# Own Parakeet engine: desktop parity (Nov 14, 2026)

Desktop twin = `main.cc` + the app's `app/src/main/cpp/parakeet_core.cc`, built with `build.sh` against ONNX Runtime
1.28.2 (linux-aarch64, the version inside the sherpa-onnx 2.0.1 AAR), 2 threads, the app's pinned int8 files
(HF rev 1ab93235). Reference: sherpa-onnx 2.0.1 Python, same files, 2 threads. 62 synthetic clips
(tools/eval/prosody/audio, 162 s).

| check | result |
|---|---|
| text = sherpa | **62/62** (target ≥ 60/62) |
| tokens = sherpa | 62/62 |
| token frames = sherpa | 44/62 (runtime noise; sherpa vs itself under 1e-5 input noise: 39–45/62) |
| patched encoder (+layer-11 output): tokens and frames vs unpatched | 62/62, 62/62 |
| sherpa on the patched encoder: text | 62/62 (extra output is ignored by position) |
| layer-11 rows vs Python ORT 1.28.0 (pkenc2 front end) | shapes 62/62, mean per-frame cosine 0.998, median clip 0.9989 |
| same, Python vs Python with 1e-5 input noise | mean 0.997 (worst clip f4h 0.951 in both) |
| time, own engine | 69 ms per second of audio (encoder 64; fbank ~1, decode loop ~3) vs sherpa Python 78 ms/s, separate runs, aarch64 sandbox |

On-phone encoder patch (`model/EncoderPatch.kt`), run on desktop with the app's Kotlin code: 1.7 s, output
652,184,369 bytes, SHA-256 081b59fa…ae60c, byte-identical to `onnx.save` of the same edit
(tools/eval/intent/layers/export_layers.py). A corrupted original fails the SHA check and stays in place.
