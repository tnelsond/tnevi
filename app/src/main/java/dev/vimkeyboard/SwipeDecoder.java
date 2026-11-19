package dev.vimkeyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Gesture ("swipe") typing decoder. Pure Java so it can be tested on the JVM.
 *
 * A gesture is compared with the ideal path through each candidate word's keys (location
 * distance, after resampling both to the same number of points), and combined with the
 * word's frequency rank. Candidates are pruned first by start/end key and by requiring the
 * path to pass near every letter, in order.
 */
public final class SwipeDecoder {

    private static final int N = 48;

    private final String[] words;   // display form, in frequency order
    private final String[] seqs;    // key indices as chars, consecutive duplicates collapsed (null = not swipeable)
    private final List<List<Integer>> buckets; // index by first*K+last key
    private final int keyCount;

    public float freqWeight = 0.08f;
    /** Weight of the language-model cost when a {@link Scorer} is given. */
    public float lmWeight = 0.07f;
    /** Candidate pruning radii, in key widths: first/last key, and every letter along the path. */
    public float endRadius = 1.35f, passRadius = 1.15f;

    /** Language-model cost (-ln P) of the word at a list index, given the current context. */
    public interface Scorer {
        float cost(int index);
    }

    /** Maps a word to the keys a finger passes through: one char per key index, or null if untypeable. */
    public interface Keyer {
        String keys(String word);
    }

