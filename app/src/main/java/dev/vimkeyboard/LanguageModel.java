package dev.vimkeyboard;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word n-gram model (unigram + bigram + trigram, "stupid backoff") built by tools/build_lm.py.
 * Pure Java so it can be tested on the JVM.
 *
 * Word ids: 0 is the sentence start {@code <s>}; ids 1..size()-1 are words; -1 is unknown.
 */
public final class LanguageModel {

    private static final int MAGIC = 0x564B4C4D;
    private static final float Q = 16f;
    /** -ln(0.4): penalty per backoff step. */
    private static final float BACKOFF = 0.916f;

    private final String[] words;          // index = id (words[0] = "<s>")
    private final HashMap<String, Integer> ids = new HashMap<>();
    private final char[] uni;
    private final int[] bStart;
    private final char[] bNext, bCost;
    private final long[] tKeys;
    private final int[] tStart;
    private final char[] tNext, tCost;
    private final int[] byFrequency;       // ids ordered by unigram probability

    private LanguageModel(DataInputStream d) throws IOException {
        if (d.readInt() != MAGIC) throw new IOException("not a language model");
        d.readInt(); // version
        int v = d.readInt();
        words = new String[v];
        words[0] = "<s>";
        for (int i = 1; i < v; i++) {
            byte[] b = new byte[d.readUnsignedShort()];
            d.readFully(b);
            words[i] = new String(b, "UTF-8");
            ids.put(words[i].toLowerCase(Locale.ROOT), i);
        }
        uni = readChars(d, v);
        int nb = d.readInt();
        bStart = readInts(d, v + 1);
        bNext = readChars(d, nb);
        bCost = readChars(d, nb);
        int nc = d.readInt();
        tKeys = new long[nc];
        for (int i = 0; i < nc; i++) tKeys[i] = d.readInt() & 0xffffffffL;
        tStart = readInts(d, nc + 1);
        int nt = tStart[nc];
        tNext = readChars(d, nt);
        tCost = readChars(d, nt);
        // word ids are written in frequency order already
        byFrequency = new int[v - 1];
        for (int i = 1; i < v; i++) byFrequency[i - 1] = i;
    }

    public static LanguageModel load(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(new BufferedInputStream(in, 1 << 16));
        try { return new LanguageModel(d); } finally { d.close(); }
    }

    private static char[] readChars(DataInputStream d, int n) throws IOException {
        byte[] b = new byte[n * 2];
        d.readFully(b);
        char[] c = new char[n];
        for (int i = 0; i < n; i++) c[i] = (char) (((b[2 * i] & 0xff) << 8) | (b[2 * i + 1] & 0xff));
        return c;
    }

    private static int[] readInts(DataInputStream d, int n) throws IOException {
        byte[] b = new byte[n * 4];
        d.readFully(b);
        int[] r = new int[n];
        for (int i = 0; i < n; i++)
            r[i] = ((b[4 * i] & 0xff) << 24) | ((b[4 * i + 1] & 0xff) << 16) | ((b[4 * i + 2] & 0xff) << 8) | (b[4 * i + 3] & 0xff);
        return r;
    }

    /** Number of ids including {@code <s>}. */
    public int size() { return words.length; }

    /** Display form of a word id (e.g. "London", "I'm", "the"). */
    public String word(int id) { return words[id]; }

    public int id(String w) {
        Integer i = ids.get(w.toLowerCase(Locale.ROOT));
        return i == null ? -1 : i;
    }

