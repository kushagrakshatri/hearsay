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
- **Sign in with ChatGPT (opt-in):** OpenAI's open-source ChatGPT plan usage flow (OAuth + PKCE, loopback redirect, no API key). Cleanup and commands run on the user's plan via the Responses API (default model `gpt-6-luna`, reasoning effort none). Speech always stays on-device (the flow takes no audio). Falls back to the on-device LLM when offline or over the plan limit. Compiled out with `-PnoCloud`.

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

## Update Oct 28, 2026: Silero VAD + hands-free (completes M1)
- Silero VAD (silero_vad.onnx, 644 KB, MIT, bundled in assets, SHA-256 9e2449e1…) runs through the sherpa-onnx Vad API on the mic thread (threshold 0.4, min silence 0.4 s, min speech 0.15 s).
- Only speech stretches (padded 250 ms) reach the ASR model; long pauses are cut. No speech at all → "Didn't hear any speech" and nothing is transcribed (stops Whisper hallucinating on silence). Energy trim remains the fallback if the VAD can't load.
- Mic: hold to talk (unchanged), or a quick tap (<350 ms) for hands-free, which stops after 1.5 s of silence following speech, after 8 s with no speech, or on a second tap.

## Update Oct 28, 2026: personal dictionary (M2, part 1)
- Settings has a free-text dictionary: one entry per line; `heard -> written` lines are fixes. Stored in app prefs only.
- Applied in three places: (1) to the ASR text (fixes, exact-case, split-word joins like "hear say" → "Hearsay", one-letter-off for terms ≥6 letters); (2) in the LLM system prompt ("spell these exactly", up to 150 entries; a dictionary change reloads the cached prefix); (3) again on the cleaned text. "Raw" still restores the exact ASR transcript.
- ASR-level biasing (Whisper initial prompt / sherpa hotwords) deferred: Parakeet TDT hotword support in sherpa-onnx is unverified.
- CI now runs `:app:testDebugUnitTest` (DictionaryTest) before assembling.

## Update Oct 28, 2026: per-app styles (M2, part 2)
- Styles: Casual (texting: conversational, light punctuation, no period on a single short sentence), Polished (email/docs/work chat: complete sentences), Verbatim (code, URLs, numbers, passwords, search: transcript as heard + dictionary, no LLM).
- Default from the app (known messaging / work / code packages) and field type (URI, email address, password, number, filter → Verbatim; short message → Casual; else Polished). A chip on the keyboard cycles the style; the choice is saved per app.
- Each style has its own system rules and few-shot examples; llama.cpp prefixes are cached per style (lazily, warmed when the keyboard opens), so switching styles costs one prefix evaluation once.

## Update Oct 28, 2026: floating mic (default) + keyboard (fallback)
- Floating mic: an AccessibilityService draws a round mic (TYPE_ACCESSIBILITY_OVERLAY) above whatever keyboard the user has, when an editable, non-password field is focused. Hold to talk, tap for hands-free, drag to move. Text is inserted with ACTION_SET_TEXT + ACTION_SET_SELECTION, falling back to clipboard paste (clipboard restored). Supports styles, dictionary, snippets, "scratch that", and voice edits on selected text. It reads the focused field, plus visible on-screen names when "Spell names on screen" is on (see below).
- The Hearsay keyboard stays as the fallback: most reliable insertion, undo-to-raw, works where accessibility is blocked.
- Models are shared between the two (core/Brain), so only one copy is loaded.
- Known limits: secure/banking fields and some apps refuse accessibility text; Android 13+ requires "Allow restricted settings" for sideloaded APKs; Play reviews accessibility use closely (F-Droid first).
- Not in the bubble yet: undo-to-raw, live waveform beyond the mic halo.

