#!/usr/bin/env python3
"""
Builds the vocabulary + n-gram language model for a language pack:  packs/<lang>/assets/lm.bin
and held-out test sentences:  test/lm_heldout_<lang>.txt

  tools/build_lm.py en NLTK_CORPUS_DIR
      * wordfreq (pip install wordfreq): unigram frequencies and candidate words
      * /usr/share/dict/american-english-large (apt install wamerican-large): drops acronyms/junk
      * NLTK corpora zips (brown reuters webtext nps_chat gutenberg movie_reviews abc) from
        https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/corpora/<name>.zip

  tools/build_lm.py km KHMER_DATA_DIR
      KHMER_DATA_DIR contains clones of
      * https://github.com/phylypo/khmer-text-data   (word-segmented Khmer Wikipedia + OSCAR)
      * https://github.com/silnrsi/khmerlbdict       (Khmer word lists, MIT)
      Words must be typeable on packs/km/assets/layout.txt.

Binary format (big endian): magic 'VKLM', version, V, V-1 words (u16 length + UTF-8),
unigram costs u16[V], bigrams (count, u32 starts[V+1], u16 next[], u16 cost[]),
trigrams (contexts, u32 keys[] = u*65536+v, u32 starts[], u16 next[], u16 cost[]).
Costs are round(-ln p * 16). Word id 0 is the sentence start <s>.
"""
import collections, glob, math, re, struct, sys, zipfile, random
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VOCAB_SIZE = 40000
Q = 16.0


def q(p):
    return max(0, min(65535, int(round(-math.log(p) * Q))))


# ---------------------------------------------------------------------------- shared

def count_ngrams(sentences, ids, rnd, heldout, display):
    """sentences: iterable of token lists. Returns bigram/trigram counters and context counts."""
    bi, tri = collections.Counter(), collections.Counter()
    ctx1, ctx2 = collections.Counter(), collections.Counter()
    tokens = 0
    for words in sentences:
        if len(words) < 2:
            continue
        seq = [ids.get(w, -1) for w in words]
        if rnd.random() < 0.01:
            if all(x > 0 for x in seq) and 4 <= len(seq) <= 25:
                heldout.append(display(seq))
            continue
        tokens += len(seq)
        u, v = -1, 0
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
    return bi, tri, ctx1, ctx2


def write_model(out, vocab, uni, bi, tri, ctx1, ctx2, min_bi, min_tri):
    V = len(vocab) + 1
    by_prev = collections.defaultdict(list)
    for (v, w), c in bi.items():
        if c >= min_bi:
            by_prev[v].append((w, q(c / ctx1[v])))
    by_ctx = collections.defaultdict(list)
    for (u, v, w), c in tri.items():
        if c >= min_tri:
            by_ctx[(u, v)].append((w, q(c / ctx2[(u, v)])))
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "wb") as f:
        f.write(struct.pack(">III", 0x564B4C4D, 1, V))
        for w in vocab:
            b = w.encode("utf-8")
            f.write(struct.pack(">H", len(b)) + b)
        f.write(struct.pack(f">{V}H", *uni))
        starts, nxt, sc = [0], [], []
        for v in range(V):
            for w, s in sorted(by_prev.get(v, [])):
                nxt.append(w); sc.append(s)
            starts.append(len(nxt))
        f.write(struct.pack(">I", len(nxt)))
        f.write(struct.pack(f">{V + 1}I", *starts))
        f.write(struct.pack(f">{len(nxt)}H", *nxt))
        f.write(struct.pack(f">{len(sc)}H", *sc))
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


# ---------------------------------------------------------------------------- English

DICT = Path("/usr/share/dict/american-english-large")
WORD_RE = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")
SENT_SPLIT = re.compile(r"[.!?;:]+[\"')\]]*\s+|\n\s*\n|\r?\n(?=[A-Z])")
CONTRACTIONS = """don't can't won't isn't aren't wasn't weren't doesn't didn't haven't hasn't hadn't
couldn't shouldn't wouldn't mustn't i'm i've i'll i'd you're you've you'll you'd he's she's it's
we're we've we'll we'd they're they've they'll they'd that's there's here's what's who's let's
how's where's ain't y'all""".split()
SHORT_WORDS = set("""a i am an as at be by do go he hi if in is it me my no of oh on or so to up us we
ah ha ok ox ma pa mr ms dr""".split())
SHORT_DISPLAY = {"ok": "OK", "mr": "Mr", "ms": "Ms", "dr": "Dr"}
ABBREVIATIONS = set("""inc ltd vol com est ave lbs mph gov govt cal sen col ref sci tel atm del gen aka hes def mon
jan feb mar apr jun jul aug sep sept oct nov dec thu tue thur thurs fri dept corp misc approx pres univ natl intl
mfg pct yrs hrs mins secs mgmt info pics specs stats docs prob tbh btw imo idk asap fyi jk omg lol lmao rofl wtf
""".split())


def english_vocab():
    import wordfreq
    lower_ok, proper = set(), {}
    for line in DICT.read_text(encoding="utf-8", errors="ignore").split("\n"):
        w = line.strip()
        if not w or not re.fullmatch(r"[A-Za-z]+(?:'[A-Za-z]+)?", w) or w.endswith("'s"):
            continue
        if w.islower():
            lower_ok.add(w)
        elif w[0].isupper() and w[1:].islower():
            proper.setdefault(w.lower(), w)
    lower_ok.update(CONTRACTIONS)
    vocab, seen = [], set()
    for w in wordfreq.top_n_list("en", 250000):
        if len(vocab) >= VOCAB_SIZE:
            break
        if not re.fullmatch(r"[a-z]+(?:'[a-z]+)?", w) or w in seen or w in ABBREVIATIONS:
            continue
        if len(w.replace("'", "")) <= 2 and w not in SHORT_WORDS:
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
    uni = [q(1e-4)] + [q(max(wordfreq.word_frequency(w.lower(), "en"), 1e-9)) for w in vocab]
    return vocab, uni


