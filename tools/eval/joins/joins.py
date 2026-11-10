"""Python twin of app/src/main/java/dev/offlineflow/cleanup/Joins.kt: repairs the seam between two pause-closed pieces."""
import re

# A sentence (almost) never ends on these, so a period the ASR put after one is a pause, not an end.
NEVER_END = set("""the a an my your our their its and or but nor of with from than because whether
whose until very into onto upon via plus versus""".split())
# These can end a sentence ("log in", "I think so", "that's how it is"); they count only when the
# piece already ends without a period, i.e. the ASR itself heard the sentence go on.
WEAK_END = set("""to at in on for by about that so if when while before after since is are was were
be been will would could should can may might must has have had do does did not this these those
who what which where how his her him them us me you we they it as like just really also than
until""".split()) | NEVER_END
# "to" ends sentences after these ("I'd love to.", "I have to.").
TO_ENDS_AFTER = set("""want wants wanted love like liked have has had need needs needed going got try
tried used ought supposed able glad happy mean meant plan planned hope hoped""".split())
# A piece starting with these carries on the sentence before it ("... the line | for speech").
CONT_START = set("for of than to because which including nor".split())
# ... unless it is one of these openers, or a comma comes in its first few words ("For now, ...").
# Object pronouns don't open a sentence; they carry one on only when the piece before was left open.
OPEN_START = set("us them him whom".split())
# A preposition that ends a piece before a day, month or number ("on. Saturday", "at. Seven").
WHEN_PREP = set("on at by until till before after in from".split())
WHEN_WORDS = set("""monday tuesday wednesday thursday friday saturday sunday tomorrow tonight noon midnight
january february march april may june july august september october november december
one two three four five six seven eight nine ten eleven twelve""".split())
OPENERS = [r"for (example|instance|now|sure|one|real|the record|what|some reason|me|us|years|once)\b",
           r"to (be|me|us|my|our|start|begin|sum|summarize|recap|clarify)\b",
           r"of course\b", r"because of that\b"]
OPENER_RE = re.compile(r"^(?:" + "|".join(OPENERS) + ")", re.I)
SUBJECT = re.compile(r"(?i)\b(?:i|we|you|he|she|they|it|there)(?:'(?:m|re|ve|ll|d|s))?\b")
I_WORDS = re.compile(r"^I(?:'(?:m|d|ll|ve|s))?$")
WORD = re.compile(r"[A-Za-z][A-Za-z'\u2019]*")


def _last_words(s, n=2):
    return [w.lower().replace("\u2019", "'") for w in WORD.findall(s)[-n:]]


def _first_word(s):
    m = WORD.match(s)
    return m.group(0) if m else None


def continues(prev, nxt):
    """True when [nxt] carries on the sentence [prev] left open at a pause."""
    prev, nxt = prev.rstrip(), nxt.lstrip()
    if not prev or not nxt or prev[-1] in "?!:;\"')\u201d\u2026" or nxt.endswith("?") and re.match(r"(?i)which\b", nxt):
        return False
    first = _first_word(nxt)
    if first is None and not re.match(r"\d", nxt) or not nxt[0].isalnum():
        return False
    words = _last_words(prev)
    if not words or not re.search(r"[A-Za-z]$", prev.rstrip(".,")):
        return False
    last = words[-1]
    open_end = prev[-1] != "."  # the ASR left it unpunctuated (or ended on a comma)
    if last == "to" and len(words) > 1 and words[0] in TO_ENDS_AFTER:
        to_ok = False
    else:
        to_ok = True
    if last in NEVER_END or (open_end and last in WEAK_END and (last != "to" or to_ok)):
        return True
    if last == "to" and to_ok and not open_end:
        pass  # "to." could be a real end; fall through to the start-word test
    f = first.lower().replace("\u2019", "'") if first else ""
    if open_end and (nxt[0].islower() or f in OPEN_START):
        return True
    if last in WHEN_PREP and (f in WHEN_WORDS or re.match(r"\d", nxt)):
        return True
    if f in CONT_START and not OPENER_RE.match(nxt) and "," not in " ".join(nxt.split()[:3]):
        # "For lunch we had pizza": a fronted phrase with its own subject after it opens a new sentence.
        if f in ("for", "to") and SUBJECT.search(nxt.split(",")[0]):
            return False
        return True
    return False


def lowercase_first(nxt, common):
    """Lowercases the first word of [nxt] when it is an everyday word, never a name, I, or an acronym."""
    first = _first_word(nxt)
    if first is None or nxt.find(first) != 0:
        return nxt
    if I_WORDS.match(first) or first.isupper() and len(first) > 1 or any(c.isupper() for c in first[1:]):
        return nxt
    low = first.lower().replace("\u2019", "'")
    if low in common or low in WEAK_END or low in CONT_START:
        return first[0].lower() + nxt[1:]
    return nxt


def pair(prev, nxt, common):
    """[prev] and [nxt] joined at a pause: the sentence carries on when [continues] says so."""
    prev, nxt = prev.strip(), nxt.strip()
    if not prev: return nxt
    if not nxt: return prev
    if continues(prev, nxt):
        p = prev[:-1] if prev.endswith(".") and not prev.endswith("..") else prev
        return p + " " + lowercase_first(nxt, common)
    return prev + " " + nxt


def join(parts, common=frozenset(), period=False):
    """Pieces joined with [pair]; [period] adds one where a piece that doesn't carry on was left open."""
    parts = [p.strip() for p in parts if p.strip()]
    out = ""
    for i, p in enumerate(parts):
        if not out:
            out = p; continue
        if continues(out, p):
            out = pair(out, p, common)
        else:
            if period and not re.search(r"[.!?:;\u2026)\"'\u201d]$", out):
                out += "."
            out += " " + p
    return out
