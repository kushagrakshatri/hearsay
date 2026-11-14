#!/usr/bin/env python3
"""Builds the names set: dictation-style sentences with names/jargon from names.tsv, spoken by edge-tts neural voices
(synthetic speech, five English voices incl. Indian English). Writes WAVs + manifest.jsonl to --out.
Run WITHOUT proxy env vars (edge-tts can't use them):  env -u http_proxy -u https_proxy python make_names.py --out DIR"""
import argparse, asyncio, json, os, random, subprocess
import edge_tts
VOICES = ["en-US-AndrewMultilingualNeural", "en-US-AvaMultilingualNeural", "en-IN-NeerjaNeural", "en-IN-PrabhatNeural", "en-GB-RyanNeural"]
HERE = os.path.dirname(os.path.abspath(__file__))

async def tts(text, voice, path):
    for k in range(6):
        try:
            c = edge_tts.Communicate(text, voice); a = bytearray()
            async for ch in c.stream():
                if ch["type"] == "audio": a += ch["data"]
            open(path + ".mp3", "wb").write(a)
            subprocess.run(["ffmpeg", "-v", "quiet", "-y", "-i", path + ".mp3", "-ac", "1", "-ar", "16000", path], check=True)
            os.remove(path + ".mp3"); return
        except Exception:
            await asyncio.sleep(2 + k)
    raise RuntimeError("tts failed " + text)

async def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--out", required=True); ap.add_argument("--per", type=int, default=2); a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    names = [l.strip() for l in open(os.path.join(HERE, "names.tsv")) if l.strip()]
    sents = [l.strip() for l in open(os.path.join(HERE, "sentences.tsv")) if l.strip()]
    rnd = random.Random(11); rows = []
    for s in sents:
        for k in range(a.per):
            used = rnd.sample(names, 2)
            text = s.format(*used)
            rows.append(dict(id=f"n{len(rows):03d}", text=text, names=[n for n, tag in zip(used, ["{0}", "{1}"]) if tag in s], voice=VOICES[len(rows) % len(VOICES)]))
    for r in rows:
        p = os.path.join(a.out, r["id"] + ".wav")
        if not os.path.exists(p): await tts(r["text"], r["voice"], p)
    json.dump(rows, open(os.path.join(a.out, "manifest.json"), "w"), indent=1)
    print(len(rows), "clips")
asyncio.run(main())
