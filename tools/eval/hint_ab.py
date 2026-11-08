"""Live-draft speculative decoding A/B (Nov 2026): long dictations built from 5 eval items each, the
pieces cleaned one by one (as live cleanup while talking would), joined, and used as the draft
source ('H' command) for one full cleanup pass. Compares final-pass decode time against plain
greedy and the app's current prompt lookup (draft 3). Usage: hint_ab.py --bin B --model M [--out F]"""
import argparse, json, os, sys, statistics as S
import run_eval as R
ap = argparse.ArgumentParser(); ap.add_argument("--bin"); ap.add_argument("--model"); ap.add_argument("--out", default="results/2026-11-08-live-drafts.json")
ap.add_argument("--group", type=int, default=5); ap.add_argument("--threads", type=int, default=2); a = ap.parse_args()
items = [json.loads(l) for l in open(os.path.join(R.HERE, "cleanup_eval.jsonl"))]
groups = []
for st in ("polished", "casual"):
    its = [i for i in items if i["style"] == st]
    for k in range(0, len(its) - a.group + 1, a.group): groups.append((st, its[k:k + a.group]))
pre, turn = R.native_fns("ember")
eng = R.Engine(a.bin, a.model, a.threads, 1.0, None, "16,3")
pid = eng.prefix(pre("polished"))[0]
def x(d, g=3): eng.p.stdin.write(f"X {d} {g} 1 4\n".encode()); eng.p.stdin.flush(); eng.p.stdout.readline()
def hint(t): eng._send("H", t); eng.p.stdout.readline()
def run(text):
    cap = len(text.split()) * 2 + 32
    out, pt, pms, gt, dms = eng.complete(pid, cap, turn(text)); return R.clean_output(out), pms, gt, dms
CFG = {"plain": (0, ""), "lookup3": (3, ""), "draft8": (8, "h"), "draft16": (16, "h"), "lookup8_nohint": (8, "")}
rows = []
for gi, (st, its) in enumerate(groups):
    pieces = [R.rules_clean(i["raw"]) for i in its]
    full = " ".join(pieces)
    hint(""); x(3)
    run(full)  # warm-up
    live = [run(p) for p in pieces]
    draft = " ".join(o for o, *_ in live)
    row = dict(group=gi, style=st, words=len(full.split()), live_ms=sum(d + p for _, p, _, d in live), draft=draft)
    names = list(CFG); names = names[gi % len(names):] + names[:gi % len(names)]
    for n in names:
        d, h = CFG[n]; hint(draft if h else ""); x(d)
        out, pms, gt, dms = run(full); q = eng.spec_stats() if d else {}
        row[n] = dict(out=out, prefill=pms, gen=gt, decode=dms, **q)
    row["same_as_plain"] = {n: row[n]["out"] == row["plain"]["out"] for n in CFG}
    rows.append(row)
    print(gi, st, row["words"], {n: round(row[n]["decode"]) for n in CFG}, file=sys.stderr)
eng.close()
summ = {}
for n in CFG:
    dec = [r[n]["decode"] for r in rows]; pf = [r[n]["prefill"] for r in rows]
    s = dict(decode_mean=S.mean(dec), prefill_mean=S.mean(pf), same=sum(r["same_as_plain"][n] for r in rows))
    if n != "plain":
        dr = sum(r[n].get("drafted", 0) for r in rows); ac = sum(r[n].get("accepted", 0) for r in rows)
        s.update(accept=ac / dr if dr else 0, tok_per_step=sum(r[n]["gen"] for r in rows) / max(1, sum(r[n].get("spec_steps", 0) for r in rows)))
    summ[n] = s
summ["words_mean"] = S.mean(r["words"] for r in rows); summ["draft_equals_final"] = sum(r["draft"] == r["plain"]["out"] for r in rows)
summ["n"] = len(rows)
os.makedirs(os.path.dirname(a.out), exist_ok=True)
json.dump(dict(summary=summ, rows=rows), open(a.out, "w"), indent=1)
print(json.dumps(summ, indent=1))
