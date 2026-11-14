# Speech (Parakeet) word-error-rate eval, Nov 11, 2026

Measures what the speech model writes, before cleanup. Run on a Linux box with `pip install sherpa-onnx numpy soundfile edge-tts`
and the Parakeet int8 model (HF `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8`, same files the app downloads).
`asrlib.py` has a line-for-line port of `audio/Recorder.kt` + `audio/SpeechGate.kt` (same Silero VAD settings, 100 ms reads,
2 s minimum piece, 250 ms padding), so "live" here is what the phone hands to Parakeet.

| script | what it does |
|---|---|
| `asr_eval.py` | whole recording vs VAD-cut vs live pieces (and bigger minimum pieces), on LibriSpeech dictations (`--set clean/other/pauses`, `--snr`, `--gain`, `--hw-score`, `--beam`) |
| `make_names.py`, `asr_names.py` | names set: 60 dictation sentences with names/jargon from `names.tsv`, spoken by 5 edge-tts voices (synthetic, incl. Indian English). Dictionary = all 60 terms, so most entries are distractors in any clip |
| `asr_rare.py` | real speech (LibriSpeech): each dictation's rare words (not in `common-en.txt`) plus 40 distractors as the dictionary |
| `asr_speed.py` | decode speed: greedy vs beam search vs beam + hotwords |

Audio: LibriSpeech test-clean / test-other (read, public-domain audiobooks, https://www.openslr.org/12). Dictations are 3 consecutive
utterances joined with 0.5-1.2 s of quiet. The `pauses` set also stretches natural gaps (150 ms+ of near-silence) to 0.5-1.0 s every 1.5-4 s,
standing in for thinking pauses. No recordings from a phone mic were available, so mic gain, room noise and the user's voice are NOT covered.

## Results (this box, Parakeet int8, 1 thread per job unless noted)

Names set, 60 clips, 74 spoken names, dictionary of 60 (`asr_names.py`):

| decoder | name errors | WER* | words from the dictionary written where nobody said them |
|---|---|---|---|
| greedy (before) | 32/74 (43.2%) | 8.77% | 0 |
| greedy + `Dictionary.apply` twin (before, what the app did after speech) | 27/74 (36.5%) | 7.76% | 0 |
| beam search, no hotwords | 32/74 (43.2%) | 8.60% | 0 |
| hotwords, boost 1.0 | 16/74 (21.6%) | 5.73% | 0 |
| hotwords, boost 1.25 (shipped) | 13/74 (17.6%) | 5.06% | 0 |
| hotwords, boost 1.5 | 11/74 (14.9%) | 4.89% | 0 |
| hotwords, boost 1.75 | 10/74 (13.5%) | 6.07% | 5 |
| hotwords, boost 2 | 10/74 (13.5%) | 6.75% | 6 |
| hotwords 1.25 + `Dictionary.apply` | 12/74 (16.2%) | 4.89% | 0 |

*WER counts "standup/stand-up", "10/ten", "we're/we are" as errors for every row alike.

Real speech, rare words (`asr_rare.py`, 80 dictations, 252 rare-word tokens, ~100 terms per dictionary incl. distractors):

| | WER | rare-word errors |
|---|---|---|
| greedy (before) | 3.04% | 66/252 (26.2%) |
| hotwords 1.25 (shipped) | 2.57% | 31/252 (12.3%) |
| hotwords 1.5 | 3.43% | 36/252 (14.3%) |
| hotwords 2 | 4.39% | 31/252 (12.3%) |

Hotwords on speech that has no dictionary words (60 unrelated names as the dictionary, boost 1.25, 60 LibriSpeech test-clean dictations, 3836 words):
whole-recording decode 77 -> 90 errors (+0.34 pp, 95% CI 0.13..0.60), live-piece decode 81 -> 72 errors (-0.23 pp, CI -0.51..-0.03).
The two disagree in direction, so treat the false-positive cost as roughly +-0.3 pp.

Speed (`asr_speed.py`, 189 s of audio, 4 threads, nothing else running): greedy RTF 0.051, beam 0.052, beam + 60 hotwords 0.057 (+12%).

Live hand-over vs one decode (`asr_eval.py`; bootstrap over dictations, none of these differences is clearly non-zero):

| set (words) | whole | VAD-cut whole | live (now) | min piece 5 s | min piece 8 s |
|---|---|---|---|---|---|
| test-clean (3836) | 2.01% | 2.03% | 2.11% | 2.03% | 1.96% |
| test-other (3487) | 3.30% | 3.47% | 3.67% | 3.53% | 3.33% |
| test-clean + thinking pauses | 2.11% | 2.06% | 2.27% | 2.27% | 2.11% |

Left-over audio decoded after the mic is released (mean / p90 seconds of speech): live now 6.3/10.9 (clean), 4.9/9.5 (other), 1.0/2.9 (pauses);
5 s pieces 7.6/13.8, 5.8/10.7, 2.8/5.4; 8 s pieces 8.3/15.0, 7.3/12.3, 3.7/7.5.
Cutting a recording *inside* words (an early version of the pause insertion cut at stop-consonant closures) took live to 5-6% WER with doubled and half
words at the seams; the real VAD needs 0.4 s of silence to close a piece so it does not do that, which is why the final set uses 150 ms+ gaps.

Beam search failure found by this eval: on test-other, one 44 s dictation decoded to an empty string with modified beam search (greedy: fine), and a
second clip came back empty at 30 s but not at 25, 35 or 40 s (max_active_paths 1, 2 and 8 each gave different results). Whole-recording decode, test-other:
greedy 3.30%, beam without hotwords 6.11% (that one empty result is 101 of 3487 words), beam + 60 unrelated hotwords 3.73%; live pieces 3.67% / 3.58% / 3.79%.
Empty results: 1 of 120 decodes without hotwords, 0 of 240 with hotwords. `ParakeetEngine` therefore decodes the two halves of a recording separately
(cut at the quietest 50 ms in the middle third) when a 5 s+ recording comes back empty; on that clip the halves gave WER 2.0% against the reference.