    /** QWERTY: letters a-z are keys 0-25; apostrophes and other characters are skipped. */
    public static final Keyer LATIN = new Keyer() {
        @Override public String keys(String w) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < w.length(); k++) {
                char c = Character.toLowerCase(w.charAt(k));
                if (c >= 'a' && c <= 'z') sb.append((char) (c - 'a'));
            }
            return sb.toString();
        }
    };

    public SwipeDecoder(List<String> wordList) {
        this(wordList, LATIN, 26);
    }

    public SwipeDecoder(List<String> wordList, Keyer keyer, int keyCount) {
        this.keyCount = keyCount;
        int n = wordList.size();
        words = new String[n];
        seqs = new String[n];
        buckets = new ArrayList<>(keyCount * keyCount);
        for (int i = 0; i < keyCount * keyCount; i++) buckets.add(new ArrayList<Integer>());
        for (int i = 0; i < n; i++) {
            String w = wordList.get(i);
            words[i] = w;
            String k = keyer.keys(w);
            if (k == null) continue;
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < k.length(); j++) {
                char c = k.charAt(j);
                if (c >= keyCount) { sb = null; break; }
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == c) continue;
                sb.append(c);
            }
            if (sb == null) continue;
            seqs[i] = sb.toString();
            if (sb.length() >= 2) buckets.get(sb.charAt(0) * keyCount + sb.charAt(sb.length() - 1)).add(i);
        }
    }

    /** Key path used for a word (for tests), or null. */
    public String keysOf(int index) { return seqs[index]; }

    public int size() { return words.length; }

    /**
     * @param xs,ys   gesture points (any coordinate system shared with centers)
     * @param centers key centre for each key index (letters a-z are 0-25 on QWERTY), null if missing
     * @param keyW    typical key width in the same units
     */
    public List<String> decode(float[] xs, float[] ys, int count, float[][] centers, float keyW, int max) {
        return decode(xs, ys, count, centers, keyW, max, null);
    }

    public List<String> decode(float[] xs, float[] ys, int count, float[][] centers, float keyW, int max, Scorer scorer) {
        List<String> out = new ArrayList<>();
        if (count < 2) return out;
        float[][] path = resample(xs, ys, count, N);
        float sx = path[0][0], sy = path[0][1], ex = path[N - 1][0], ey = path[N - 1][1];
        List<Integer> firsts = nearKeys(sx, sy, centers, keyW * endRadius);
        List<Integer> lasts = nearKeys(ex, ey, centers, keyW * endRadius);
        final List<float[]> scored = new ArrayList<>(); // {score, index}
        float r = keyW * passRadius;
        for (int f : firsts) for (int l : lasts) {
            for (int idx : buckets.get(f * keyCount + l)) {
                String s = seqs[idx];
                if (!passesNear(path, s, centers, r)) continue;
                float[][] ideal = idealPath(s, centers);
                if (ideal == null) continue;
                float d = 0;
                for (int i = 0; i < N; i++) {
                    float dx = path[i][0] - ideal[i][0], dy = path[i][1] - ideal[i][1];
                    d += (float) Math.sqrt(dx * dx + dy * dy);
                }
                d = d / N / keyW;
                float score = d + (scorer != null ? lmWeight * scorer.cost(idx) : freqWeight * (float) Math.log(idx + 2));
                scored.add(new float[]{score, idx});
            }
        }
        Collections.sort(scored, new Comparator<float[]>() {
            @Override public int compare(float[] a, float[] b) { return Float.compare(a[0], b[0]); }
        });
        for (float[] s : scored) {
            String w = words[(int) s[1]];
            if (!out.contains(w)) out.add(w);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static List<Integer> nearKeys(float x, float y, float[][] centers, float radius) {
        List<Integer> out = new ArrayList<>();
        int best = -1; float bestD = Float.MAX_VALUE;
        for (int c = 0; c < centers.length; c++) {
            if (centers[c] == null) continue;
            float dx = centers[c][0] - x, dy = centers[c][1] - y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < bestD) { bestD = d; best = c; }
            if (d <= radius) out.add(c);
        }
        if (out.isEmpty() && best >= 0) out.add(best);
        return out;
    }

    private static boolean passesNear(float[][] path, String s, float[][] centers, float r) {
        int j = 0;
        float r2 = r * r;
        for (int k = 1; k < s.length() - 1; k++) {
            float[] c = centers[s.charAt(k)];
            if (c == null) return false;
            boolean found = false;
            for (; j < path.length; j++) {
                float dx = path[j][0] - c[0], dy = path[j][1] - c[1];
                if (dx * dx + dy * dy <= r2) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    private static float[][] idealPath(String s, float[][] centers) {
        float[] xs = new float[s.length()], ys = new float[s.length()];
        for (int k = 0; k < s.length(); k++) {
            float[] c = centers[s.charAt(k)];
            if (c == null) return null;
            xs[k] = c[0]; ys[k] = c[1];
        }
        return resample(xs, ys, s.length(), N);
    }

    /** Resamples a polyline to n equally spaced points. */
    static float[][] resample(float[] xs, float[] ys, int count, int n) {
        float[][] out = new float[n][2];
        float total = 0;
        for (int i = 1; i < count; i++) total += dist(xs[i - 1], ys[i - 1], xs[i], ys[i]);
        if (total <= 0) {
            for (int i = 0; i < n; i++) { out[i][0] = xs[0]; out[i][1] = ys[0]; }
            return out;
        }
        float step = total / (n - 1);
        out[0][0] = xs[0]; out[0][1] = ys[0];
        int o = 1;
        float acc = 0;
        float px = xs[0], py = ys[0];
        int i = 1;
        while (i < count && o < n) {
            float d = dist(px, py, xs[i], ys[i]);
            if (acc + d >= step && d > 0) {
                float t = (step - acc) / d;
                float nx = px + t * (xs[i] - px), ny = py + t * (ys[i] - py);
                out[o][0] = nx; out[o][1] = ny; o++;
                px = nx; py = ny; acc = 0;
            } else {
                acc += d; px = xs[i]; py = ys[i]; i++;
            }
        }
        for (; o < n; o++) { out[o][0] = xs[count - 1]; out[o][1] = ys[count - 1]; }
        return out;
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Helper for callers holding centres in a map. */
    public static float[][] centersFromMap(Map<Character, float[]> m) {
        float[][] c = new float[26][];
        for (Map.Entry<Character, float[]> e : m.entrySet()) {
            char ch = Character.toLowerCase(e.getKey());
            if (ch >= 'a' && ch <= 'z') c[ch - 'a'] = e.getValue();
        }
        return c;
    }
}
