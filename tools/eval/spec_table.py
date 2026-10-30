#!/usr/bin/env python3
"""Speculative decoding summary: each run in a results dir vs its model's baseline (base-<model>/).
Usage: spec_table.py results/2026-10-30-spec   -> markdown table + list of items whose output differs."""
import glob, json, os, sys

root = sys.argv[1]
runs = {}
for p in sorted(glob.glob(os.path.join(root, "*", "*.json"))):
    tag = os.path.basename(os.path.dirname(p)); runs[tag] = json.load(open(p))

def stats(res):
    ran = [r for r in res["rows"] if not r["skipped"]]
    tot = sorted(r["prefill_ms"] + r["decode_ms"] for r in ran)
    g = sum(r["gen_tokens"] for r in ran); d = sum(r["decode_ms"] for r in ran)
    dr = sum(r.get("drafted", 0) for r in ran); ac = sum(r.get("accepted", 0) for r in ran)
    st = sum(r.get("spec_steps", 0) for r in ran)
    return dict(tps=g / d * 1000, mean=sum(tot) / len(tot), p90=tot[int(len(tot) * 0.9) - 1],
                dec_mean=d / len(ran), acc=(ac / dr if dr else None), tps_step=(g / st if st else None))

print("| model | run | decode tok/s | accept % | tokens/step | mean ms | p90 ms | mean decode ms | identical to baseline |")
print("|---|---|---|---|---|---|---|---|---|")
diffs = []
for tag, res in runs.items():
    model = res["model"]; base = runs.get(f"base-{model}")
    s = stats(res)
    if base is None: continue
    same = sum(a["raw_out"] == b["raw_out"] for a, b in zip(res["rows"], base["rows"]) if not a["skipped"])
    n = sum(not a["skipped"] for a in res["rows"])
    for a, b in zip(res["rows"], base["rows"]):
        if not a["skipped"] and a["raw_out"] != b["raw_out"]: diffs.append((tag, a["id"], b["raw_out"], a["raw_out"]))
    acc = "" if s["acc"] is None else f"{s['acc'] * 100:.0f}"
    tps_step = "" if s["tps_step"] is None else f"{s['tps_step']:.2f}"
    print(f"| {model} | {tag} | {s['tps']:.1f} | {acc} | {tps_step} | {s['mean']:.0f} | {s['p90']:.0f} | {s['dec_mean']:.0f} | {same}/{n} |")
print()
for tag, iid, b, a in diffs:
    print(f"- {tag} {iid}:\n    base: {b!r}\n    spec: {a!r}")
