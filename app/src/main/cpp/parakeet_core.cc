// See parakeet_core.h. The reference this reproduces is tools/eval/encoder_on_phone/pkenc2.py.
#include "parakeet_core.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <limits>
#include <stdexcept>

#include "kaldi-native-fbank/csrc/online-feature.h"
#include "onnxruntime_cxx_api.h"

namespace hearsay {
namespace {

constexpr int32_t kSampleRate = 16000;
constexpr int32_t kFeatDim = 128;
constexpr int32_t kMaxTokensPerFrame = 5;  // DecodeOneTDT

double MsSince(std::chrono::steady_clock::time_point t0) {
  return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

Ort::Env &GlobalEnv() {
  static Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "hearsay-parakeet");
  return env;
}

std::vector<std::string> Names(const Ort::Session &s, bool inputs) {
  Ort::AllocatorWithDefaultOptions a;
  std::vector<std::string> v;
  size_t n = inputs ? s.GetInputCount() : s.GetOutputCount();
  for (size_t i = 0; i < n; ++i)
    v.emplace_back(inputs ? s.GetInputNameAllocated(i, a).get() : s.GetOutputNameAllocated(i, a).get());
  return v;
}

std::vector<const char *> Ptrs(const std::vector<std::string> &v) {
  std::vector<const char *> p;
  for (auto &s : v) p.push_back(s.c_str());
  return p;
}

// sherpa-onnx offline-stream.cc for NeMo models + nemo impl PostInit: 128 bins, low 0, high -400, librosa
// mel, povey window, preemph 0.97, no DC removal, no snip, no dither, additive log guard 2^-24.
std::vector<float> Fbank(const float *samples, int32_t n, int32_t *num_frames) {
  knf::FbankOptions o;
  o.frame_opts.samp_freq = kSampleRate;
  o.frame_opts.dither = 0;
  o.frame_opts.snip_edges = false;
  o.frame_opts.remove_dc_offset = false;
  o.frame_opts.preemph_coeff = 0.97f;
  o.frame_opts.window_type = "povey";
  o.mel_opts.num_bins = kFeatDim;
  o.mel_opts.low_freq = 0;
  o.mel_opts.high_freq = -400;
  o.mel_opts.is_librosa = true;
  o.use_log_floor = false;
  o.eps = std::numeric_limits<float>::epsilon() / 2;
  knf::OnlineFbank fb(o);
  fb.AcceptWaveform(kSampleRate, samples, n);
  fb.InputFinished();
  int32_t T = fb.NumFramesReady();
  std::vector<float> x(static_cast<size_t>(T) * kFeatDim);
  for (int32_t t = 0; t < T; ++t) std::copy_n(fb.GetFrame(t), kFeatDim, x.data() + static_cast<size_t>(t) * kFeatDim);
  *num_frames = T;
  return x;
}

// math.cc NemoNormalizePerFeature: per bin (x - mean) * 1 / (population std + 1e-5), over the whole piece.
void NormalizePerFeature(float *x, int32_t T) {
  for (int32_t d = 0; d < kFeatDim; ++d) {
    double sum = 0;
    for (int32_t t = 0; t < T; ++t) sum += x[t * kFeatDim + d];
    float mean = static_cast<float>(sum / T);
    double sq = 0;
    for (int32_t t = 0; t < T; ++t) { double v = x[t * kFeatDim + d] - mean; sq += v * v; }
    float inv_std = 1.0f / (std::sqrt(static_cast<float>(sq / T)) + 1e-5f);
    for (int32_t t = 0; t < T; ++t) x[t * kFeatDim + d] = (x[t * kFeatDim + d] - mean) * inv_std;
  }
}

}  // namespace

struct Parakeet::Impl {
  Ort::SessionOptions opts;
  std::unique_ptr<Ort::Session> enc, dec, joi;
  std::vector<std::string> enc_in, enc_out, dec_in, dec_out, joi_in, joi_out;
  bool has_l11 = false;
  int32_t vocab = 0, blank = 0;
  std::vector<int64_t> state_shape;  // decoder LSTM states, e.g. [2, 1, 640]
  int32_t dec_dim = 0;
  Ort::MemoryInfo mem = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);

