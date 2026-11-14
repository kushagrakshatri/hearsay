#!/usr/bin/env python3
"""Rare-word eval on real speech (LibriSpeech): do hotwords fix names/odd words people actually say?
The "dictionary" for each dictation is its own rare words (ones not in the app's common-word list; that is an oracle:
the user is assumed to have added them) plus [--distractors] rare words from other dictations. LibriSpeech transcripts
are lowercase, so each term is given both as written and Capitalized (Parakeet capitalizes names).
Reports WER and rare-word error rate for greedy, and for beam + hotwords at each score.
  asr_rare.py --libri DIR --parakeet DIR --count 60 --scores 1.25,1.5,2"""
import argparse, json, os, random, time
import multiprocessing as mp
import numpy as np
from asrlib import *

ap = argparse.ArgumentParser()
ap.add_argument("--libri", required=True); ap.add_argument("--parakeet", required=True)
ap.add_argument("--sets", default="test-clean,test-other"); ap.add_argument("--count", type=int, default=40)
ap.add_argument("--scores", default="1.25,1.5,2"); ap.add_argument("--distractors", type=int, default=40)
ap.add_argument("--jobs", type=int, default=4); ap.add_argument("--out", default=None)
A = None; REC = None


def init(args, cfg):
    global A, REC
    A = args
    if cfg == "greedy": REC = recognizer(A.parakeet, threads=1)
    else:
        REC = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=A.parakeet + "/encoder.int8.onnx", decoder=A.parakeet + "/decoder.int8.onnx", joiner=A.parakeet + "/joiner.int8.onnx",
            tokens=A.parakeet + "/tokens.txt", model_type="nemo_transducer", num_threads=1, decoding_method="modified_beam_search",
            hotwords_score=float(cfg.split("@")[1]), modeling_unit="bpe", bpe_vocab=bpe_vocab_file(A.parakeet, f"/tmp/bpe_{os.getpid()}.vocab"))


def work(item):
    audio, hw = item
    s = REC.create_stream(hotwords=hw) if hw else REC.create_stream()
    t0 = time.time(); s.accept_waveform(SR, audio); REC.decode_stream(s)
    return s.result.text.strip(), time.time() - t0


def main():
    global A
    A = ap.parse_args()
    D = []
    for sub in A.sets.split(","):
        D += dictations(os.path.join(A.libri, sub), A.count, 3, 5)
    rare = [[w for w in dict.fromkeys(norm(t)) if w not in COMMON and len(w) >= 3 and w.isalpha()] for _, _, t in D]
    pool = sorted({w for r in rare for w in r}); rnd = random.Random(3)
    items = []
    for (id, a, t), r in zip(D, rare):
        own = set(r); dis = [w for w in rnd.sample(pool, min(len(pool), A.distractors + len(own))) if w not in own][:A.distractors]
        terms = r + dis; both = [x for w in terms for x in (w, w.capitalize())]
        items.append((a, "/".join(both)))
    refs = [t for _, _, t in D]
    print(len(D), "dictations,", sum(len(r) for r in rare), "rare words in them,", len(pool), "distinct")
    res = {}
    for cfg in ["greedy"] + ["hot@" + s for s in A.scores.split(",")]:
        with mp.get_context("spawn").Pool(A.jobs, initializer=init, initargs=(A, cfg)) as p:
            out = p.map(work, [(a, None if cfg == "greedy" else hw) for a, hw in items], chunksize=1)
        hyps = [o[0] for o in out]; rw = rwrong = 0
        for r, ref, h in zip(rare, refs, hyps):
            rr = norm(ref); err, wrong = edit_ops(rr, norm(h))
            for i, w in enumerate(rr):
                if w in r: rw += 1; rwrong += wrong[i]
        res[cfg] = dict(wer=wer(refs, hyps), rare_err=rwrong / rw, rare_wrong=rwrong, rare_words=rw, sec=sum(o[1] for o in out), hyps=hyps)
        print(f"{cfg:10s} WER {res[cfg]['wer']*100:5.2f}%  rare-word errors {rwrong}/{rw} ({rwrong/rw*100:4.1f}%)  1-thread decode {res[cfg]['sec']:.0f}s", flush=True)
    if A.out: json.dump(dict(refs=refs, results=res), open(A.out, "w"))


if __name__ == "__main__":
    main()
