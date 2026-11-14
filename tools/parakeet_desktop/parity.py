"""Parity of the desktop twin (parakeet_desktop JSON lines) against sherpa-onnx 2.0.1 Python on the same model files.
usage: python parity.py <model_dir> <native.jsonl> <wav_dir> [l11_dir]
With l11_dir: also compares the native layer-11 rows to onnxruntime-Python extraction (pkenc2 front end) by cosine."""
import sys, os, json, time, numpy as np, soundfile as sf, sherpa_onnx
M, NJ, WD = sys.argv[1], sys.argv[2], sys.argv[3]
L11 = sys.argv[4] if len(sys.argv) > 4 else None
TOK = [l.rsplit(" ", 1)[0].replace("\u2581", " ") for l in open(M + "/tokens.txt", encoding="utf-8").read().split("\n") if l]
def text(ids):
    s = "".join(TOK[k] for k in ids); return s[1:] if s.startswith(" ") else s
enc = os.environ.get("PK_ENCODER", M + "/encoder.int8.onnx")
rec = sherpa_onnx.OfflineRecognizer.from_transducer(encoder=enc, decoder=M + "/decoder.int8.onnx",
    joiner=M + "/joiner.int8.onnx", tokens=M + "/tokens.txt", model_type="nemo_transducer", num_threads=2)
rows = [json.loads(l) for l in open(NJ)]
nt = ntok = nfr = 0; sh_ms = 0; misses = []; sec = enc_ms = all_ms = 0
for r in rows:
    a = sf.read(os.path.join(WD, r["f"]), dtype="float32")[0]
    s = rec.create_stream(); s.accept_waveform(16000, a); t0 = time.time(); rec.decode_stream(s); sh_ms += (time.time() - t0) * 1000; ref = s.result
    ours = text(r["tokens"]).strip(); theirs = ref.text.strip()
    rtok = [TOK[k] for k in r["tokens"]]; stok = [t.replace("\u2581", " ") for t in ref.tokens]
    nt += ours == theirs; ntok += rtok == stok
    nfr += [round(f * 0.08, 2) for f in r["frames"]] == [round(x, 2) for x in ref.timestamps]
    if ours != theirs: misses.append((r["f"], theirs, ours))
    sec += r["sec"]; enc_ms += r["enc_ms"]; all_ms += r["fbank_ms"] + r["enc_ms"] + r["dec_ms"]
n = len(rows)
print(f"{n} clips, {sec:.0f} s audio")
print(f"text {nt}/{n}  tokens {ntok}/{n}  token frames {nfr}/{n}")
for f, a, b in misses: print(f"  {f} | sherpa: {a} | native: {b}")
print(f"native time: {all_ms / sec:.1f} ms per second of audio (encoder {enc_ms / sec:.1f}); sherpa Python: {sh_ms / sec:.1f} ms/s")
if L11:
    import onnxruntime as ort
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../eval/encoder_on_phone"))
    os.environ["PARAKEET_DIR"] = M
    import pkenc2 as P
    o = ort.SessionOptions(); o.intra_op_num_threads = 2; o.inter_op_num_threads = 1
    sess = ort.InferenceSession(enc, o, providers=["CPUExecutionProvider"])
    name = "/layers.11/norm_out/LayerNormalization_output_0"
    cos = []
    for r in rows:
        p = os.path.join(L11, r["f"] + ".l11.f32")
        if not os.path.exists(p): continue
        nat = np.fromfile(p, np.float32).reshape(-1, 1024)
        x = P.fbank(sf.read(os.path.join(WD, r["f"]), dtype="float32")[0])
        out, ln, l11 = sess.run(["outputs", "encoded_lengths", name], {"audio_signal": x.T[None], "length": np.array([x.shape[0]], np.int64)})
        py = l11[0, :int(ln[0])]
        c = (nat * py).sum(1) / (np.linalg.norm(nat, axis=1) * np.linalg.norm(py, axis=1) + 1e-12)
        cos.append((r["f"], nat.shape[0] == py.shape[0], float(c.mean()), float(c.min())))
    print(f"layer-11 rows vs onnxruntime {ort.__version__} Python: {len(cos)} clips, shapes equal {sum(c[1] for c in cos)}/{len(cos)}, "
          f"mean per-frame cosine {np.mean([c[2] for c in cos]):.5f}, worst clip mean {min(c[2] for c in cos):.5f}, worst frame {min(c[3] for c in cos):.5f}")
