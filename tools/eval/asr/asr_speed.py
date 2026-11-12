#!/usr/bin/env python3
"""Decode speed on this machine, one process, nothing else running: greedy (the app before) vs beam search vs beam search
with the 60-word dictionary as per-dictation hotwords (the app now). Real-time factor = decode seconds / audio seconds.
Relative only: a phone is slower, and sherpa-onnx runs the same code there.
  asr_speed.py --libri DIR/test-clean --parakeet DIR [--threads 4] [--count 12]"""
import argparse, os, time
import numpy as np
from asrlib import *
ap = argparse.ArgumentParser(); ap.add_argument("--libri", required=True); ap.add_argument("--parakeet", required=True)
ap.add_argument("--threads", type=int, default=4); ap.add_argument("--count", type=int, default=12); ap.add_argument("--reps", type=int, default=2)
A = ap.parse_args()
D = dictations(A.libri, A.count, 2, 99); audio = sum(len(a) for _, a, _ in D) / SR
terms = [l.strip() for l in open(os.path.join(HERE, "names.tsv")) if l.strip()]
hw = "/".join(terms); bv = bpe_vocab_file(A.parakeet, "/tmp/bpe_speed.vocab")
def build(beam):
    if not beam: return recognizer(A.parakeet, threads=A.threads)
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=A.parakeet + "/encoder.int8.onnx", decoder=A.parakeet + "/decoder.int8.onnx", joiner=A.parakeet + "/joiner.int8.onnx",
        tokens=A.parakeet + "/tokens.txt", model_type="nemo_transducer", num_threads=A.threads, decoding_method="modified_beam_search",
        hotwords_score=1.25, modeling_unit="bpe", bpe_vocab=bv)
for name, beam, hot in [("greedy", False, False), ("beam", True, False), ("beam+hotwords", True, True)]:
    rec = build(beam); transcribe(rec, D[0][1][:SR * 3])  # warm up
    best = 1e9
    for _ in range(A.reps):
        t0 = time.time()
        for _, a, _ in D:
            s = rec.create_stream(hotwords=hw) if hot else rec.create_stream(); s.accept_waveform(SR, a); rec.decode_stream(s)
        best = min(best, time.time() - t0)
    print(f"{name:14s} {best:6.1f} s for {audio:.0f} s of audio  RTF {best / audio:.3f}  ({A.threads} threads)", flush=True)
    del rec
