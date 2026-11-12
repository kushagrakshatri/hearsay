"""Shared pieces for the speech eval: the Parakeet recognizer, the app's recorder/VAD hand-over
(a line-for-line port of audio/Recorder.kt + audio/SpeechGate.kt), WER, and the dictation set."""
import glob, os, random, re, subprocess
import numpy as np
import sherpa_onnx
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "../../.."))
VAD_MODEL = os.path.join(REPO, "app/src/main/assets/silero_vad.onnx")
COMMON = set(open(os.path.join(REPO, "app/src/main/assets/words/common-en.txt")).read().split())
SR = 16000


# ---------------------------------------------------------------- recognizer
def recognizer(model_dir, threads=4, hotwords_file="", hotwords_score=1.5, beam=False, max_active=4, blank_penalty=0.0, bpe_vocab=""):
    """Parakeet as the app loads it. beam / hotwords switch to modified beam search; hotwords need [bpe_vocab]
    (a "piece score" text file made from tokens.txt, see bpe_vocab_file) so words can be split into pieces."""
    M = model_dir.rstrip("/") + "/"
    kw = {}
    if beam or hotwords_file:
        kw = dict(decoding_method="modified_beam_search", max_active_paths=max_active)
    if hotwords_file:
        kw.update(hotwords_file=hotwords_file, hotwords_score=hotwords_score, modeling_unit="bpe", bpe_vocab=bpe_vocab)
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=M + "encoder.int8.onnx", decoder=M + "decoder.int8.onnx", joiner=M + "joiner.int8.onnx",
        tokens=M + "tokens.txt", model_type="nemo_transducer", num_threads=threads, blank_penalty=blank_penalty, **kw)


def transcribe(rec, audio):
    if len(audio) == 0:
        return ""
    s = rec.create_stream()
    s.accept_waveform(SR, audio.astype(np.float32))
    rec.decode_stream(s)
    return s.result.text.strip()


def bpe_vocab_file(model_dir, path):
    """sherpa-onnx splits hotwords into pieces with a "piece score" vocab. Parakeet's SentencePiece vocab is ranked,
    its score is minus the piece's row in tokens.txt (checked against the .nemo's tokenizer.vocab), so tokens.txt is enough."""
    toks = [l.rsplit(" ", 1)[0] for l in open(os.path.join(model_dir, "tokens.txt"), encoding="utf-8").read().splitlines() if l.strip()]
    open(path, "w", encoding="utf-8").write("".join(f"{t} {-i}\n" for i, t in enumerate(toks) if t != "<blk>"))
    return path


# ---------------------------------------------------------------- the app's audio path
def vad(threshold=0.4, min_silence=0.4, min_speech=0.15, max_speech=30.0):
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = VAD_MODEL
    cfg.silero_vad.threshold = threshold
    cfg.silero_vad.min_silence_duration = min_silence
    cfg.silero_vad.min_speech_duration = min_speech
    cfg.silero_vad.window_size = 512
    cfg.silero_vad.max_speech_duration = max_speech
    cfg.sample_rate = SR
    cfg.num_threads = 1
    return sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)


def cut(audio, ranges, pad=4000):
    """SpeechGate.cut: keep the speech ranges, each padded by [pad] samples, silence between dropped."""
    merged = []
    for a0, b0 in sorted(ranges):
        a = max(0, a0 - pad); b = min(len(audio), b0 + pad)
        if a >= b:
            continue
        if merged and a <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b))
        else:
            merged.append((a, b))
    return np.concatenate([audio[a:b] for a, b in merged]) if merged else np.zeros(0, np.float32)


class Gate:
    """SpeechGate.kt"""
    def __init__(self, **kw):
        self.v = vad(**kw); self.ranges = []; self.seen = 0; self.last_speech = -1

    @property
    def heard(self): return self.last_speech >= 0 or bool(self.ranges)

    @property
    def trailing(self): return 0 if self.last_speech < 0 else self.seen - self.last_speech

    def accept(self, c):
        self.v.accept_waveform(c); self.seen += len(c)
        if self.v.is_speech_detected(): self.last_speech = self.seen
        self._drain()

    def _drain(self):
        while not self.v.empty():
            seg = self.v.front; st = seg.start
            self.ranges.append((st, st + len(seg.samples))); self.v.pop()

    def finish(self):
        self.v.flush(); self._drain(); return list(self.ranges)


CHUNK_SILENCE_MS = 100   # Recorder.CHUNK_SILENCE_MS
MIN_CHUNK_MS = 2000      # Recorder.MIN_CHUNK_MS


