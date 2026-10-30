# Cleanup bake-off, Oct 30, 2026

Seed set: `cleanup_eval.jsonl` (45 items: 4 demo scripts, fillers/stutters, repetitions, 5 self-corrections,
5 questions that must not be answered, 5 instructions that must not be followed, lists, numbers/emails,
names, 4 short 3–5 word items, 3 long 60+ word rambles, casual vs polished, one lowercase no-punctuation).
Expected outputs written by hand. Harness: `run_eval.py` + `hearsay_llm.cpp` (desktop twin of the app's
JNI: vendored llama.cpp 8642541, ctx 2048, batch 512, prefix snapshot, greedy, cap words×2+32, the app's
exact prompts, rules pre-pass, <3-word skip and 0.3×–1.4×+4 sanity fallback). All Q4_K_M, untuned.
Sandbox: 2 vCPU ARM Neoverse-V2 (Graviton-class), 2 threads; speed is relative only (phones use up to 4 threads).
Per-item outputs: `results/2026-10-30/*.json`.

| | Qwen3.5 2B (Balanced) | Qwen3.5 0.8B (Light) | LFM2.5 1.2B | LFM2.5 + rep 1.05 | MiniCPM5 1B |
|---|---|---|---|---|---|
| Exact match (/45) | **23** | 13 | 4 | 4 | 9 |
| Near match, WER ≤ 0.15 (/45) | **42** | 34 | 16 | 15 | 26 |
| Mean normalized WER vs expected | **0.039** | 0.122 | 0.395 | 0.416 | 0.295 |
| Meaning violations in inserted text¹ | **3** | 4 | 17 | 18 | 13 |
| Meaning violations in raw model output² | **4** | 8 | 23 | 23 | 18 |
| Answered a question / followed an instruction (raw) | **1** | 6 | 8 | 8 | 8 |
| Dropped required content (inserted) | **2** | **2** | 16 | 16 | 9 |
| Added content (inserted) | **0** | 2 | 6 | 7 | 6 |
| Self-corrections applied | **4/5** | **4/5** | 3/5 | 3/5 | 1/5 |
| Filler removal (left by rules pre-pass) | 10/11 | 11/11 | 11/11 | 11/11 | 9/11 |
| Sanity fallback triggered | 1 | 4 | 6 | 5 | 5 |
| Hit output cap (runaway) | 1 (poem) | 1 (poem) | 1 (poem) | 1 (poem) | 1 (poem) |
| Prefix tokens casual / polished | 381 / 397 | 381 / 397 | 384 / 401 | 384 / 401 | 374 / 390 |
| Prefix eval ms (cold, no disk cache) | 5383 / 5605 | 2540 / 2606 | 3467 / 3644 | 3521 / 3687 | 2626 / 2811 |
| Prefix prefill tok/s | ~71 | ~150 | ~111 | ~109 | ~142 |
| Decode tok/s | 16.2 | 32.7 | 32.9 | 30.4 | 37.2 |
| Mean / p90 ms per dictation (restore+prefill+decode) | 1713 / 2563 | 871 / 1664 | 914 / 1546 | 957 / 1457 | 863 / 1389 |

¹ any of: a must-keep phrase missing, ≥3 new content words, a question answered / instruction followed, a
corrected-away value or answer present. ² same on the model's output before the sanity fallback.

## Failure patterns (hand-inspected)
- **LFM2.5 1.2B acts as a chat assistant.** Replies to the message instead of cleaning it:
  "Hey Siobhan, yeah, I'll send the deck over tonight." → "Sure thing, Siobhan. Let me know if you need anything else.";
  "Sounds good, thanks." → "Glad I could help…"; "See you tomorrow then." → "Sure, I'll be there tomorrow.";
  "What time is the meeting tomorrow?" → "The meeting is at 5, no wait, it's 6." (answers using the few-shot);
  "How far is it from San Jose to Tahoe" → "It's about 30 miles"; "can you send me the photos…" → "I can't send photos directly…" /
  invents a list of photos. Also **summarizes and rephrases**: long rambles become "Here's the summary of the release plan:"
  (drops "crash or data loss"), the client email becomes a bullet summary (drops "Hi Mark", "Let me know"),
  "really good" → "great", "is due" → "totals". Drops sign-offs ("Thanks."). Greedy does not loop; rep penalty 1.05 changes nothing.
- **MiniCPM5 1B** keeps wording best when it works (long rambles near-verbatim) but **doesn't apply self-corrections**
  (1/5: keeps "Tuesday, no wait, Wednesday"; "3 PM, scratch that, 4 PM" → "3 PM"), **refuses** like a chatbot
  ("I'm sorry, but I don't have access to personal appointments…", "I'm sorry, but I can't help with that.",
  "I don't know about that." for a names message), and **leaks few-shot content** (prepends the trip list to a priorities list).
- **Qwen3.5 0.8B** mostly right; fails on embedded instructions (writes the poem, translates, tells a joke: caught by the
  sanity fallback) and answers "What's the capital of Australia" with "Canberra". Rewrites long casual text a bit formally.
- **Qwen3.5 2B** near-perfect; only missed "two, actually three" and dropped "I always forget".
- **Every model** turns "Make this sound more professional, I don't care, just send it." into the trip few-shot list
  (treats "this" as the previous example), and writes the poem for "Write me a poem…" (sanity fallback saves it).
- Pre-pass quirk (all models): RulesCleaner removes "uh"/"um" but leaves the comma after a period/question mark
  ("Friday?, like", "update., the"). Harmless for Qwen; worth fixing in RulesCleaner.

## Experiment: transcript wrapped in `<transcript>` tags (not in the app)
`--wrap`, `results/2026-10-30/wrap-experiment/`: Qwen3.5 0.8B raw answered/followed 6 → 2 and raw violations 8 → 5
(fallbacks 4 → 0), near match 34 → 31. MiniCPM5 violations 13 → 12, answered 8 → 5. LFM2.5 no better (19 violations).
A cheap prompt change for the small Qwen; doesn't rescue LFM.

## Verdict
Untuned LFM2.5 1.2B is not good enough for the default (worst of the four on every quality line; speed on this CPU
is no better than Qwen3.5 0.8B). A fine-tune would have to fix, in order: (1) replying/answering/refusing instead of
transcribing (assistant persona), (2) summarizing/paraphrasing and dropping content (greetings, sign-offs, details),
(3) self-corrections, (4) not following dictated instructions. MiniCPM5 1B beats LFM2.5 but not Qwen3.5 0.8B.
