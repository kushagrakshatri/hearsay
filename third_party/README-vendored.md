# Vendored sources for the own Parakeet engine (app/src/main/cpp/parakeet_core.cc)

| dir | upstream | version | license |
|---|---|---|---|
| `kaldi-native-fbank/` | https://github.com/csukuangfj/kaldi-native-fbank | v1.22.4 (tarball SHA-256 257fea63…ea545fae, the version sherpa-onnx 2.0.1 pins); `kaldi-native-fbank/csrc` without tests and Python | Apache-2.0 |
| `kissfft/` | https://github.com/mborgerding/kissfft | commit febd4cae (zip SHA-256 497103e6…ca7bc42d0ab13fd, what knf 1.22.4 fetches); `kiss_fft*`, `kiss_fftr*` only, built with `kiss_fft_scalar=float` | BSD-3-Clause |
| `onnxruntime-headers/` | https://github.com/microsoft/onnxruntime | v1.28.2 (`include/` of onnxruntime-linux-aarch64-1.28.2.tgz, commit 33ca9628) = the runtime inside the sherpa-onnx 2.0.1 AAR | MIT |

Unmodified copies. The runtime library itself is not vendored: CMake links against `jni/<abi>/libonnxruntime.so` unzipped from the pinned sherpa-onnx AAR (see app/build.gradle.kts).
