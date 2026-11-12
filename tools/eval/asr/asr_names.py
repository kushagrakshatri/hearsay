#!/usr/bin/env python3
"""Names/jargon eval: does the speech model get the user's dictionary words right? Runs the clips from make_names.py
(synthetic speech, 5 voices) with a dictionary of ALL names.tsv entries (so most entries are distractors in any one clip):
  greedy        what the app does today (Parakeet greedy, dictionary only applied after, like Dictionary.apply)
  greedy+dict   greedy then the app's Dictionary.apply twin
  beam          modified beam search, no hotwords (cost of the decoder alone)
  hot@S         beam search + dictionary as hotwords, boost S per token, then Dictionary.apply
Reports WER, name error rate (a dictionary word in the reference not written as spelled), and false names
(a dictionary word in the hypothesis that isn't in the reference).
  asr_names.py --set DIR --parakeet DIR [--scores 1.5,2,2.5,3] [--jobs 5]"""
import argparse, json, os, time
import multiprocessing as mp
import numpy as np, soundfile as sf
from asrlib import *
from asrlib import _norm_alnum

ap = argparse.ArgumentParser()
ap.add_argument("--set", required=True); ap.add_argument("--parakeet", required=True)
ap.add_argument("--scores", default="1.5,2,2.5,3"); ap.add_argument("--jobs", type=int, default=5)
ap.add_argument("--out", default=None)
A = None; REC = None; TERMS = None


def init(args, cfg):
    """One recognizer per worker (they are ~1-2 GB each), built for decoder config [cfg]."""
    global REC, TERMS, A
    A = args
    TERMS = [l.strip() for l in open(os.path.join(HERE, "names.tsv")) if l.strip()]
    if cfg == "greedy": REC = recognizer(A.parakeet, threads=1)
    elif cfg == "beam": REC = recognizer(A.parakeet, threads=1, beam=True)
    else:
        vf = bpe_vocab_file(A.parakeet, f"/tmp/bpe_{os.getpid()}.vocab")
        hf = f"/tmp/hw_{os.getpid()}.txt"; open(hf, "w").write("\n".join(TERMS) + "\n")
        REC = recognizer(A.parakeet, threads=1, hotwords_file=hf, hotwords_score=float(cfg.split("@")[1]), bpe_vocab=vf)


def work(row):
    a, _ = sf.read(os.path.join(A.set, row["id"] + ".wav"), dtype="float32")
    t0 = time.time(); txt = transcribe(REC, a)
    return txt, time.time() - t0, len(a) / SR


def name_stats(rows_out, terms):
    tl = {_norm_alnum(t): t for t in terms}
    tw = {t: norm(t) for t in terms}
    res = {}
    for name in rows_out[0][1]:
        e = w = nref = nwrong = fals = 0
        for row, out, _ in rows_out:
            ref, hyp = norm(row["text"]), norm(out[name][0]); err, wrong = edit_ops(ref, hyp)
            e += err; w += len(ref)
            nref += len(row['names'])
            # per-name: right when all its words aligned correctly
            for n in row["names"]:
                nw = tw[n]; ok = False
                for i in range(len(ref) - len(nw) + 1):
                    if ref[i:i + len(nw)] == nw and not any(wrong[i:i + len(nw)]): ok = True
                nwrong += 0 if ok else 1
            # names written in the hypothesis that nobody said
            def has(seq, sub): return any(seq[i:i + len(sub)] == sub for i in range(len(seq) - len(sub) + 1))
            for t in terms:
                if has(hyp, tw[t]) and not has(ref, tw[t]): fals += 1
        res[name] = dict(wer=e / w, name_err=nwrong / nref, name_wrong=nwrong, names=nref, false_names=fals)
    return res


def main():
    global A
    A = ap.parse_args()
    rows = json.load(open(os.path.join(A.set, "manifest.json")))
    terms = [l.strip() for l in open(os.path.join(HERE, "names.tsv")) if l.strip()]
    outs = [(r, {}, 0.0) for r in rows]; secs = {}; audio = 0
    for cfg in ["greedy", "beam"] + ["hot@" + s for s in A.scores.split(",")]:
        with mp.get_context("spawn").Pool(A.jobs, initializer=init, initargs=(A, cfg)) as p:
            res = p.map(work, rows, chunksize=1)
        secs[cfg] = sum(r[1] for r in res); audio = sum(r[2] for r in res)
        for (row, o, _), (txt, dt, _) in zip(outs, res):
            o[cfg] = (txt, dt)
            if cfg != "beam": o[cfg + "+dict"] = (dictionary_apply(txt, terms), 0)
    st = name_stats(outs, terms)
    print(f"{len(rows)} clips, {audio:.0f} s audio, {sum(len(r['names']) for r in rows)} spoken names")
    for k, v in st.items():
        print(f"{k:16s} WER {v['wer']*100:5.2f}%  name errors {v['name_wrong']:3d}/{v['names']} ({v['name_err']*100:4.1f}%)  false names {v['false_names']:2d}" + (f"  1-thread decode {secs[k]:.0f}s" if k in secs else ""))
    if A.out: json.dump(dict(stats=st, secs=secs, hyps={k: [o[k][0] for _, o, _ in outs] for k in st}, refs=[r["text"] for r in rows]), open(A.out, "w"), indent=1)


if __name__ == "__main__":
    main()
