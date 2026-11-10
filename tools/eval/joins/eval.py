import json, re, sys
from joins import continues, lowercase_first, join
common = set(open(__import__("os").path.join(__import__("os").path.dirname(__file__), "../../../app/src/main/assets/words/common-en.txt")).read().split())
data = json.load(open(sys.argv[1] if len(sys.argv)>1 and sys.argv[1].endswith(".json") else "asr_out.json"))
SKIP = {19} if len(sys.argv)<2 or not sys.argv[1].endswith(".json") else set()  # TTS word alignment off by one (two-word "Google Drive" token): chunks don't match the text
def ends(s): return bool(re.search(r"[.?!]$", s.strip()))
def judge(mode):
    err = dict(wrong_period=0, missed_period=0, wrong_lower=0, stray_capital=0); n = dict(mid=0, end=0); bad = []
    for i, d in enumerate(data):
        if i in SKIP: continue
        T, C = d["truth"], d["chunks"]
        for j in range(len(C) - 1):
            t_end = ends(T[j]); t_cap = T[j + 1][0].isupper()
            n["end" if t_end else "mid"] += 1
            prev = " ".join(C[:j + 1]) if mode.startswith("rule") else C[j]
            nxt = C[j + 1]
            if mode == "speech_base": brk = ends(prev); cap = nxt[0].isupper()
            elif mode == "cleaned_base": brk = True; cap = nxt[0].isupper()
            else:
                c = continues(prev, nxt)
                brk = (not c) and (ends(prev) or mode == "rule_cleaned")
                cap = (lowercase_first(nxt, common) if c else nxt)[0].isupper()
            e = []
            if not t_end and brk: e.append("wrong_period")
            if t_end and not brk: e.append("missed_period")
            if t_cap and not cap and nxt[0].isupper(): e.append("wrong_lower")
            if not t_end and not t_cap and not brk and cap: e.append("stray_capital")
            for k in e: err[k] += 1
            if e: bad.append((i, j, e, C[j][-30:], "|", nxt[:30]))
    return err, n, bad
for m in ["speech_base", "rule_speech", "cleaned_base", "rule_cleaned"]:
    err, n, bad = judge(m)
    print(m, n, err, "total", sum(err.values()))
    if "-v" in sys.argv[1:]:
        for b in bad: print("   ", b)
print()
for i, d in enumerate(data):
    if i not in SKIP: print(i, join(d["chunks"], common))
