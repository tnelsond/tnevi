#!/usr/bin/env python3
"""
Builds the English swipe vocabulary and n-gram language model:  app/src/main/assets/en_lm.bin

Inputs
  * wordfreq (pip install wordfreq)              unigram frequencies and candidate words
  * /usr/share/dict/american-english-large       (apt install wamerican-large) spelling dictionary,
                                                  used to drop acronyms, typos and junk tokens
  * NLTK corpora zips in CORPUS_DIR               brown reuters webtext nps_chat gutenberg movie_reviews abc
        https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/corpora/<name>.zip

Also writes test/lm_heldout.txt (held-out sentences) for the accuracy test.

Usage: tools/build_lm.py CORPUS_DIR
"""
import collections, math, re, struct, sys, zipfile, random
from pathlib import Path

import wordfreq

ROOT = Path(__file__).resolve().parent.parent
VOCAB_SIZE = 40000
DICT = Path("/usr/share/dict/american-english-large")
MIN_BIGRAM, MIN_TRIGRAM = 2, 2
Q = 16.0  # quantisation: stored value = round(-ln(p) * Q)

WORD_RE = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")
SENT_SPLIT = re.compile(r"[.!?;:]+[\"')\]]*\s+|\n\s*\n|\r?\n(?=[A-Z])")

# Common contractions the spelling dictionary may lack.
CONTRACTIONS = """don't can't won't isn't aren't wasn't weren't doesn't didn't haven't hasn't hadn't
couldn't shouldn't wouldn't mustn't i'm i've i'll i'd you're you've you'll you'd he's she's it's
we're we've we'll we'd they're they've they'll they'd that's there's here's what's who's let's
how's where's ain't y'all""".split()


# Words of one or two letters are mostly abbreviations/symbols in dictionaries; allow only these.
SHORT_WORDS = set("""a i am an as at be by do go he hi if in is it me my no of oh on or so to up us we
ah ha ok ox ma pa mr ms dr""".split())
SHORT_DISPLAY = {"ok": "OK", "mr": "Mr", "ms": "Ms", "dr": "Dr"}
# Abbreviations that dictionaries list as lowercase words.
ABBREVIATIONS = set("""inc ltd vol com est ave lbs mph gov govt cal sen col ref sci tel atm del gen aka hes def mon
jan feb mar apr jun jul aug sep sept oct nov dec thu tue thur thurs fri dept corp misc approx pres univ natl intl
mfg pct yrs hrs mins secs mgmt info pics specs stats docs prob tbh btw imo idk asap fyi jk omg lol lmao rofl wtf
""".split())


def build_vocab():
    lower_ok, proper = set(), {}
    for line in DICT.read_text(encoding="utf-8", errors="ignore").split("\n"):
        w = line.strip()
        if not w or not re.fullmatch(r"[A-Za-z]+(?:'[A-Za-z]+)?", w):
            continue
        if w.endswith("'s"):
            continue  # possessives: swipe the base word
        if w.islower():
            lower_ok.add(w)
        elif w[0].isupper() and w[1:].islower():
            proper.setdefault(w.lower(), w)  # proper noun, e.g. London
        # anything else (NASA, McDonald, iPhone) is skipped: acronyms / odd casing
    lower_ok.update(CONTRACTIONS)

    vocab, seen = [], set()
    for w in wordfreq.top_n_list("en", 250000):
        if len(vocab) >= VOCAB_SIZE:
            break
        if not re.fullmatch(r"[a-z]+(?:'[a-z]+)?", w) or w in seen or w in ABBREVIATIONS:
            continue
        letters = w.replace("'", "")
        if len(letters) <= 2 and w not in SHORT_WORDS:
            continue
        if w == "i" or w.startswith("i'"):
            disp = "I" + w[1:]
        elif w in SHORT_DISPLAY:
            disp = SHORT_DISPLAY[w]
        elif w in lower_ok:
            disp = w
        elif w in proper:
            disp = proper[w]
        else:
            continue
        seen.add(w)
        vocab.append(disp)
    return vocab


