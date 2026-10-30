#!/usr/bin/env python3
"""Gemma 4 E2B .litertlm on the desktop CPU: multi-token prediction (MTP) on vs off, same prompts as the app.

Uses Google's LiteRT-LM Python API (pip install litert-lm-api==0.19.0, same runtime version as the app's AAR).
Each item runs in a fresh conversation with the app's cleanup system prompt + 4 few-shots (LiteRtEngine passes
them as system instruction + initial messages; the chat template comes from the model file), greedy, thinking
off, max output words*2+32. MTP is an engine setting, so the modes run as whole passes: off, on, on, off
(the order cancels drift on a shared machine). Times are wall-clock per send_message (prefill of the
system prompt + few-shots + turn, then decode), so they include the prefill that the app avoids by cloning.

Usage: litert_mtp.py --model gemma-4-E2B-it.litertlm [--n 15] [--threads 2] [--out results/2026-10-30-spec-ab/gemma-mtp.json]
"""
import argparse, json, os, sys, time
import litert_lm
import run_eval as R


def make_engine(path, mtp, threads):
    mode = litert_lm.SpeculativeDecodingMode.ON if mtp else litert_lm.SpeculativeDecodingMode.OFF
    return litert_lm.Engine(path, backend=litert_lm.Backend.CPU(thread_count=threads), max_num_tokens=2048,
                            cache_dir="/tmp/litertlm-cache", enable_benchmark=True,
                            speculative_decoding_config=litert_lm.SpeculativeDecodingConfig(mode=mode))


def text_of(resp):
    content = resp.get("content", []) if hasattr(resp, "get") else []
    return "".join(c.get("text", "") for c in content if isinstance(c, dict) and c.get("type") == "text")


def run(path, mtp, items, threads):
    t0 = time.time()
    eng = make_engine(path, mtp, threads)
    eng.__enter__()
    load_s = time.time() - t0
    rows = []
    for it in items:
        inp = R.rules_clean(it["raw"])
        ex = R.examples_for(it["style"])
        msgs = []
        for u, a in zip(ex[0::2], ex[1::2]):
            msgs += [litert_lm.Message.user(u), litert_lm.Message.model(litert_lm.Contents.of(a))]
        conv = eng.create_conversation(
            system_message=R.system_for(it["style"]), messages=msgs,
            sampler_config=litert_lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0),
            thinking_config=litert_lm.ThinkingConfig(enable_thinking=False),
            max_output_tokens=len(inp.split()) * 2 + 32)
        with conv:
            t = time.time()
            resp = conv.send_message(inp)
            ms = (time.time() - t) * 1000
            b = conv.get_benchmark_info()
        text = text_of(resp)
        rows.append(dict(id=it["id"], out=text, ms=ms, prefill_tokens=b.last_prefill_token_count,
                         prefill_tps=b.last_prefill_tokens_per_second, decode_tokens=b.last_decode_token_count,
                         decode_tps=b.last_decode_tokens_per_second))
        print(f"  mtp={mtp} {it['id']}: {ms:.0f} ms, decode {b.last_decode_token_count} tok at {b.last_decode_tokens_per_second:.1f} tok/s {text[:60]!r}",
              file=sys.stderr, flush=True)
    eng.__exit__(None, None, None)
    return dict(mtp=mtp, load_s=load_s, rows=rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--n", type=int, default=15)
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--out", default="results/2026-10-30-spec-ab/gemma-mtp.json")
    a = ap.parse_args()
    items = [json.loads(l) for l in open(os.path.join(R.HERE, "cleanup_eval.jsonl")) if l.strip()]
    items = [i for i in items if len(R.rules_clean(i["raw"]).split()) >= 3][: a.n]
    runs = [run(a.model, False, items, a.threads), run(a.model, True, items, a.threads),
            run(a.model, True, items, a.threads), run(a.model, False, items, a.threads)]
    summ = {}
    for mode in (False, True):
        ms = [r["ms"] for x in runs if x["mtp"] == mode for r in x["rows"]]
        rs = [r for x in runs if x["mtp"] == mode for r in x["rows"]]
        dec_ms = sum(r["decode_tokens"] / r["decode_tps"] * 1000 for r in rs if r["decode_tps"] > 0)
        summ["mtp_on" if mode else "mtp_off"] = dict(
            mean_ms=round(sum(ms) / len(ms)), p90_ms=round(sorted(ms)[int(len(ms) * 0.9) - 1]),
            decode_tps=round(sum(r["decode_tokens"] for r in rs) / dec_ms * 1000, 1),
            mean_decode_ms=round(dec_ms / len(rs)))
    off, on = runs[0]["rows"], runs[1]["rows"]
    summ["identical_outputs"] = f"{sum(x['out'] == y['out'] for x, y in zip(off, on))}/{len(off)}"
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    json.dump(dict(model=os.path.basename(a.model), summary=summ, runs=runs), open(a.out, "w"), indent=1, ensure_ascii=False)
    print(json.dumps(summ))


if __name__ == "__main__":
    main()
