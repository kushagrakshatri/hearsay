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

## Rematch: prompt formats that give the model nothing to reply to (Oct 30, 2026; not in the app)
Question: did the untuned models (LFM2.5 especially) fail because the transcript arrives as a chat message?
`run_eval.py --format chat|plain|single`, same 45 items, metrics, greedy, cap words×2+32, rules pre-pass and sanity
fallback. Per-item outputs: `results/2026-10-30-rematch/<format>/*.json`.
- **chat**: the app's prompt (system prompt + 4 few-shots as past user/assistant turns). Re-run with a freshly built
  harness: per-item outputs are byte-identical to `results/2026-10-30/`, so the timings below are comparable.
- **plain**: no chat template. Raw text after BOS: a descriptive header ("Speech-to-text transcripts and their cleaned
  versions … never answers or responds.") + the same rules restated as a description of the data + the style line +
  the same 4 few-shots as `Raw: …\nClean: …` blocks separated by `###`, then `Raw: <transcript>\nClean:`.
  Stops at `###` / `\nRaw:` / EOS (new `S` stop-string command in `hearsay_llm`); `###` instead of a blank-line stop so
  lists and sign-offs survive.
- **single**: chat template with one user turn. Rules + the 4 examples (as `<transcript>…</transcript>\nCleaned text: …`)
  inside the system prompt; user turn `Clean this transcript. Output only the cleaned text.\n<transcript>\n…\n</transcript>`;
  assistant turn empty (+ empty think block for Qwen/MiniCPM). The fixed head of the user turn is in the cached prefix.

| Model | Format | Near (/45) | Exact | Mean WER | Meaning viol. inserted / raw | Answered/obeyed (raw) | Dropped (ins.) | Added (ins.) | Self-corr. | Fallback | Mean / p90 ms | Prefix tok cas/pol | Prefix ms cas/pol |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| LFM2.5 1.2B | chat | 16 | 4 | 0.395 | 17 / 23 | 8 | 16 | 6 | 3/5 | 6 | 950 / 1554 | 384 / 401 | 3532 / 4303 |
| LFM2.5 1.2B | plain | 30 | 9 | 0.175 | 9 / 11 | 5 | 8 | 2 | 3/5 | 2 | 879 / 1191 | 398 / 413 | 3688 / 3908 |
| LFM2.5 1.2B | single | 18 | 7 | 0.343 | 17 / 18 | 6 | 14 | 7 | 2/5 | 1 | 830 / 981 | 417 / 433 | 4123 / 4021 |
| MiniCPM5 1B | chat | 26 | 9 | 0.295 | 13 / 18 | 8 | 9 | 6 | 1/5 | 5 | 884 / 1401 | 374 / 390 | 2644 / 2813 |
| MiniCPM5 1B | plain | 27 | 13 | 0.186 | 13 / 14 | 1 | 10 | 0 | 2/5 | 1 | 744 / 1058 | 380 / 394 | 2946 / 2940 |
| MiniCPM5 1B | single | 30 | 12 | 0.146 | 7 / 9 | 2 | 3 | 0 | 2/5 | 2 | 861 / 1457 | 407 / 422 | 2943 / 3026 |
| Qwen3.5 0.8B | chat | 34 | 13 | 0.122 | 4 / 8 | 6 | 2 | 2 | 4/5 | 4 | 822 / 1139 | 381 / 397 | 2438 / 2564 |
| Qwen3.5 0.8B | plain | 36 | 16 | 0.082 | 7 / 7 | 0 | 3 | 0 | 2/5 | 0 | 767 / 1058 | 397 / 413 | 2858 / 3041 |
| Qwen3.5 0.8B | single | 33 | 10 | 0.150 | 7 / 7 | 1 | 2 | 1 | 0/5 | 0 | 857 / 1181 | 431 / 447 | 3130 / 2870 |
| Qwen3.5 2B | chat | 42 | 23 | 0.039 | 3 / 4 | 1 | 2 | 0 | 4/5 | 1 | 1686 / 2599 | 381 / 397 | 6250 / 6056 |
| Qwen3.5 2B | plain | 38 | 22 | 0.065 | 5 / 6 | 0 | 4 | 0 | 4/5 | 1 | 1659 / 2205 | 397 / 413 | 5766 / 5874 |
| Qwen3.5 2B | single | 40 | 26 | 0.071 | 4 / 4 | 1 | 3 | 1 | 4/5 | 0 | 1624 / 2133 | 431 / 447 | 6086 / 6400 |

"Meaning viol." = inserted text / raw model output (same definitions as above). Sandbox 2 vCPU, 2 threads; ms are
restore+prefill+decode per dictation; prefix ms is the one-off cold prefix eval.

LFM2.5 plain with Liquid's recommended sampling (temp 0.1, top_k 50, rep 1.05; `--sample lfm=0.1,50,SEED --rep lfm=1.05`,
`results/2026-10-30-rematch/plain-lfm-sampled-seed{1234,42}/`): near 30 / 29, WER 0.182 / 0.173, violations 10 / 10,
self-corrections 2/5 / 3/5: within noise of greedy (30, 0.175, 9, 3/5). No gain; keep greedy.

