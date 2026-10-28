# Hearsay (formerly offline-flow) — Spec v0.2 (Oct 27, 2026)

## 1. What it is
An open-source Android keyboard that turns speech into clean, ready-to-send text, fully on the phone. Hold the mic, talk naturally, release; polished text lands in whatever app you're in. No account, no subscription, audio never leaves the device.

**Goals**
- Works in any app (system keyboard / IME).
- Offline by default: speech-to-text and cleanup both run on-device.
- Feels close to Wispr Flow: removes "um/uh", fixes punctuation and casing, handles self-corrections ("Tuesday, no, Wednesday" → "Wednesday"), formats lists.
- Fast enough to use daily on a recent mid-to-high-end phone.

**Non-goals (v1)**
- iOS (later).
- Full typing keyboard competing with Gboard (minimal QWERTY fallback only).
- Real-time translation, meeting transcription, cloud sync.

## 2. User experience
- **Dictate:** hold-to-talk or tap-to-toggle mic. Live waveform; optional rough live partials.
- **Release:** raw transcript → cleanup → inserted at cursor.
- **Undo to raw:** one tap swaps the cleaned text back to the exact transcript.
- **Per-app style:** casual in messaging apps, formal in email/Slack, neutral elsewhere (detected from the app's package name; user can override).
- **Personal dictionary:** names, jargon, acronyms that bias recognition and get spelled right.
- **Snippets:** say "my address" → inserts saved text.
- **Command mode (v1.1):** select text, hold mic, say "make this shorter" / "more formal" / "turn into bullets".
- **Settings app:** model choice, styles, dictionary, privacy toggles, optional cloud key.

## 3. Architecture
```
Mic (AudioRecord, 16 kHz mono)
  → VAD (Silero VAD, trims silence, auto-stop)
  → ASR engine (on-device)
  → Cleanup engine (local LLM, or rules fallback)
  → InputConnection.commitText() into the focused app
```
- **App shell:** Kotlin, Jetpack Compose, `InputMethodService`. Settings as a normal activity in the same APK.
- **ASR engine (pluggable interface):**
  - Default: whisper.cpp via JNI, quantized GGUF models (base / small, English first, multilingual optional).
  - Alternative to benchmark: Moonshine or sherpa-onnx streaming models for lower latency.
  - Dictionary words fed as an initial prompt to bias spelling.
- **Cleanup engine (pluggable):**
  - Default: llama.cpp with a small instruct model (~1–3B, quantized), short system prompt + style + dictionary, low temperature, output capped near input length.
  - Fallback for low-RAM devices: deterministic rules (filler removal, punctuation, capitalization).
  - Guardrail: if the LLM output drifts too far from the transcript (length/similarity check), insert the rules version instead.
- **Context:** app package + input type from `EditorInfo`; a little text before the cursor via `getTextBeforeCursor` for tone and continuity. Never stored.
- **Optional cloud mode (opt-in):** bring-your-own key for an OpenAI-compatible ASR/LLM endpoint. Off by default, clearly labeled.

## 4. Models and devices
- Models download on first launch (not bundled), with checksums, resumable downloads, and a storage screen to delete them.
- Device tiers picked automatically by RAM/CPU, user can override:
  - **High:** whisper small + ~3B LLM.
  - **Mid:** whisper base + ~1B LLM.
  - **Low:** whisper base + rules-only cleanup.
- Inference runs off the main thread; models stay warm while the keyboard is open, released when it closes.

## 5. Privacy
- Audio held in memory only, discarded after each dictation. No transcripts saved unless the user turns on history.
- No analytics or telemetry. Network used only for model downloads and opt-in cloud mode.
- Ship an F-Droid build flavor with cloud mode compiled out.

## 6. Targets (to validate in M0)
- Under ~1.5 s from release to text for a 10–15 s utterance on a recent flagship.
- Cleanup never invents content; word-level meaning preserved.
- Keyboard memory stays within what Android tolerates for an IME without getting killed.

## 7. Milestones
- **M0 — Raw dictation:** IME skeleton, mic + VAD, whisper.cpp inserting raw text. Benchmark latency on 2–3 phones.
- **M1 — Cleanup:** llama.cpp cleanup, rules fallback, undo-to-raw, model downloader.
- **M2 — Personal:** per-app styles, dictionary, snippets, settings polish.
- **M3 — Command mode:** edit selected text by voice.
- **M4 — Release:** README, docs, CI builds, GitHub Releases + F-Droid.

## 8. Risks
- Latency and battery/thermals on mid-range phones (mitigate: tiers, rules fallback, smaller models).
- IME process memory limits with two models loaded (mitigate: lazy load, unload LLM when idle).
- LLM "helpfully" rewriting meaning (mitigate: tight prompt, drift check, undo).
- Model licenses vary: pick ones that allow redistribution/download in an OSS app; code under Apache-2.0 (whisper.cpp and llama.cpp are MIT).

## 9. Open questions
- ~~English-only v1 or multilingual?~~ Decided: English-only v1 (*.en models).
- Which cleanup model ships as default (benchmark 2–3 candidates in M1).
- ~~Minimum Android version~~ Decided: Android 10+ (minSdk 29).
- ~~Name and license~~ Decided: Hearsay (renamed from offline-flow Oct 27), Apache-2.0. Kotlin package stays dev.offlineflow for now.

## Update Oct 28, 2026: speech engines and distribution
- Two on-device ASR engines behind `SpeechEngine`: **Parakeet TDT 0.6B v2 int8** (NVIDIA, via sherpa-onnx 2.0.1 AAR, ~661 MB, default on phones with ≥ ~6 GB RAM) and **Whisper base.en q5_1** (whisper.cpp, ~60 MB, default otherwise / fallback). User picks in the setup screen.
- Every downloaded model file is pinned by size + SHA-256 and deleted on mismatch.
- Every push to main publishes a GitHub Release `build-N` with `hearsay-debug.apk`; signed with a committed debug key so builds upgrade in place. Newest: https://github.com/kushagrakshatri/hearsay/releases/latest/download/hearsay-debug.apk
- Candidates later: Moonshine (tiny/base) for low-end phones; streaming/partials.

## Update Oct 28, 2026: local LLM cleanup (M1)
- New Gradle module `:llm` builds llama.cpp (submodule third_party/llama.cpp, pinned 8642541 / v0.8.0) into libhearsayllm.so, separate from whisper's ggml; both libs link with `--exclude-libs,ALL`.
- Cleanup models (GGUF, SHA-256 pinned, all Apache-2.0): Qwen3.5 2B Q4_K_M (~1.28 GB, default on ≥ ~8 GB RAM), Qwen3.5 0.8B Q4_K_M (~530 MB, default otherwise), Gemma 4 E2B Q4_0 (~2.84 GB, opt-in, best quality/slower). "Off" = RulesCleaner. Replaced Qwen2.5 the same day after the user asked about Gemma.
- Prompts are formatted in Kotlin per family (ChatML with empty <think> for Qwen3.5; <|turn> format for Gemma 4). System prompt + 4 few-shot examples are decoded once and the sequence state is snapshotted (works for hybrid/SWA memory); each utterance restores it and decodes only its own turn. Greedy decoding, output rejected (fallback to rules) when empty or length ratio is off.
- IME status shows "speech X + cleanup Y" ms; "Raw" still swaps back to the exact transcript.