def record(audio, chunk_silence_ms=None, min_chunk_ms=None, pad=4000, **vadkw):
    """Feeds [audio] through Recorder.afterRead in 100 ms reads. Returns (pieces, tail, all_speech).
    pieces are the live hand-overs (each cut() from the ranges since the previous one), tail is what's
    left on release, all_speech is what the non-live path would transcribe."""
    cs = CHUNK_SILENCE_MS if chunk_silence_ms is None else chunk_silence_ms
    mc = MIN_CHUNK_MS if min_chunk_ms is None else min_chunk_ms
    g = Gate(**vadkw); emitted = 0; pieces = []
    for i in range(0, len(audio), 1600):
        c = audio[i:i + 1600]
        g.accept(c)
        if not g.heard or g.trailing < cs * SR // 1000:
            continue
        n = len(g.ranges)
        if n <= emitted:
            continue
        rs = g.ranges[emitted:]
        if rs[-1][1] - rs[0][0] < mc * SR // 1000:
            continue
        emitted = n
        pieces.append(cut(audio[:i + len(c)], rs, pad))
    speech = g.finish()
    all_ = cut(audio, speech, pad) if speech else np.zeros(0, np.float32)
    tail = cut(audio, speech[emitted:], pad) if emitted > 0 and speech[emitted:] else (all_ if emitted == 0 else np.zeros(0, np.float32))
    return pieces, tail, all_, speech


def add_pink_noise(a, snr_db, seed):
    """Pink noise at [snr_db] relative to the speech RMS (silence between words included in the RMS)."""
    n = np.random.RandomState(seed).randn(len(a)); f = np.fft.rfft(n); k = np.arange(len(f)); k[0] = 1
    n = np.fft.irfft(f / np.sqrt(k), len(a))
    n *= np.sqrt(np.mean(a ** 2)) / np.sqrt(np.mean(n ** 2)) / (10 ** (snr_db / 20))
    return (a + n).astype(np.float32)


# ---------------------------------------------------------------- WER
def norm(t):
    t = t.lower().replace("-", " ").replace("\u2019", "'")
    t = re.sub(r"[^a-z0-9' ]", " ", t)
    return t.split()


def edit_ops(ref, hyp):
    """Levenshtein over words. Returns (errors, per-ref-word wrong flags)."""
    n, m = len(ref), len(hyp)
    d = np.zeros((n + 1, m + 1), dtype=np.int32)
    d[:, 0] = np.arange(n + 1); d[0, :] = np.arange(m + 1)
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i, j] = min(d[i - 1, j] + 1, d[i, j - 1] + 1, d[i - 1, j - 1] + (ref[i - 1] != hyp[j - 1]))
    wrong = [False] * n; i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and d[i, j] == d[i - 1, j - 1] + (ref[i - 1] != hyp[j - 1]):
            if ref[i - 1] != hyp[j - 1]: wrong[i - 1] = True
            i -= 1; j -= 1
        elif i > 0 and d[i, j] == d[i - 1, j] + 1:
            wrong[i - 1] = True; i -= 1
        else:
            j -= 1
    return int(d[n, m]), wrong


def wer(refs, hyps):
    e = w = 0
    for r, h in zip(refs, hyps):
        rr, hh = norm(r), norm(h); e += edit_ops(rr, hh)[0]; w += len(rr)
    return e / max(1, w)


# ---------------------------------------------------------------- dictation set
def dictations(libri_dir, count=60, utts=3, seed=7, gap=(0.5, 1.2)):
    """LibriSpeech test-clean (read public-domain audiobooks, CC BY 4.0 transcripts): [utts] consecutive utterances
    of one chapter joined with 0.5-1.2 s of quiet noise, like pauses in a dictation. Returns [(id, audio, text)]."""
    rnd = random.Random(seed)
    chapters = sorted(glob.glob(os.path.join(libri_dir, "*", "*")))
    rnd.shuffle(chapters)
    out = []
    for ch in chapters:
        if len(out) >= count: break
        tr = sorted(glob.glob(os.path.join(ch, "*.trans.txt")))[0]
        items = [l.strip().split(" ", 1) for l in open(tr)]
        if len(items) < utts: continue
        k = rnd.randrange(0, len(items) - utts + 1)
        parts = []; texts = []; ids = []
        for uid, text in items[k:k + utts]:
            a, sr = sf.read(os.path.join(ch, uid + ".flac"), dtype="float32"); assert sr == SR
            if parts:
                parts.append((np.random.RandomState(rnd.randrange(1 << 30)).randn(int(rnd.uniform(*gap) * SR)) * 1e-4).astype(np.float32))
            parts.append(a); texts.append(text.lower()); ids.append(uid)
        out.append((ids[0] + "+%d" % (utts - 1), np.concatenate(parts), " ".join(texts)))
    return out