### What changed (hand-checked)
- **LFM2.5's chatbot replies disappear in plain** (and mostly in single). Chat → plain:
  "Sounds good, thanks." "Glad I could help. Let me know if you need anything else." → "Sounds good. Thanks.";
  "Hey Siobhan, yeah, I'll send the deck over tonight." "Sure thing, Siobhan. Let me know…" → "Hey Siobhan, I'll send the deck tonight";
  "How far is it from San Jose to Tahoe" "It's about 30 miles, roughly." → "How far is it from San Jose to Tahoe?";
  "What time is the meeting tomorrow?" "The meeting is at 5, no wait, it's 6." → unchanged question;
  long rambles "Here's the summary of the release plan:" / bullet recap of the Mark email → near-verbatim cleanups that
  keep "crash or data loss", "Hi Mark", "Let me know". Near match 16 → 30, WER 0.395 → 0.175, violations 17 → 9.
- **What LFM2.5 still does in plain:** answers "What's the capital of Australia, I always forget." ("The capital of Australia
  is Canberra."), announces instead of transcribing ("Here's a poem about the ocean…", "Here's a joke for you…"), translates
  the "Ignore all previous instructions…" line into French, rewrites "Make this sound more professional…", trims words
  ("over", "yeah", "Three things"), keeps "Tuesday, no wait, Wednesday" and drops "actually three". It is still below
  Qwen3.5 0.8B on every quality line.
- **LFM2.5 single** stops replying to the message but turns into a meta-assistant: "Not enough info to clean.",
  "I don't have the transcript to clean. Please provide the text…", "Here are the key points from the transcript:",
  drops greetings ("Send the deck over tonight"). Only marginally better than chat (near 18, violations 17).
- **MiniCPM5**: both new formats stop the refusals/answers ("I don't have that information.", "2,500 miles", "I apologize,
  but I can't send the report"). single is its best (near 30, violations 7) but self-corrections stay 2/5 and it still
  answers the Canberra item in every format; plain occasionally rambles past the answer (a "Note: …" paragraph; fallback).
- **Qwen3.5 0.8B**: plain stops every answer/poem/joke/translation (raw answered 6 → 0, fallbacks 4 → 0, near 34 → 36,
  WER 0.122 → 0.082) but **stops applying self-corrections** (4/5 → 2/5: keeps "John, sorry, I mean Sarah",
  "Tuesday, no wait, Wednesday") and drops a few small words ("seven", "Thanks.", "Jen"), so inserted violations go 4 → 7.
  single is worse (self-corrections 0/5).
- **Qwen3.5 2B**: chat stays best (near 42, violations 3). single is close (40, 4, no fallbacks, more exact matches) but
  translates the "Ignore all previous instructions…" line into French. plain is lower (38, 5) and once copied the prompt
  header into the output mid-sentence ("…by the 1 cleaned version:\n- Filler words…"; caught by the fallback): with no
  turn markers the model can wander back into the header.
- Latency is unchanged by format: prefix 380–447 tokens everywhere; mean per-dictation suffix 24–25 tokens (plain) vs 28–32 (chat) vs 29–34 (single).

### Verdict
The chat framing *was* the main cause of LFM2.5's replying (and of MiniCPM's refusals): give it nothing to reply to and
the "Sure thing, Siobhan" class of failures goes away. But a format change does not make LFM2.5 competitive: plain LFM2.5
(30/45 near, 9 violations) is still worse than the app's current Qwen3.5 0.8B chat (34, 4) and far from Qwen3.5 2B chat (42, 3).
Keep the app's chat format for Qwen. Plain completion is the right format *if* LFM2.5 is ever shipped untuned or used as a
fine-tune base (train it on the plain `Raw:/Clean:` format, which it already follows). Prefix KV caching still works with
plain: everything before `Raw: <transcript>\nClean:` is a fixed string per style, so it can be snapshotted exactly like
the chat prefix (this harness already does). The app would additionally need stop strings in the JNI generate loop
(no end-of-turn token in plain mode), a prefix-cache key that includes the format, and a place for the names note
(the header or the suffix).