## Update Oct 29, 2026: beating Flow — learns your words + screen context
Goal: be better than Wispr Flow, not just match it. These two make Hearsay feel like it knows you, and both run entirely on the phone.
- **Learns from your fixes (on by default, Settings → Dictionary → "Learn from my fixes").** After inserting, Hearsay remembers the inserted stretch (`cleanup/CorrectionWatch`). Floating mic: it watches `TYPE_VIEW_TEXT_CHANGED` on that field and compares once the user has stopped typing for 12 s, leaves the field, or starts the next dictation. Keyboard: it compares when the user comes back to the same field (e.g. after fixing a word with another keyboard). `cleanup/Learner` diffs words (LCS) and keeps up to 3 small swaps (≤3 heard words → ≤2 written) that look like a misheard name/term: the written form must not be an everyday word (SCOWL 10–35 list, `assets/words/common-en.txt`), must sound like what was heard (edit distance ≤ max(2, 40%), same or similar first sound), or be a real case spelling (GitHub, iPhone, not sentence case). Rewrites (>40% of words changed) are ignored. A swap away from a common word becomes a plain term ("Marc"), otherwise a fix ("cash agra -> Kushagra"). Entries are appended to the normal dictionary (cap 400) and a "Learned “X”" message shows.
- **Spell names on screen (floating mic, on by default, toggle in the Floating mic sheet).** When you stop talking, the service reads the visible text of the focused field's window (≤800 nodes, no password nodes), and `cleanup/ScreenNames` keeps name-like words (Capitalized or inner caps, ≥3 letters, not everyday words, not handles/links/SHOUTED headings), up to 30 by frequency. They're used for that one dictation only: exact sound-alike fixes (case, split words; no fuzzy near misses) and a per-request system note after the cached LLM prefix (cloud cleanup: an extra system message). Nothing is stored. The keyboard does the same with the text before the cursor (up to 15 names).
- Accessibility description updated to disclose reading on-screen names and watching the field for fixes (needed for Play review).
- Not yet: ASR-level biasing with these words (Parakeet hotwords), learning from fixes made in the keyboard's own field while it stays open.

## Update Oct 30, 2026: fast, consistent cleanup (GPU first)
**Lag fixes (llama.cpp path and shared code)**
- **Only sound-alike names reach the LLM.** Names on screen (up to 30) and words learned since the prompt was built are filtered by `cleanup/SoundAlike` against the transcript: a word or 1–4-word span must match after lowercasing/joining, be within a small edit distance (0 for ≤3 letters, 1 for ≤5, 30% above) with a similar first sound, or share a rough sound key ("cash agra" ≈ "Kushagra", "mug the" ≈ "Mugdha"). Names already spelled exactly are skipped. At most 5; usually none, so usually no per-dictation note at all. Cloud cleanup uses the same filter for its extra system message.
- **Learning a word doesn't rebuild the prompt.** The cached style prompt keeps the dictionary it was built with; up to 20 words added since then ride in the per-dictation note (only when they sound like something in the transcript). The prompt is rebuilt with the full dictionary on the next model load, when more than 20 piled up, or when an entry was removed.
- **Models stay loaded.** No timed unload when the floating mic or keyboard hides. Models are released only on `onTrimMemory` RUNNING_CRITICAL or COMPLETE (UI_HIDDEN and other background levels no longer unload), and when the user switches models in Settings. Field focus still warms both models; pressing the mic starts loading a dropped model in parallel with recording.
- **Prompt state survives process death.** Each evaluated llama.cpp prefix (style prompts, command-mode prompt) is saved with `llama_state_seq_save_file` to `cache/llm-prefix/<sha256(model file name+size+mtime, format, prefix text)>.kv` (newest 8 kept) and loaded back with `llama_state_seq_load_file` when its saved tokens equal the prefix's tokens; a stale file is deleted and the prefix decoded again.

