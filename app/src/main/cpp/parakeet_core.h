// Parakeet TDT 0.6B v2 (int8 ONNX export) run directly on ONNX Runtime: fbank -> encoder -> TDT greedy.
// A line-for-line port of sherpa-onnx 2.0.1's offline NeMo transducer path (offline-stream.cc,
// math.cc NemoNormalizePerFeature, offline-transducer-greedy-search-nemo-decoder.cc DecodeOneTDT),
// so the text matches what the app got from sherpa. It also hands out the encoder's layer-11 states
// when the encoder file carries that graph output. No JNI in here: the desktop parity test builds it too.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace hearsay {

struct ParakeetResult {
  std::vector<int32_t> tokens;     // token ids, blank excluded
  std::vector<int32_t> frames;     // encoder frame (80 ms) where each token was emitted
  std::vector<int32_t> durations;  // TDT duration index (0..4) chosen with each token
  int32_t num_frames = 0;          // encoder frames for the piece
  bool has_layer11 = false;
  std::vector<float> layer11;      // num_frames x kLayer11Dim when requested and available
  double ms_fbank = 0, ms_encoder = 0, ms_decode = 0;
};

class Parakeet {
 public:
  static constexpr int32_t kLayer11Dim = 1024;
  static constexpr const char *kLayer11Output = "/layers.11/norm_out/LayerNormalization_output_0";

  // vocab_size: lines in tokens.txt (1025; blank is the last). threads: intra = inter op threads, as sherpa sets.
  Parakeet(const std::string &encoder, const std::string &decoder, const std::string &joiner,
           int32_t vocab_size, int32_t threads);
  ~Parakeet();

  // 16 kHz mono float samples in [-1, 1].
  ParakeetResult Run(const float *samples, int32_t n, bool want_layer11) const;

  // The encoder file has the layer-11 graph output (the on-phone patched encoder).
  bool HasLayer11() const;

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace hearsay