def english_sentences(cdir):
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
                raw = re.sub(r"\s+\n", "\n\n", raw)
            elif name == "nps_chat":
                if not item.endswith(".xml"):
                    continue
                raw = "\n\n".join(re.findall(r"<Post[^>]*>(.*?)</Post>", raw, re.S))
            elif name == "webtext":
                raw = raw.replace("\n", "\n\n")
            for sent in SENT_SPLIT.split(raw):
                yield [m.group(0).lower() for m in WORD_RE.finditer(sent)]


def build_english(cdir):
    vocab, uni = english_vocab()
    ids = {w.lower(): i + 1 for i, w in enumerate(vocab)}
    print("vocab", len(vocab), vocab[:20])
    heldout = []
    counts = count_ngrams(english_sentences(cdir), ids, random.Random(1), heldout,
                          lambda seq: " ".join(vocab[x - 1] for x in seq))
    write_model(ROOT / "packs/en/assets/lm.bin", vocab, uni, *counts, 2, 2)
    (ROOT / "test/lm_heldout_en.txt").write_text("\n".join(heldout) + "\n")
    print("heldout sentences", len(heldout))


# ---------------------------------------------------------------------------- Khmer

KHMER_TOKEN = re.compile(r"[ក-៓៝]+")
KHMER_SENT = re.compile(r"[។៕!?\n]+")
DOUBLED_MARK = re.compile(r"([ា-៓])\1")


def unescape(s):
    return re.sub(r"\\u([0-9A-Fa-f]{4})", lambda m: chr(int(m.group(1), 16)), s).replace("\\\\", "\\")


def layout_chars(path):
    chars, section = set(), None
    for line in Path(path).read_text(encoding="utf-8").split("\n"):
        if line.startswith("["):
            section = line
            continue
        if section == "[letters]" and line and not line.startswith("#"):
            for key in line.split("\t"):
                for out in key.split(" "):
                    chars.update(unescape(out))
    chars.discard("​")
    return chars


def khmer_files(kdir):
    wiki = zipfile.ZipFile(Path(kdir) / "khmer-text-data/km-wikipedia/seg_kmwiki_data.zip")
    for n in wiki.namelist():
        if n.endswith(".txt"):
            yield wiki.read(n).decode("utf-8", "ignore")
    for f in sorted(glob.glob(str(Path(kdir) / "khmer-text-data/oscar/seg_data/*"))):
        if f.endswith(".txt"):
            yield Path(f).read_text(encoding="utf-8", errors="ignore")
        elif f.endswith(".zip"):
            z = zipfile.ZipFile(f)
            for n in z.namelist():
                if not n.endswith("/"):
                    yield z.read(n).decode("utf-8", "ignore")


# Much of the OSCAR Khmer text is Buddhist scripture; drop those documents so predictions
# reflect everyday Khmer rather than the Tipitaka.
SCRIPTURE = re.compile("ព្រះភាគ|តថាគត|ភិក្ខុ|ព្រះសមណ|អាត្មាអញ|ក្រាបបង្គំទូល|អាសវៈ|ជ្ឈាន")


def khmer_sentences(kdir):
    for text in khmer_files(kdir):
        text = "\n".join(line for line in text.split("\n") if not SCRIPTURE.search(line))
        for sent in KHMER_SENT.split(text):
            # Tokens are space-separated (CRF segmentation). Anything non-Khmer breaks the context.
            out = []
            for tok in sent.split():
                if KHMER_TOKEN.fullmatch(tok):
                    out.append(tok)
                else:
                    if len(out) >= 2:
                        yield out
                    out = []
            if len(out) >= 2:
                yield out


def well_formed(w):
    if not ("ក" <= w[0] <= "ឳ"):        # must start with a consonant or independent vowel
        return False
    if w.endswith("្") or "្្" in w:  # coeng must join two consonants
        return False
    return not DOUBLED_MARK.search(w)


def build_khmer(kdir):
    typeable = layout_chars(ROOT / "packs/km/assets/layout.txt")
    dictionary = set()
    for f in glob.glob(str(Path(kdir) / "khmerlbdict/src/*.txt")):
        for line in Path(f).read_text(encoding="utf-8", errors="ignore").split("\n"):
            w = line.split("\t")[0].strip()
            if w:
                dictionary.add(w)
    print("dictionary words", len(dictionary))

    freq = collections.Counter()
    for sent in khmer_sentences(kdir):
        freq.update(sent)
    total = sum(freq.values())
    print("corpus tokens", total, "distinct", len(freq))
    vocab = []
    for w, c in freq.most_common():
        if len(vocab) >= VOCAB_SIZE:
            break
        if not set(w) <= typeable or not well_formed(w):
            continue
        if w not in dictionary and c < 40:
            continue
        vocab.append(w)
    print("vocab", len(vocab), vocab[:30])
    ids = {w: i + 1 for i, w in enumerate(vocab)}
    uni = [q(1e-4)] + [q(freq[w] / total) for w in vocab]
    heldout = []
    counts = count_ngrams(khmer_sentences(kdir), ids, random.Random(1), heldout,
                          lambda seq: "​".join(vocab[x - 1] for x in seq))
    write_model(ROOT / "packs/km/assets/lm.bin", vocab, uni, *counts, 4, 6)
    (ROOT / "test/lm_heldout_km.txt").write_text("\n".join(heldout[:3000]) + "\n")
    print("heldout sentences", len(heldout))


if __name__ == "__main__":
    {"en": build_english, "km": build_khmer}[sys.argv[1]](sys.argv[2])