def with_pauses(audio, rnd, every=(1.5, 4.0), pause=(0.5, 1.0)):
    """Inserts a quiet pause of 0.5-1.0 s into the recording roughly every 1.5-4 s of speech, the way people stop
    mid-sentence to think. Pauses go only into genuine gaps (150 ms+ of near-silence, longer than a stop-consonant closure), at the gap's middle,
    so no word is split; the gap is then stretched with low noise."""
    fr = 160
    rms = np.array([np.sqrt(np.mean(audio[i:i + fr] ** 2)) for i in range(0, len(audio) - fr, fr)])
    quiet = rms < 0.06 * np.percentile(rms, 95)
    gaps = []; i = 0
    while i < len(quiet):
        if quiet[i]:
            j = i
            while j < len(quiet) and quiet[j]: j += 1
            if j - i >= 15: gaps.append(((i + j) // 2) * fr)
            i = j
        else: i += 1
    out = []; last = 0; next_at = rnd.uniform(*every) * SR
    for g in gaps:
        if g < next_at or g < last + SR // 2: continue
        out.append(audio[last:g]); out.append((np.random.RandomState(rnd.randrange(1 << 30)).randn(int(rnd.uniform(*pause) * SR)) * 1e-4).astype(np.float32))
        last = g; next_at = g + rnd.uniform(*every) * SR
    out.append(audio[last:])
    return np.concatenate(out)


# ---------------------------------------------------------------- hotwords for the NeMo transducer
def vocab(tokens_txt):
    return {l.rsplit(" ", 1)[0] for l in open(tokens_txt, encoding="utf-8").read().splitlines() if l.strip()}


def pieces(word, V, longest_first=False):
    """Splits [word] (case kept) into vocab pieces, the first with the word-start marker. Fewest pieces
    (ties: longer pieces first), or greedy longest match. None when a letter isn't in the vocab."""
    s = "\u2581" + word
    n = len(s)
    if longest_first:
        out = []; i = 0
        while i < n:
            for j in range(n, i, -1):
                if s[i:j] in V: out.append(s[i:j]); i = j; break
            else: return None
        return out
    best = [None] * (n + 1); best[0] = (0, [])
    for i in range(n):
        if best[i] is None: continue
        for j in range(i + 1, min(n, i + 12) + 1):
            if s[i:j] in V:
                cand = (best[i][0] + 1, best[i][1] + [s[i:j]])
                if best[j] is None or cand[0] < best[j][0]: best[j] = cand
    return best[n][1] if best[n] else None


def hotword_lines(terms, V, score=None):
    """One line per term and tokenization: "\u2581K ush ag ra :score". Multi-word terms get a marker per word."""
    lines = []
    for t in terms:
        words = t.split()
        for lf in (False, True):
            ps = [pieces(w, V, lf) for w in words]
            if any(p is None for p in ps): continue
            line = " ".join(" ".join(p) for p in ps) + (f" :{score}" if score is not None else "")
            if line not in lines: lines.append(line)
    return lines


# ---------------------------------------------------------------- Dictionary.apply twin (cleanup/Dictionary.kt, terms only)
def _norm_alnum(s): return "".join(c for c in s.lower() if c.isalnum())


def _edit(a, b):
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] != b[j - 1]))
        prev = cur
    return prev[len(b)]


def dictionary_apply(text, terms):
    """What the app does after speech today: fix case, split words and one-letter-off spellings of dictionary terms."""
    WORD = re.compile(r"[^\W_']+(?:'[^\W_]+)*|\d+", re.U)
    for term in terms:
        target = _norm_alnum(term)
        if len(target) < 2: continue
        words = list(re.finditer(r"[\w']+", text))
        out = []; cursor = 0; i = 0; tw = len(term.split())
        while i < len(words):
            matched = 0
            for span in range(min(tw + 2, len(words) - i), 0, -1):
                piece = words[i:i + span]
                if not all(set(text[piece[k].end():piece[k + 1].start()]) <= {" ", "-"} for k in range(span - 1)): continue
                heard = _norm_alnum("".join(w.group() for w in piece))
                if heard == target or (span == 1 and tw == 1 and len(target) >= 6 and len(heard) >= 5 and heard[0] == target[0] and _edit(heard, target) <= 1):
                    matched = span; break
            if matched:
                out.append(text[cursor:words[i].start()]); out.append(term.strip()); cursor = words[i + matched - 1].end(); i += matched
            else:
                i += 1
        out.append(text[cursor:]); text = "".join(out)
    return text
