#!/usr/bin/env python3
"""Offline word-error-rate eval for the speech path (Nov 2026).

Compares, on the same dictations:
  raw_whole   the whole recording in one decode, no VAD (best case)
  vad_whole   the app's non-live path: VAD ranges cut + padded, one decode (Recorder.stop() -> all)
  live        the app's live path: pieces handed over at pauses while talking + the tail, each decoded alone
plus variants of the live hand-over (bigger minimum piece, padding).

  asr_eval.py --libri DIR/test-clean --parakeet DIR --set clean|other|pauses --count 60 --jobs 6 --out X.json
Audio sets: LibriSpeech (read public-domain audiobooks, https://www.openslr.org/12), dictations built from
consecutive utterances; 'pauses' adds quiet gaps at word boundaries (stand-in for thinking pauses).
Needs: pip install sherpa-onnx numpy soundfile; the Parakeet int8 model (HF csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8)."""
import argparse, json, os, random, sys, time
import multiprocessing as mp
import numpy as np
from asrlib import *

ap = argparse.ArgumentParser()
ap.add_argument("--libri", required=True); ap.add_argument("--parakeet", required=True)
ap.add_argument("--set", default="clean", choices=["clean", "other", "pauses"])
ap.add_argument("--count", type=int, default=60); ap.add_argument("--utts", type=int, default=3)
ap.add_argument("--jobs", type=int, default=6); ap.add_argument("--gain", type=float, default=1.0)
ap.add_argument("--configs", default="raw_whole,vad_whole,live"); ap.add_argument("--out", default=None)
ap.add_argument("--seed", type=int, default=7)
ap.add_argument("--hw-score", type=float, default=None, help="decode with every term of names.tsv as hotwords at this boost (false-positive check on speech that has none)")
ap.add_argument("--beam", action="store_true", help="modified beam search without hotwords")
ap.add_argument("--snr", type=float, default=None, help="add pink noise at this SNR in dB (synthetic stand-in for a noisy room)")
A = None; REC = None


def configs():
    """name -> function(audio) -> text. Everything is built from the app's real recorder logic."""
    def live(**kw):
        def f(a):
            pieces, tail, _, _ = record(a, **kw)
            f.tail = len(tail) / SR if len(tail) >= SR // 4 else 0.0
            return " ".join(transcribe(REC, p) for p in pieces + [tail] if len(p) >= SR // 4)
        return f
    return {
        "raw_whole": lambda a: transcribe(REC, a),
        "vad_whole": lambda a: transcribe(REC, record(a)[2]),
        "live": live(),
        "live_min5": live(min_chunk_ms=5000),
        "live_min8": live(min_chunk_ms=8000),
        "live_pad8": live(pad=8000),
    }


def init(args):
    global REC, A
    A = args
    if A.hw_score is not None:
        hf = f"/tmp/hw_{os.getpid()}.txt"; open(hf, "w").write(open(os.path.join(HERE, "names.tsv")).read())
        REC = recognizer(A.parakeet, threads=1, hotwords_file=hf, hotwords_score=A.hw_score, bpe_vocab=bpe_vocab_file(A.parakeet, f"/tmp/bpe_{os.getpid()}.vocab"))
    else:
        REC = recognizer(A.parakeet, threads=1, beam=A.beam)


def work(item):
    id, a, text = item
    a = (a * A.gain).astype(np.float32)
    if A.snr is not None: a = add_pink_noise(a, A.snr, abs(hash(id)) % (1 << 30))
    out = {}
    for name in A.configs.split(","):
        f = configs()[name]; t0 = time.time(); out[name] = (f(a), time.time() - t0, getattr(f, "tail", None))
    return id, text, len(a) / SR, out


def main():
    global A
    A = ap.parse_args()
    sub = "test-other" if A.set == "other" else "test-clean"
    libri = A.libri if A.libri.rstrip("/").endswith(sub) else os.path.join(os.path.dirname(A.libri.rstrip("/")), sub)
    D = dictations(libri, A.count, A.utts, A.seed)
    if A.set == "pauses":
        rnd = random.Random(A.seed)
        D = [(i, with_pauses(a, rnd), t) for i, a, t in D]
    with mp.get_context("spawn").Pool(A.jobs, initializer=init, initargs=(A,)) as p:
        rows = p.map(work, D, chunksize=1)
    refs = [r[1] for r in rows]; res = {}
    for name in A.configs.split(","):
        hyps = [r[3][name][0] for r in rows]
        tails = [r[3][name][2] for r in rows if r[3][name][2] is not None]
        res[name] = dict(tail_s_mean=float(np.mean(tails)) if tails else None, tail_s_p90=float(np.percentile(tails, 90)) if tails else None, wer=wer(refs, hyps), words=sum(len(norm(r)) for r in refs), seconds_1thread=sum(r[3][name][1] for r in rows), hyps=hyps)
        print(f"{A.set:7s} gain={A.gain:<5} snr={A.snr} hw={A.hw_score} beam={A.beam} {name:10s} WER {res[name]['wer'] * 100:5.2f}%  (1-thread decode {res[name]['seconds_1thread']:.0f} s for {sum(r[2] for r in rows):.0f} s audio)" + (f"  tail after release: mean {res[name]['tail_s_mean']:.1f} s, p90 {res[name]['tail_s_p90']:.1f} s of speech" if tails else ""), flush=True)
    if A.out:
        json.dump(dict(args=vars(A), refs=refs, results=res), open(A.out, "w"))


if __name__ == "__main__":
    main()