    private static int find(char[] arr, int from, int to, int w) {
        int lo = from, hi = to - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int x = arr[mid];
            if (x < w) lo = mid + 1; else if (x > w) hi = mid - 1; else return mid;
        }
        return -1;
    }

    private int context(int u, int v) {
        long key = (long) u * 65536 + v;
        int lo = 0, hi = tKeys.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (tKeys[mid] < key) lo = mid + 1; else if (tKeys[mid] > key) hi = mid - 1; else return mid;
        }
        return -1;
    }

    /** Cost (-ln probability, roughly) of word w after context (u, v). u/v may be -1 (unknown). */
    public float cost(int u, int v, int w) {
        float back = 0;
        if (u >= 0 && v >= 0) {
            int c = context(u, v);
            if (c >= 0) {
                int i = find(tNext, tStart[c], tStart[c + 1], w);
                if (i >= 0) return tCost[i] / Q;
                back += BACKOFF;
            }
        }
        if (v >= 0 && v < bStart.length - 1) {
            int i = find(bNext, bStart[v], bStart[v + 1], w);
            if (i >= 0) return back + bCost[i] / Q;
            back += BACKOFF;
        }
        return back + uni[w] / Q;
    }

    /** Most likely next words after (u, v), best first. */
    public List<Integer> predict(int u, int v, int k) {
        List<Integer> out = new ArrayList<>();
        // gather candidates from the trigram and bigram lists, then rank by full cost
        List<Integer> cand = new ArrayList<>();
        if (u >= 0 && v >= 0) {
            int c = context(u, v);
            if (c >= 0) for (int i = tStart[c]; i < tStart[c + 1]; i++) cand.add((int) tNext[i]);
        }
        if (v >= 0 && v < bStart.length - 1) for (int i = bStart[v]; i < bStart[v + 1]; i++) cand.add((int) bNext[i]);
        for (int i = 0; i < 40 && i < byFrequency.length; i++) cand.add(byFrequency[i]);
        float[] best = new float[k];
        int[] bestId = new int[k];
        int n = 0;
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (int w : cand) {
            if (!seen.add(w)) continue;
            float s = cost(u, v, w);
            if (n < k) { best[n] = s; bestId[n] = w; n++; }
            else {
                int worst = 0;
                for (int j = 1; j < k; j++) if (best[j] > best[worst]) worst = j;
                if (s < best[worst]) { best[worst] = s; bestId[worst] = w; }
            }
        }
        // sort selected
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++)
            if (best[j] < best[i]) {
                float t = best[i]; best[i] = best[j]; best[j] = t;
                int ti = bestId[i]; bestId[i] = bestId[j]; bestId[j] = ti;
            }
        for (int i = 0; i < n; i++) out.add(bestId[i]);
        return out;
    }

    private int maxWordLength = -1;

    /**
     * Context for scripts written without spaces (Khmer): splits the text before the cursor into
     * dictionary words from the right (longest match), stopping at sentence ends.
     * Spaces and zero-width spaces are word breaks; anything else unknown gives no context.
     */
    public int[] contextOfUnspaced(CharSequence before, String sentenceEnds) {
        if (maxWordLength < 0) {
            int m = 1;
            for (int i = 1; i < words.length; i++) m = Math.max(m, words[i].length());
            maxWordLength = Math.min(m, 24);
        }
        String s = before == null ? "" : before.toString();
        int pos = s.length();
        int[] got = new int[2];
        int found = 0;
        boolean stopped = false;
        while (pos > 0 && found < 2) {
            char c = s.charAt(pos - 1);
            if (sentenceEnds.indexOf(c) >= 0 || c == '\n') { got[found++] = 0; stopped = true; break; }
            if (Character.isWhitespace(c) || c == '\u200B') { pos--; continue; }
            int match = 0;
            for (int len = Math.min(maxWordLength, pos); len > 0; len--) {
                Integer id = ids.get(s.substring(pos - len, pos));
                if (id != null) { got[found++] = id; match = len; break; }
            }
            if (match == 0) { got[found++] = -1; stopped = true; break; }
            pos -= match;
        }
        if (found < 2 && !stopped && pos == 0) got[found++] = 0; // start of text
        int[] ctx = new int[2];
        ctx[1] = found > 0 ? got[0] : 0;
        ctx[0] = found > 1 && got[0] > 0 ? got[1] : -1;
        return ctx;
    }

    /** Context for the given layout (segmenting scripts without spaces). */
    public int[] contextOf(CharSequence before, Layout layout) {
        if (layout != null && layout.segment) return contextOfUnspaced(before, layout.sentenceEnds);
        return contextOf(before);
    }

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z]+(?:'[A-Za-z]+)?|[.!?\\n]");

    /**
     * Context ids {u, v} for the word about to be typed, from the text before the cursor.
     * Sentence starts (beginning of text, after . ! ? or a new line) give v = 0 ({@code <s>}).
     * If the text ends in the middle of a word, that partial word is ignored.
     */
    public int[] contextOf(CharSequence before) {
        String s = before == null ? "" : before.toString();
        int end = s.length();
        while (end > 0 && (Character.isLetter(s.charAt(end - 1)) || s.charAt(end - 1) == '\'')) end--;
        Matcher m = TOKEN.matcher(s.substring(0, end));
        List<String> toks = new ArrayList<>();
        while (m.find()) toks.add(m.group());
        int[] ctx = new int[2];
        int found = 0;
        int[] got = new int[2];
        for (int i = toks.size() - 1; i >= 0 && found < 2; i--) {
            String t = toks.get(i);
            if (t.equals(".") || t.equals("!") || t.equals("?") || t.equals("\n")) { got[found++] = 0; break; }
            got[found++] = id(t);
        }
        if (found < 2 && (toks.isEmpty() || found == toks.size())) {
            if (found == 0 || got[found - 1] != 0) got[found++] = 0; // start of text
        }
        // got[0] is the nearest word (v), got[1] the one before (u)
        ctx[1] = found > 0 ? got[0] : 0;
        ctx[0] = found > 1 && got[0] != 0 ? got[1] : -1;
        return ctx;
    }
}