  // One decoder step: feeds token y with states, returns the decoder output [dec_dim] and new states.
  void Decode(int32_t y, std::vector<float> &s0, std::vector<float> &s1, std::vector<float> &out) const {
    std::array<int64_t, 2> ts{1, 1};
    std::array<int64_t, 1> ls{1};
    int32_t tgt = y, len = 1;
    std::array<Ort::Value, 4> in{
        Ort::Value::CreateTensor<int32_t>(mem, &tgt, 1, ts.data(), ts.size()),
        Ort::Value::CreateTensor<int32_t>(mem, &len, 1, ls.data(), ls.size()),
        Ort::Value::CreateTensor<float>(mem, s0.data(), s0.size(), state_shape.data(), state_shape.size()),
        Ort::Value::CreateTensor<float>(mem, s1.data(), s1.size(), state_shape.data(), state_shape.size()),
    };
    auto ip = Ptrs(dec_in), op = Ptrs(dec_out);
    auto o = dec->Run(Ort::RunOptions{nullptr}, ip.data(), in.data(), in.size(), op.data(), op.size());
    const float *d = o[0].GetTensorData<float>();
    out.assign(d, d + dec_dim);
    const float *a = o[2].GetTensorData<float>();
    const float *b = o[3].GetTensorData<float>();
    s0.assign(a, a + s0.size());
    s1.assign(b, b + s1.size());
  }
};

Parakeet::Parakeet(const std::string &encoder, const std::string &decoder, const std::string &joiner,
                   int32_t vocab_size, int32_t threads)
    : impl_(new Impl) {
  auto &m = *impl_;
  m.opts.SetIntraOpNumThreads(threads);
  m.opts.SetInterOpNumThreads(threads);  // sherpa sets both; ORT's default graph optimisation (ALL), as sherpa
  Ort::SessionOptions small;             // decoder and joiner: same as sherpa (one options object for all three)
  small.SetIntraOpNumThreads(threads);
  small.SetInterOpNumThreads(threads);
  m.enc.reset(new Ort::Session(GlobalEnv(), encoder.c_str(), m.opts));
  m.dec.reset(new Ort::Session(GlobalEnv(), decoder.c_str(), small));
  m.joi.reset(new Ort::Session(GlobalEnv(), joiner.c_str(), small));
  m.enc_in = Names(*m.enc, true);
  m.enc_out = Names(*m.enc, false);
  m.dec_in = Names(*m.dec, true);
  m.dec_out = Names(*m.dec, false);
  m.joi_in = Names(*m.joi, true);
  m.joi_out = Names(*m.joi, false);
  if (m.enc_in.size() != 2 || m.enc_out.size() < 2 || m.dec_in.size() != 4 || m.dec_out.size() != 4 ||
      m.joi_in.size() != 2 || m.joi_out.size() != 1)
    throw std::runtime_error("unexpected Parakeet ONNX inputs/outputs");
  m.has_l11 = std::find(m.enc_out.begin(), m.enc_out.end(), kLayer11Output) != m.enc_out.end();
  m.vocab = vocab_size;
  m.blank = vocab_size - 1;
  // Decoder states from the graph: [layers, batch(dynamic), hidden]
  m.state_shape = m.dec->GetInputTypeInfo(2).GetTensorTypeAndShapeInfo().GetShape();
  if (m.state_shape.size() != 3) throw std::runtime_error("unexpected decoder state rank");
  m.state_shape[1] = 1;
  if (m.state_shape[0] <= 0 || m.state_shape[2] <= 0) throw std::runtime_error("dynamic decoder state shape");
  auto dshape = m.dec->GetOutputTypeInfo(0).GetTensorTypeAndShapeInfo().GetShape();  // [1, 640, 1]
  m.dec_dim = dshape.size() >= 2 && dshape[1] > 0 ? static_cast<int32_t>(dshape[1]) : static_cast<int32_t>(m.state_shape[2]);
}

Parakeet::~Parakeet() = default;

bool Parakeet::HasLayer11() const { return impl_->has_l11; }

