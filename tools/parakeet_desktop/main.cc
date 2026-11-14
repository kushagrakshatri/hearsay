// Desktop twin of the app's native Parakeet engine (app/src/main/cpp/parakeet_core.cc, same file compiled here).
// usage: parakeet_desktop <model_dir> <threads> <l11_out_dir|-> <wav>...
// Prints one JSON line per wav: tokens, frames, durations, encoder frames, per-stage ms. With an output dir,
// writes <name>.l11.f32 (encoder frames x 1024 float32, layer-11 states) when the encoder has that output.
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "parakeet_core.h"

static std::vector<float> ReadWav16(const char *path) {
  std::ifstream f(path, std::ios::binary);
  std::vector<char> b((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
  size_t p = 12;
  while (p + 8 <= b.size()) {
    uint32_t sz; std::memcpy(&sz, &b[p + 4], 4);
    if (std::memcmp(&b[p], "data", 4) == 0) {
      std::vector<float> s(sz / 2);
      for (size_t i = 0; i < s.size(); ++i) { int16_t v; std::memcpy(&v, &b[p + 8 + 2 * i], 2); s[i] = v / 32768.f; }
      return s;
    }
    p += 8 + sz + (sz & 1);
  }
  return {};
}

static int CountLines(const std::string &path) {
  std::ifstream f(path); std::string l; int n = 0;
  while (std::getline(f, l)) if (!l.empty()) ++n;
  return n;
}

int main(int argc, char **argv) {
  if (argc < 5) { std::fprintf(stderr, "usage: %s model_dir threads l11_dir|- wav...\n", argv[0]); return 2; }
  std::string m = argv[1];
  int threads = std::atoi(argv[2]);
  std::string l11dir = argv[3];
  std::string enc = getenv("PK_ENCODER") ? getenv("PK_ENCODER") : m + "/encoder.int8.onnx";
  hearsay::Parakeet pk(enc, m + "/decoder.int8.onnx", m + "/joiner.int8.onnx", CountLines(m + "/tokens.txt"), threads);
  std::fprintf(stderr, "layer11 output: %s\n", pk.HasLayer11() ? "yes" : "no");
  for (int i = 4; i < argc; ++i) {
    auto a = ReadWav16(argv[i]);
    auto r = pk.Run(a.data(), static_cast<int32_t>(a.size()), l11dir != "-");
    std::string name = argv[i]; name = name.substr(name.find_last_of('/') + 1);
    std::printf("{\"f\": \"%s\", \"sec\": %.3f, \"T\": %d, \"fbank_ms\": %.2f, \"enc_ms\": %.2f, \"dec_ms\": %.2f, \"tokens\": [",
                name.c_str(), a.size() / 16000.0, r.num_frames, r.ms_fbank, r.ms_encoder, r.ms_decode);
    for (size_t k = 0; k < r.tokens.size(); ++k) std::printf("%s%d", k ? ", " : "", r.tokens[k]);
    std::printf("], \"frames\": [");
    for (size_t k = 0; k < r.frames.size(); ++k) std::printf("%s%d", k ? ", " : "", r.frames[k]);
    std::printf("], \"durations\": [");
    for (size_t k = 0; k < r.durations.size(); ++k) std::printf("%s%d", k ? ", " : "", r.durations[k]);
    std::printf("], \"l11\": %s}\n", r.has_layer11 ? "true" : "false");
    std::fflush(stdout);
    if (r.has_layer11) {
      std::ofstream o(l11dir + "/" + name + ".l11.f32", std::ios::binary);
      o.write(reinterpret_cast<const char *>(r.layer11.data()), r.layer11.size() * sizeof(float));
    }
  }
}
