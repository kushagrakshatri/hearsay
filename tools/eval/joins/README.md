# Seam repair for live speech (cleanup/Joins.kt)

Live speech transcribes each pause-closed piece alone, and Parakeet TDT 0.6B v2 ends every piece like a
sentence. `joins.py` is the Python twin of `Joins.kt` (same output on every passage here, checked by
running both). `eval.py` counts boundary errors before and after the rule.

- `corpus.txt`: 23 readings, `|` marks a pause (mid-sentence or after a real end). Used while writing the rule.
- `held/corpus.txt`: 8 readings written after the rule, not tuned on.
- `tts.py`: edge-tts en-US-AndrewMultilingualNeural, with `...` at mid-sentence pauses so the audio has a real pause (run without proxy env).
- `asr.py`: cuts each reading in the middle of each pause (edge-tts word offsets), adds 0.3 s of silence, and transcribes each piece with sherpa-onnx Parakeet int8 (`PARAKEET_DIR`, default `models/parakeet`, from HF csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8). Writes `asr_out.json`.
- `python3 eval.py [held/asr_out.json] [-v]`. Reading 19 of the main set is skipped (its word offsets are off by one).

Nov 9, 2026 (boundaries: main 37 mid-sentence / 28 real ends; held-out 13 / 11):

| | wrong period | missed real period | wrong lowercase | stray capital | total |
|---|---|---|---|---|---|
| speech, before (main) | 16 | 2 | 0 | 17 | 35 |
| speech, after (main) | 11 | 2 | 0 | 1 | 14 |
| speech, before (held-out) | 7 | 0 | 0 | 5 | 12 |
| speech, after (held-out) | 7 | 0 | 0 | 2 | 9 |
| cleaned join, before (main) | 37 | 0 | 0 | 0 | 37 |
| cleaned join, after (main) | 12 | 0 | 0 | 0 | 12 |
| cleaned join, before (held-out) | 13 | 0 | 0 | 0 | 13 |
| cleaned join, after (held-out) | 8 | 0 | 0 | 1 | 9 |

"Cleaned join" stands the raw pieces in for cleaned stretches (no LLM) to test `Pieces.join`, which used to add a period after every unpunctuated stretch.
The speech "missed real period" rows are pieces Parakeet left unpunctuated; the rule never removes those.
