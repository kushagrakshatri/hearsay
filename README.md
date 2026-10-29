# Hearsay

**Talk. It types.** Free, open-source voice dictation for Android that runs entirely on your phone.

Hold the floating mic in any app, say what you mean (ums, restarts, "no wait, Thursday" and all), and clean, punctuated text lands where your cursor is. No account, no subscription, no audio leaving your phone.

## Why Hearsay

- **Private by design.** Speech recognition and cleanup both run on-device. No analytics, no telemetry, nothing stored on a server.
- **Works offline.** Planes, basements, bad signal: no network round trip, so no network lag either.
- **Free and open source.** Apache-2.0. Read the code, build it yourself.
- **Learns your words.** Fix a name once and Hearsay adds it to your dictionary by itself.
- **Knows who you're talking to.** The floating mic spells names it sees on screen (a chat contact, people in an email thread) the same way. That happens on the phone, just for that dictation, and nothing is saved.
- **Writes like you, per app.** Casual in chats, polished in email, verbatim for code and search.
- **Edit by voice.** Select text and say "make this shorter" or "turn it into bullets". Say "scratch that" to undo.
- **Snippets.** Say "my email" and get your address.
- **Works with your keyboard.** The floating mic sits above Gboard, SwiftKey or anything else. Prefer a dedicated voice keyboard? Hearsay has one too.

## How it works

```
Mic (16 kHz, in memory only) → Silero VAD → Parakeet TDT 0.6B or Whisper (on-device)
  → your dictionary + names on screen → small local LLM cleanup (Qwen3.5 / Gemma 4) → your app
```

- **Speech:** NVIDIA Parakeet TDT 0.6B v2 (int8) via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), or [whisper.cpp](https://github.com/ggml-org/whisper.cpp) `base.en` for lighter phones.
- **Cleanup:** [llama.cpp](https://github.com/ggml-org/llama.cpp) with Qwen3.5 0.8B / 2B or Gemma 4 E2B, picked to fit your phone's RAM. Models download once, SHA-256 verified.
- **Optional cloud boost:** sign in with ChatGPT or bring your own OpenAI-compatible key for cleanup. Off by default. Your voice is still transcribed on the phone. The F-Droid build leaves this out.

## Install

- **Download:** [hearsay.apk](https://github.com/kushagrakshatri/hearsay/releases/latest/download/hearsay.apk) (signed release, Android 10+, 64-bit ARM).
- **F-Droid:** coming soon.
- **Google Play:** coming soon.

Then open Hearsay. Setup walks you through the mic permission, the model download (about 0.6 to 2 GB, depending on what you pick) and turning on the floating mic.

Android may grey out the accessibility switch for apps installed outside a store. Open App info, tap ⋮, then **Allow restricted settings**.

## Build it

```bash
git clone --recursive https://github.com/kushagrakshatri/hearsay.git
cd hearsay
./gradlew :app:assembleDebug            # add -PnoCloud to compile out all cloud features
```

Needs JDK 17, Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1.

## Privacy

See [PRIVACY.md](PRIVACY.md). In short: audio stays in memory, on your phone, and is gone after each dictation. The only network traffic is model downloads, plus ChatGPT or your own key if you turn that on.

## Layout

```
app/src/main/java/dev/offlineflow/
  bubble/   floating mic (AccessibilityService overlay)
  ime/      voice keyboard (InputMethodService)
  core/     Brain: the shared models, learning from fixes
  audio/    mic capture + Silero VAD
  asr/      Parakeet (sherpa-onnx) and Whisper (JNI)
  cleanup/  LLM cleanup, styles, dictionary, learner, screen names, snippets, commands
  cloud/    optional ChatGPT / own-key cleanup
  model/    verified model downloads
llm/        llama.cpp JNI module
docs/spec.md  design spec
```

## License

Apache-2.0. whisper.cpp and llama.cpp are MIT, and sherpa-onnx is Apache-2.0. The common-words list comes from SCOWL (see `app/src/main/assets/words/SCOWL-LICENSE.txt`). Models are downloaded from their publishers under their own licenses.