**Engines: `llm/TextEngine`**
- `TextEngine` = prepare(system, few-shot examples) → handle; complete(handle, note?, user, maxTokens). `LlmCleaner` (cleanup + command mode) runs on any engine with the same system prompt, style rules, examples, dictionary/names note and `cleanWith`/`editWith` sanity checks.
- **LiteRT-LM** (`llm/LiteRtEngine`, Google `com.google.ai.edge.litertlm:litertlm-android:0.19.0`, Apache-2.0) for `.litertlm` models. Engine starts on `Backend.GPU()` (OpenCL; manifest declares optional `libOpenCL.so` / `libvndksupport.so`), else `Backend.CPU(4 threads)`. `maxNumTokens` 2048, `cacheDir` = `cache/litertlm` (compiled kernels / weight layouts for faster reloads). Greedy sampling (topK 1, topP 1, temperature 0), thinking off. Each prepared prompt is a Conversation created with `prefillPrefaceOnInit`; every dictation runs on `clone()` of it, so only the new turn is prefilled. A per-dictation note (rare) goes into the system prompt of a fresh conversation. If cloning or a second conversation isn't supported, each call opens a fresh conversation. The chat template comes from the model file.
- Crash guard: a start marker per (runtime version, model, backend) in `files/engine-state`; a GPU init that throws, or two starts in a row that never finished (driver crash), marks that backend bad so the next load goes straight to the CPU.
- **llama.cpp** (`cleanup/LlamaText` over `LlamaEngine`) stays for GGUF models, CPU only, with the hand-written chat formats and the prefix snapshots above. If a LiteRT-LM model can't start on GPU or CPU, `Brain` falls back to a downloaded GGUF cleanup model, else rules (not retried until another model is picked).
- **Models:** new default on ≥ ~8 GB RAM: **Gemma 4 E2B .litertlm** (litert-community/gemma-4-E2B-it-litert-lm, `gemma-4-E2B-it.litertlm`, 2,588,147,712 B, SHA-256 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c, ungated). Picker tiers: Light (Qwen3.5 0.8B, CPU), Balanced (Qwen3.5 2B, CPU), Best (Gemma 4 E2B, GPU with CPU fallback), Best (no GPU) (Gemma 4 E2B GGUF, CPU). Someone who never picked a model keeps an already-downloaded Qwen default rather than being moved to a new 2.6 GB download. Settings shows "graphics chip" / "processor" once the Best model is loaded.
- Not used: litert-community Qwen3.5 0.8B/2B `.litertlm` (community conversions; their cards report cross-conversation state leaks on one Engine and very slow GPU init on Android).
- Toolchain: Kotlin 2.3.21 (LiteRT-LM's AAR carries Kotlin 2.4 metadata); `kotlinOptions` moved to `compilerOptions`. R8 keeps `com.google.ai.edge.litertlm.**` and Gson. LiteRT-LM's JNI library (arm64 ~22.7 MB uncompressed) is the main APK size cost.

## Update Oct 30, 2026: LFM2.5 and MiniCPM5 as opt-in cleanup models + offline eval
- **Eval harness (`tools/eval/`).** `cleanup_eval.jsonl` is the seed eval set (45 hand-labelled dictations: demo scripts, fillers, repetitions, self-corrections, questions/instructions that must not be answered or followed, lists, numbers/emails, names, short and long, casual vs polished). `hearsay_llm.cpp` is a desktop twin of `llama_jni.cpp` (same llama.cpp calls and settings); `run_eval.py` reproduces LlmCleaner exactly (rules pre-pass, <3-word skip, style prompt + few-shots, chat format per model, greedy, cap words×2+32, strip, sanity fallback) and scores WER vs expected, meaning violations, filler removal, fallback rate, speed. Results: `tools/eval/RESULTS.md`.
- **Bake-off (untuned Q4_K_M, 2-thread ARM CPU):** near-match 42/45 Qwen3.5 2B, 34 Qwen3.5 0.8B, 26 MiniCPM5 1B, 16 LFM2.5 1.2B; meaning violations in inserted text 3 / 4 / 13 / 17. LFM2.5 behaves like a chat assistant (replies "Sure thing…", answers questions, summarizes long text, drops sign-offs); MiniCPM5 rarely applies self-corrections and sometimes refuses. LFM2.5 decode speed ≈ Qwen3.5 0.8B here; greedy doesn't loop (rep penalty 1.05 changed nothing, so none is used). Defaults unchanged.
- **New picker entries (llama.cpp, CPU, SHA-256 pinned):** "Fast (Liquid LFM2.5 1.2B)" = LiquidAI/LFM2.5-1.2B-Instruct-GGUF `LFM2.5-1.2B-Instruct-Q4_K_M.gguf`, 730,895,168 B, SHA-256 b1b3de114215d9507409a662a501a631095a479a419584e8a2ded6304b19b4f5, LFM Open License v1.0 (free under US$10M revenue; attribution in the picker blurb, README and NOTICE, license copy in `docs/licenses/`). "Fast (MiniCPM5 1B)" = openbmb/MiniCPM5-1B-GGUF `MiniCPM5-1B-Q4_K_M.gguf`, 688,065,920 B, SHA-256 81b64d05a23b17b34c475f42b3e72fbde62d4b92cc34541f7a8031d0752deafa, Apache-2.0. Both labelled experimental.
- **Chat formats:** `Format.CHATML` for LFM2.5 (ChatML, no think block; the tokenizer adds BOS `<|startoftext|>`; `<|im_end|>` is EOG). `Format.MINICPM_NO_THINK` for MiniCPM5 (`<s>` written into the prefix because its GGUF has add_bos=false, then ChatML with an empty think block). Prefix snapshots and the on-disk KV cache work unchanged (keyed by model file + format + prefix text).
- Next: fine-tuning a small model on cleanup pairs (targets: no replying/answering, no summarizing/dropping, self-corrections, ignore dictated instructions); consider wrapping the transcript in `<transcript>` tags (helped Qwen3.5 0.8B in the eval); fix RulesCleaner leaving ", " after a sentence end when it removes a filler.

## Update Oct 30, 2026: speculative decoding for cleanup
- **llama.cpp (GGUF: Qwen3.5 0.8B/2B, LFM2.5, MiniCPM5, Gemma 4 GGUF): prompt-lookup drafts.** Cleanup output mostly copies the transcript, so after each token the last 3–4 output tokens are looked up in the user turn (then the output so far, then the cached prefix) and the next 3 tokens that followed become a draft. Pending token + draft are decoded in one batch (logits at every position) and accepted while they equal the greedy pick; rejected positions are trimmed with `llama_memory_seq_rm`. Hybrid models (Qwen3.5, LFM2) need the context built with `n_rs_seq` = 3 so llama.cpp keeps per-token recurrent snapshots to roll back to (+58 MB recurrent state for Qwen3.5 2B). Shared code `llm/src/main/cpp/ngram_spec.h` (JNI + eval twin); llama.cpp's `common/` lookup code isn't in the JNI build.
- **Settings:** draft 3, minimum match 3 tokens, short drafts padded to a 4-token batch. ggml's repacked CPU matmuls (Q4_K/Q6_K 8x4 dotprod on phones) work in groups of 4 rows, so 1 + 3 drafts is the cheap batch (4 tokens ≈ 1.6× one token; 2 or 3 tokens cost more). Longer drafts get rejected more and cost more per pass.
- **Desktop eval (2 vCPU, paired A/B, 45 items):** Qwen3.5 0.8B decode 34.0 → 43.6 tok/s, mean 844 → 709 ms per dictation, p90 1195 → 1162 ms, 73% of drafts accepted; Qwen3.5 2B 16.9 → 25.6 tok/s, mean 1648 → 1270 ms, p90 2553 → 1768 ms, 78% accepted. Not byte-identical on every item: a token verified in a batch gets slightly different logits than decoded alone (ggml kernel numerics, also on pure-attention models), so a near-tie can flip (0/45 items changed on the 0.8B, 2/45 on the 2B); quality metrics unchanged. Details: `tools/eval/RESULTS.md`.
- **App:** on by default, Settings → Cleanup → "Faster cleanup" (`fast_decoding` pref, read per call). Any llama.cpp error mid-generation restores the prefix and finishes with plain greedy; drafts then stay off for that model until it reloads. Prefix KV disk cache unchanged except the key (`v2`), since contexts now carry snapshots. Logcat `hearsay-llm`: `llm loaded: … spec_draft=3`, per dictation `spec: N tokens in S steps, accepted A/D drafts (P%)`.
- **LiteRT-LM (Gemma 4 E2B .litertlm): multi-token prediction.** The pinned file carries the MTP drafter (`tf_lite_mtp_drafter` section; `ModelInfo.Llm.hasSpeculativeDecodingSupport()` = true). Enabled via `EngineConfig(speculativeDecodingConfig = SpeculativeDecodingConfig(SpeculativeDecodingMode.ON))` (LiteRT-LM 0.19.0; `ExperimentalFlags.enableSpeculativeDecoding` is deprecated). GPU: on, except PowerVR GPUs (Pixel 10 / Tensor G5–G6, where MTP cuts decode ~65%, LiteRT-LM issue #2227), detected from the GL renderer string (1×1 EGL pbuffer) or `Build.SOC_MODEL` Tensor G5+. CPU: on by default (Google recommends it for rewrite tasks on E2B), Settings → Cleanup → "Best (Gemma): multi-token prediction on the processor" (`mtp_cpu` pref; reloads the model). A start that fails with MTP is retried without it. Desktop CPU (LiteRT-LM Python 0.19.0, 15 items): decode 22.8 → 30.5 tok/s, outputs identical. Logcat `hearsay-litert`: `MTP drafter in model: true`, `gpu: renderer=… powerVR=…`, `loaded on GPU, MTP on|off (reason)`, `complete: N chars in M ms (…, MTP on)`.

## Update Oct 30, 2026: on-phone "Last cleanup" readout
- **Why:** testers can't run adb, so what the cleanup engine did is shown in Settings → Cleanup instead of only in logcat.
- **Line:** `Last cleanup: <model> · <engine> · <speed-up> · <ms> ms · <N> tokens/s`, plus `Graphics chip: <GL renderer>` once detected. Engine is "llama.cpp processor", "LiteRT-LM graphics chip" or "LiteRT-LM processor". Speed-up for llama.cpp: "Faster cleanup on, P% guesses accepted" (accepted/drafted for that dictation), "Faster cleanup off", "Faster cleanup fell back to normal" (error this call or earlier), "Faster cleanup not available for this model"; for Gemma on LiteRT-LM: "MTP on", "MTP off (PowerVR GPU)", "MTP off (turned off for processor)", "MTP off (no drafter in model)", "MTP off (didn't start)". Example: `Last cleanup: Qwen3.5 2B · llama.cpp processor · Faster cleanup on, 78% guesses accepted · 640 ms · 21 tokens/s`.
- **Source:** the engines, not logs. `TextEngine.lastRun: RunStats` (llm module). llama.cpp: JNI `nativeLastRun` returns mode, generated tokens, drafted, accepted and generation ms recorded in `nativeComplete`; tokens/s = tokens over generation time, ms = whole call. LiteRT-LM: MTP state fixed at load; tokens/s from `Conversation.getBenchmarkInfo()` when the runtime reports it, else estimated from output length (~4 chars/token over the whole call) and shown with "~". GPU name from `Speculation.detectedRenderer` (set when the PowerVR check runs, i.e. when Gemma tries the GPU).
- **Storage:** prefs `last_cleanup` and `gpu_renderer`, written after each on-device cleanup that ran the model (keyboard and floating mic); short/verbatim dictations keep the previous line. Cloud/ChatGPT cleanup doesn't change it. Text is `CleanupStatus.line`.
- **Switch copy:** "Faster cleanup": "Same model, guesses words from what you said to finish sooner. Qwen/Fast models only." The MTP switch is labeled for the Best (Gemma) model. The keyboard's "Done · X s" status is unchanged.
