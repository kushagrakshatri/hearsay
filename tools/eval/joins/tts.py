import asyncio, edge_tts, json, sys, re
async def one(i, text):
    segs=[x.strip() for x in text.split("|")]
    plain = " ".join((seg if j==len(segs)-1 or seg[-1] in ".?!" else seg+"...") for j,seg in enumerate(segs))
    c = edge_tts.Communicate(plain, "en-US-AndrewMultilingualNeural", boundary="WordBoundary")
    audio = bytearray(); words = []
    async for ch in c.stream():
        if ch["type"] == "audio": audio += ch["data"]
        elif ch["type"] == "WordBoundary": words.append((ch["offset"], ch["duration"], ch["text"]))
    open(f"r{i:02d}.mp3","wb").write(audio); json.dump(words, open(f"r{i:02d}.json","w"))
async def main():
    lines=[l for l in open("corpus.txt").read().splitlines() if l.strip()]
    await asyncio.gather(*[one(i,l) for i,l in enumerate(lines)])
asyncio.run(main())