ParakeetResult Parakeet::Run(const float *samples, int32_t n, bool want_layer11) const {
  auto &m = *impl_;
  ParakeetResult r;
  if (n <= 0) return r;

  // ---- features
  auto t0 = std::chrono::steady_clock::now();
  int32_t T = 0;
  std::vector<float> feats = Fbank(samples, n, &T);
  if (T <= 0) return r;
  NormalizePerFeature(feats.data(), T);
  // [T, 128] -> [1, 128, T] (sherpa's RunEncoder transposes the same way)
  std::vector<float> x(feats.size());
  for (int32_t t = 0; t < T; ++t)
    for (int32_t d = 0; d < kFeatDim; ++d) x[static_cast<size_t>(d) * T + t] = feats[static_cast<size_t>(t) * kFeatDim + d];
  r.ms_fbank = MsSince(t0);

  // ---- encoder
  t0 = std::chrono::steady_clock::now();
  std::array<int64_t, 3> xs{1, kFeatDim, T};
  std::array<int64_t, 1> ls{1};
  int64_t len = T;
  std::array<Ort::Value, 2> in{
      Ort::Value::CreateTensor<float>(m.mem, x.data(), x.size(), xs.data(), xs.size()),
      Ort::Value::CreateTensor<int64_t>(m.mem, &len, 1, ls.data(), ls.size()),
  };
  bool l11 = want_layer11 && m.has_l11;
  std::vector<std::string> outs{m.enc_out[0], m.enc_out[1]};
  if (l11) outs.emplace_back(kLayer11Output);
  auto ip = Ptrs(m.enc_in), op = Ptrs(outs);
  auto eo = m.enc->Run(Ort::RunOptions{nullptr}, ip.data(), in.data(), in.size(), op.data(), op.size());
  auto shape = eo[0].GetTensorTypeAndShapeInfo().GetShape();  // [1, 1024, T']
  if (shape.size() != 3) throw std::runtime_error("unexpected encoder output rank");
  const int32_t D = static_cast<int32_t>(shape[1]), Tmax = static_cast<int32_t>(shape[2]);
  int32_t Te = static_cast<int32_t>(eo[1].GetTensorData<int64_t>()[0]);
  Te = std::max(0, std::min(Te, Tmax));
  // Transpose to [T', 1024] rows (sherpa Transpose12)
  std::vector<float> enc(static_cast<size_t>(Te) * D);
  const float *e = eo[0].GetTensorData<float>();
  for (int32_t d = 0; d < D; ++d)
    for (int32_t t = 0; t < Te; ++t) enc[static_cast<size_t>(t) * D + d] = e[static_cast<size_t>(d) * Tmax + t];
  r.num_frames = Te;
  if (l11) {
    auto s11 = eo[2].GetTensorTypeAndShapeInfo().GetShape();  // [1, T', 1024]
    if (s11.size() == 3 && s11[2] == kLayer11Dim && s11[1] >= Te) {
      const float *p = eo[2].GetTensorData<float>();
      r.layer11.assign(p, p + static_cast<size_t>(Te) * kLayer11Dim);
      r.has_layer11 = true;
    }
  }
  r.ms_encoder = MsSince(t0);

  // ---- TDT greedy, line for line with sherpa DecodeOneTDT
  t0 = std::chrono::steady_clock::now();
  size_t ssz = static_cast<size_t>(m.state_shape[0] * m.state_shape[1] * m.state_shape[2]);
  std::vector<float> s0(ssz, 0.f), s1(ssz, 0.f), dout;
  m.Decode(m.blank, s0, s1, dout);
  std::array<int64_t, 3> es{1, D, 1};
  std::array<int64_t, 3> ds{1, static_cast<int64_t>(m.dec_dim), 1};
  auto jp = Ptrs(m.joi_in), jo = Ptrs(m.joi_out);
  int32_t tokens_this_frame = 0, skip = 0;
  for (int32_t t = 0; t < Te; t += skip) {
    std::array<Ort::Value, 2> jin{
        Ort::Value::CreateTensor<float>(m.mem, enc.data() + static_cast<size_t>(t) * D, D, es.data(), es.size()),
        Ort::Value::CreateTensor<float>(m.mem, dout.data(), dout.size(), ds.data(), ds.size()),
    };
    auto lo = m.joi->Run(Ort::RunOptions{nullptr}, jp.data(), jin.data(), jin.size(), jo.data(), jo.size());
    const float *p = lo[0].GetTensorData<float>();
    int32_t out_size = static_cast<int32_t>(lo[0].GetTensorTypeAndShapeInfo().GetShape().back());
    int32_t num_durations = out_size - m.vocab;
    if (num_durations <= 0) throw std::runtime_error("joiner output smaller than vocab");
    int32_t y = static_cast<int32_t>(std::max_element(p, p + m.vocab) - p);
    skip = static_cast<int32_t>(std::max_element(p + m.vocab, p + m.vocab + num_durations) - (p + m.vocab));
    if (y != m.blank) {
      r.tokens.push_back(y);
      r.frames.push_back(t);
      r.durations.push_back(skip);
      m.Decode(y, s0, s1, dout);
      tokens_this_frame += 1;
    }
    if (skip > 0) tokens_this_frame = 0;
    if (tokens_this_frame >= kMaxTokensPerFrame) { tokens_this_frame = 0; skip = 1; }
    if (y == m.blank && skip == 0) { tokens_this_frame = 0; skip = 1; }
  }
  r.ms_decode = MsSince(t0);
  return r;
}

}  // namespace hearsay
