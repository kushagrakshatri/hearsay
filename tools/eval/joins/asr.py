import json, re, subprocess, numpy as np, sherpa_onnx
M=__import__("os").environ.get("PARAKEET_DIR", "models/parakeet") + "/"
rec = sherpa_onnx.OfflineRecognizer.from_transducer(encoder=M+"encoder.int8.onnx", decoder=M+"decoder.int8.onnx",
    joiner=M+"joiner.int8.onnx", tokens=M+"tokens.txt", model_type="nemo_transducer", num_threads=2)
def tx(a):
    s=rec.create_stream(); s.accept_waveform(16000,a); rec.decode_stream(s); return s.result.text.strip()
def load(p):
    raw=subprocess.run(["ffmpeg","-v","quiet","-i",p,"-f","f32le","-ac","1","-ar","16000","-"],capture_output=True).stdout
    return np.frombuffer(raw,dtype=np.float32)
lines=[l for l in open("corpus.txt").read().splitlines() if l.strip()]
out=[]
for i,l in enumerate(lines):
    segs=[s.strip() for s in l.split("|")]
    words=json.load(open(f"r{i:02d}.json")); a=load(f"r{i:02d}.mp3")
    counts=[len(re.sub(r"32\.7 seconds","32.7seconds",s).split()) for s in segs]
    if sum(counts)!=len(words): print("MISMATCH",i,sum(counts),len(words),[w[2] for w in words])
    cuts=[0]; k=0; gaps=[]
    for c in counts[:-1]:
        k+=c; end=words[k-1][0]+words[k-1][1]; nxt=words[k][0]
        cuts.append(int((end+nxt)/2/1e7*16000)); gaps.append((nxt-end)/1e7)
    cuts.append(len(a))
    sil=np.zeros(int(0.3*16000),dtype=np.float32)
    chunks=[tx(np.concatenate([sil,a[cuts[j]:cuts[j+1]],sil])) for j in range(len(segs))]
    whole=tx(a)
    out.append(dict(truth=segs,chunks=chunks,whole=whole)); print(i,chunks,[round(g,2) for g in gaps],flush=True)
json.dump(out,open("asr_out.json","w"),indent=1)