def corpus_texts(cdir):
    skip = {"gutenberg/bible-kjv.txt", "gutenberg/shakespeare-caesar.txt", "gutenberg/shakespeare-hamlet.txt",
            "gutenberg/shakespeare-macbeth.txt", "gutenberg/blake-poems.txt", "gutenberg/milton-paradise.txt"}
    for name in ["brown", "reuters", "webtext", "nps_chat", "gutenberg", "movie_reviews", "abc"]:
        z = zipfile.ZipFile(Path(cdir) / f"{name}.zip")
        for item in z.namelist():
            if item.endswith("/") or item in skip or item.endswith(("README", "cats.txt", "stopwords", "LICENSE")):
                continue
            raw = z.read(item).decode("latin-1")
            if name == "brown":
                raw = re.sub(r"(\S+)/\S+", r"\1", raw)
                raw = re.sub(r"\s+\n", "\n\n", raw)  # one sentence per line in brown
            elif name == "nps_chat":
                if not item.endswith(".xml"):
                    continue
                raw = "\n\n".join(re.findall(r"<Post[^>]*>(.*?)</Post>", raw, re.S))
            elif name == "webtext":
                raw = raw.replace("\n", "\n\n")
            yield raw


def main():
    cdir = sys.argv[1]
    vocab = build_vocab()
    ids = {w.lower(): i + 1 for i, w in enumerate(vocab)}  # 0 = <s>
    V = len(vocab) + 1
    print("vocab", len(vocab), vocab[:30])

    bi, tri = collections.Counter(), collections.Counter()
    ctx1, ctx2 = collections.Counter(), collections.Counter()
    heldout = []
    rnd = random.Random(1)
    tokens = 0
    for text in corpus_texts(cdir):
        for sent in SENT_SPLIT.split(text):
            words = [m.group(0).lower() for m in WORD_RE.finditer(sent)]
            if len(words) < 2:
                continue
            seq = [ids.get(w, -1) for w in words]
            if rnd.random() < 0.01:
                if all(x > 0 for x in seq) and 4 <= len(seq) <= 25:
                    heldout.append(" ".join(vocab[x - 1] for x in seq))
                continue
            tokens += len(seq)
            u, v = -1, 0  # sentence start: context (<none>, <s>)
            for w in seq:
                if w > 0 and v >= 0:
                    bi[(v, w)] += 1
                    if u >= 0:
                        tri[(u, v, w)] += 1
                if v >= 0:
                    ctx1[v] += 1
                    if u >= 0:
                        ctx2[(u, v)] += 1
                u, v = v, w
    print("tokens", tokens, "bigrams", len(bi), "trigrams", len(tri))

    def q(p):
        return max(0, min(65535, int(round(-math.log(p) * Q))))

    # unigram from wordfreq (much larger corpus than ours)
    uni = [q(1e-4)] + [q(max(wordfreq.word_frequency(w.lower(), "en"), 1e-9)) for w in vocab]

    by_prev = collections.defaultdict(list)
    for (v, w), c in bi.items():
        if c >= MIN_BIGRAM:
            by_prev[v].append((w, q(c / ctx1[v])))
    by_ctx = collections.defaultdict(list)
    for (u, v, w), c in tri.items():
        if c >= MIN_TRIGRAM:
            by_ctx[(u, v)].append((w, q(c / ctx2[(u, v)])))

    out = ROOT / "app/src/main/assets/en_lm.bin"
    with open(out, "wb") as f:
        f.write(struct.pack(">II", 0x564B4C4D, 1))
        f.write(struct.pack(">I", V))
        for w in vocab:
            b = w.encode("utf-8")
            f.write(struct.pack(">H", len(b)) + b)
        f.write(struct.pack(f">{V}H", *uni))
        # bigrams: CSR by previous word
        starts, nxt, sc = [0], [], []
        for v in range(V):
            for w, s in sorted(by_prev.get(v, [])):
                nxt.append(w); sc.append(s)
            starts.append(len(nxt))
        f.write(struct.pack(">I", len(nxt)))
        f.write(struct.pack(f">{V + 1}I", *starts))
        f.write(struct.pack(f">{len(nxt)}H", *nxt))
        f.write(struct.pack(f">{len(sc)}H", *sc))
        # trigrams: sorted context keys (u * 65536 + v)
        keys = sorted(by_ctx)
        tstarts, tn, ts = [0], [], []
        for k in keys:
            for w, s in sorted(by_ctx[k]):
                tn.append(w); ts.append(s)
            tstarts.append(len(tn))
        f.write(struct.pack(">I", len(keys)))
        f.write(struct.pack(f">{len(keys)}I", *[u * 65536 + v for u, v in keys]))
        f.write(struct.pack(f">{len(keys) + 1}I", *tstarts))
        f.write(struct.pack(f">{len(tn)}H", *tn))
        f.write(struct.pack(f">{len(ts)}H", *ts))
    print("wrote", out, out.stat().st_size, "bytes;", len(nxt), "bigrams,", len(tn), "trigrams")
    (ROOT / "test/lm_heldout.txt").write_text("\n".join(heldout) + "\n")
    print("heldout sentences", len(heldout))


if __name__ == "__main__":
    main()
