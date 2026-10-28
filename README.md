# offline-flow

**An open-source, offline-first voice dictation keyboard for Android.** Think Wispr Flow, but your audio never leaves your phone: no account, no subscription, no cloud.

Hold the mic, talk naturally, release. Clean text lands in whatever app you're typing in.

> Status: **M0, raw dictation.** Early and rough. See the [spec](docs/spec.md) for where this is going.

## How it works

```
Mic (16 kHz, in memory only) → silence trim → whisper.cpp (on-device) → cleanup → your app
```

- **Speech-to-text:** [whisper.cpp](https://github.com/ggml-org/whisper.cpp) via JNI, English `base.en` quantized model (~60 MB, downloaded once).
- **Cleanup:** rules today (fillers, punctuation, capitalization). A small local LLM via llama.cpp lands in M1.
- **Privacy:** the only network call is the one-time model download. No analytics, no telemetry, nothing saved.

## Try it

1. Grab the debug APK from the latest [Actions run](../../actions), or build it:
   ```bash
   git clone --recursive https://github.com/kushagrakshatri/offline-flow.git
   cd offline-flow
   ./gradlew :app:assembleDebug
   ```
   Needs Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1.
2. Open **offline-flow**, allow the mic, download the model, enable the keyboard and switch to it.
3. In any text field: hold **Hold to talk**, speak, release. Tap **Raw** to see the untouched transcript.

The status line shows latency for each dictation. Please share numbers and your phone model in an issue.

## Roadmap

- **M0** raw on-device dictation + latency benchmarks ← *now*
- **M1** local-LLM cleanup, Silero VAD, undo, verified model downloads
- **M2** per-app tone, personal dictionary, snippets
- **M3** command mode ("make this shorter")
- **M4** releases on GitHub + F-Droid

## Layout

```
app/src/main/java/dev/offlineflow/
  ime/        keyboard (InputMethodService)
  audio/      mic capture + silence trim
  asr/        SpeechEngine interface, WhisperEngine (JNI)
  cleanup/    Cleaner interface, RulesCleaner
  model/      model download/storage
app/src/main/cpp/   JNI bridge + CMake for whisper.cpp
third_party/whisper.cpp   git submodule (v1.9.5)
```

## License

Apache-2.0. whisper.cpp is MIT. Models are downloaded from their publishers under their own licenses.
