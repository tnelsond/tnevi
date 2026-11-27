import dev.vimkeyboard.LanguageModel;
import dev.vimkeyboard.Layout;
import dev.vimkeyboard.SwipeDecoder;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Swipe accuracy with and without n-gram context, on held-out sentences the model was not trained on.
 * Gestures are simulated: the finger passes each key of the word with Gaussian noise (in key widths).
 *
 * Usage: java SwipeContextTest lm.bin heldout.txt layout.txt [lmWeight]
 *        (a layout.txt without [letters] means the built-in QWERTY layout)
 * System properties: -Dnoise=0.28 -Dend=1.35 -Dpass=1.15
 */
public class SwipeContextTest {
    static float NOISE = Float.parseFloat(System.getProperty("noise", "0.28"));
    static float PROPER = Float.parseFloat(System.getProperty("proper", "1.5"));

    public static void main(String[] a) throws Exception {
        final LanguageModel lm = LanguageModel.load(new FileInputStream(a[0]));
        final Layout layout = Layout.parse(new FileInputStream(a[2]));
        boolean qwerty = layout.letters == null;
        List<String> words = new ArrayList<>();
        for (int i = 1; i < lm.size(); i++) words.add(lm.word(i));

        // key centres in key widths (keys are ~1.33 key widths tall)
        float[][] c;
        SwipeDecoder.Keyer keyer;
        SwipeDecoder dec;
        if (qwerty) {
            c = new float[26][];
            String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
            float[] off = {0.5f, 1.0f, 2.0f};
            for (int r = 0; r < 3; r++) for (int i = 0; i < rows[r].length(); i++)
                c[rows[r].charAt(i) - 'a'] = new float[]{off[r] + i, 0.67f + r * 1.33f};
            keyer = SwipeDecoder.LATIN;
            dec = new SwipeDecoder(words);
        } else {
            int[][] idx = layout.swipeIndices();
            c = new float[layout.swipeKeyCount()][];
            float rowOffset = 0;
            for (int r = 0; r < idx.length; r++) {
                rowOffset = r == idx.length - 1 ? 1.35f : 0f; // last row starts after the shift key
                for (int i = 0; i < idx[r].length; i++)
                    if (idx[r][i] >= 0) c[idx[r][i]] = new float[]{rowOffset + 0.5f + i, 0.6f + r * 1.2f};
            }
            keyer = layout.keyer();
            dec = new SwipeDecoder(words, keyer, c.length);
        }
        dec.endRadius = Float.parseFloat(System.getProperty("end", "1.35"));
        dec.passRadius = Float.parseFloat(System.getProperty("pass", "1.15"));
        int swipeable = 0;
        for (int i = 0; i < words.size(); i++) if (dec.keysOf(i) != null) swipeable++;

        if (qwerty) {
            check(lm, layout, "", 0, -1);
            check(lm, layout, "Hello. ", 0, -1);
            check(lm, layout, "I saw the ", lm.id("the"), lm.id("saw"));
            check(lm, layout, "I saw the ca", lm.id("the"), lm.id("saw"));
        } else {
            String w1 = lm.word(1), w2 = lm.word(2);
            check(lm, layout, "", 0, -1);
            check(lm, layout, w1 + w2, 2, 1);          // no spaces: segmented by dictionary
            check(lm, layout, w1 + "​" + w2, 2, 1);
            check(lm, layout, w1 + "។" + w2, 2, 0); // ។ starts a new sentence
        }

        List<String> sents = Files.readAllLines(Paths.get(a[1]));
        String sep = qwerty ? " " : "​";
        float[] weights = a.length > 3 ? new float[]{Float.parseFloat(a[3])} : new float[]{0.04f, 0.06f, 0.08f, 0.10f, 0.13f};
        boolean first = true;
        for (float w : weights) {
            dec.lmWeight = w;
            Random rnd = new Random(11);
            int n = 0, base1 = 0, base3 = 0, ctx1 = 0, ctx3 = 0;
            for (String sent : sents) {
                String[] ws = sent.split(qwerty ? " " : "​");
                StringBuilder before = new StringBuilder();
                for (String word : ws) {
                    String keys = keyer.keys(word);
                    if (keys != null && collapse(keys).length() >= 2 && n < 4000) {
                        float[][] g = gesture(collapse(keys), c, rnd);
                        final int[] ctx = lm.contextOf(before, layout);
                        List<String> base = dec.decode(g[0], g[1], g[0].length, c, 1f, 3);
                        List<String> res = dec.decode(g[0], g[1], g[0].length, c, 1f, 3, new SwipeDecoder.Scorer() {
                            public float cost(int idx) { return lm.cost(ctx[0], ctx[1], idx + 1) + (Character.isUpperCase(lm.word(idx + 1).charAt(0)) && !lm.word(idx + 1).startsWith("I") ? PROPER : 0); }
                        });
                        n++;
                        if (!base.isEmpty() && base.get(0).equals(word)) base1++;
                        if (base.contains(word)) base3++;
                        if (!res.isEmpty() && res.get(0).equals(word)) ctx1++;
                        if (res.contains(word)) ctx3++;
                    }
                    before.append(word).append(sep);
                }
            }
            if (first) {
                System.out.printf("vocabulary %d, swipeable %d; words tested: %d%n", words.size(), swipeable, n);
                System.out.printf("frequency rank only:           top1 %.1f%%  top3 %.1f%%%n", 100.0 * base1 / n, 100.0 * base3 / n);
                first = false;
            }
            System.out.printf("n-gram context, weight %.2f:    top1 %.1f%%  top3 %.1f%%%n", w, 100.0 * ctx1 / n, 100.0 * ctx3 / n);
        }
    }

    static String collapse(String keys) {
        StringBuilder sb = new StringBuilder();
        for (char ch : keys.toCharArray()) if (sb.length() == 0 || sb.charAt(sb.length() - 1) != ch) sb.append(ch);
        return sb.toString();
    }

    static void check(LanguageModel lm, Layout l, String before, int v, int u) {
        int[] ctx = lm.contextOf(before, l);
        if (ctx[1] != v || ctx[0] != u) throw new AssertionError("context of '" + before + "' = " + ctx[0] + "," + ctx[1] + " expected " + u + "," + v);
    }

    static float[][] gesture(String keys, float[][] c, Random rnd) {
        List<float[]> pts = new ArrayList<>();
        float[] prev = null;
        for (int k = 0; k < keys.length(); k++) {
            float[] kc = c[keys.charAt(k)];
            float[] p = {kc[0] + (float) rnd.nextGaussian() * NOISE, kc[1] + (float) rnd.nextGaussian() * NOISE};
            if (prev != null) for (int s = 1; s <= 8; s++) {
                float t = s / 8f;
                pts.add(new float[]{prev[0] + t * (p[0] - prev[0]) + (float) rnd.nextGaussian() * 0.04f,
                        prev[1] + t * (p[1] - prev[1]) + (float) rnd.nextGaussian() * 0.04f});
            } else pts.add(p);
            prev = p;
        }
        float[] xs = new float[pts.size()], ys = new float[pts.size()];
        for (int i = 0; i < xs.length; i++) { xs[i] = pts.get(i)[0]; ys[i] = pts.get(i)[1]; }
        return new float[][]{xs, ys};
    }
}
