#!/usr/bin/env python3
"""Paired A/B timing of prompt-lookup speculative decoding, in one hearsay_llm process.

Each item is generated with plain greedy decoding and with every --cfg back to back (order rotated per
item), so machine-speed drift on a shared box cancels out. Same prompts, rules pre-pass, cap and
<3-word skip as run_eval.py (app chat format). Writes <out>/<model>.json with per-item outputs/timings.

Usage: spec_ab.py --bin ./hearsay_llm --model qwen2b=/path/Qwen3.5-2B-Q4_K_M.gguf --cfg 3,3 --cfg 4,3 --cfg 8,3 \
                  [--threads 2] [--only id1,id2] [--out results/2026-10-30-spec-ab]
--cfg is draft_max,ngram_min[,use_prefix]; the process is started with HEARSAY_SPEC=<largest draft>,2 so the
context keeps enough recurrent snapshots, and X switches settings between calls.
"""
import argparse, json, os, sys
import run_eval as R

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bin", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--cfg", action="append", required=True)
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--only", default="")
    ap.add_argument("--set", default=os.path.join(R.HERE, "cleanup_eval.jsonl"))
    ap.add_argument("--out", default="results/spec-ab")
    a = ap.parse_args()
    R.WRAP = False; R.PROMPT_FORMAT = "chat"
    name, path = a.model.split("=", 1)
    fmt = next(f for f in R.FORMATS if name.startswith(f))
    cfgs = [tuple(int(x) for x in c.split(",")) for c in a.cfg]
    cap = max(c[0] for c in cfgs)
    items = [json.loads(l) for l in open(a.set) if l.strip()]
    if a.only: items = [i for i in items if i["id"] in a.only.split(",")]
    prefix_fn, turn_fn = R.build_prompt_fns(fmt)
    eng = R.Engine(a.bin, path, a.threads, 1.0, None, f"{cap},2")
    def setx(c):
        d, g, up = (c + (1,))[:3] if c else (0, 2, 1)
        eng.p.stdin.write(f"X {d} {g} {up}\n".encode()); eng.p.stdin.flush(); eng.p.stdout.readline()
    pids = {st: eng.prefix(prefix_fn(st))[0] for st in ("casual", "polished")}
    variants = [None] + cfgs
    rows = []
    for k, it in enumerate(items):
        inp = R.rules_clean(it["raw"])
        words = len(inp.split())
        if words < 3: continue
        cap_t = words * 2 + 32
        row = dict(id=it["id"], runs={})
        order = variants[k % len(variants):] + variants[:k % len(variants)]
        for v in order:
            setx(v)
            out, pt, pms, gt, dms = eng.complete(pids[it["style"]], cap_t, turn_fn(inp))
            st = eng.spec_stats()
            key = "base" if v is None else ",".join(map(str, v))
            row["runs"][key] = dict(out=out, prompt_tokens=pt, prefill_ms=pms, gen_tokens=gt, decode_ms=dms, **st)
        rows.append(row)
        print(f"  [{name}] {it['id']}: " + " ".join(f"{k}={r['decode_ms']:.0f}" for k, r in row["runs"].items()), file=sys.stderr)
    eng.close()
    os.makedirs(a.out, exist_ok=True)
    keys = ["base"] + [",".join(map(str, c)) for c in cfgs]
    summ = {}
    for key in keys:
        rs = [r["runs"][key] for r in rows]
        tot = sorted(x["prefill_ms"] + x["decode_ms"] for x in rs)
        g = sum(x["gen_tokens"] for x in rs); d = sum(x["decode_ms"] for x in rs)
        dr = sum(x["drafted"] for x in rs); ac = sum(x["accepted"] for x in rs); steps = sum(x["spec_steps"] for x in rs)
        same = sum(r["runs"][key]["out"] == r["runs"]["base"]["out"] for r in rows)
        summ[key] = dict(decode_tps=round(g / d * 1000, 1), mean_ms=round(sum(tot) / len(tot)), p90_ms=round(tot[int(len(tot) * 0.9) - 1]),
                         mean_decode_ms=round(d / len(rs)), acceptance=round(ac / dr, 3) if dr else None,
                         tokens_per_step=round(g / steps, 2) if steps else None, identical=f"{same}/{len(rows)}",
                         spec_fallbacks=sum(x["spec_fallback"] for x in rs))
    json.dump(dict(model=name, file=os.path.basename(path), threads=a.threads, cfgs=a.cfg, summary=summ, rows=rows),
              open(os.path.join(a.out, f"{name}.json"), "w"), indent=1, ensure_ascii=False)
    print(json.dumps(dict(model=name, summary=summ)))

if __name__ == "__main__":
    main()
