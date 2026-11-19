import dev.vimkeyboard.LanguageModel;
import dev.vimkeyboard.SwipeDecoder;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Measures swipe accuracy with and without n-gram context on held-out sentences
 * (sentences the model was not trained on). Gestures are simulated with noise.
 * Usage: java SwipeContextTest en_lm.bin lm_heldout.txt [lmWeight]
 */
public class SwipeContextTest {
    static float NOISE = Float.parseFloat(System.getProperty("noise", "0.28"));
    public static void main(String[] a) throws Exception {
        final LanguageModel lm = LanguageModel.load(new FileInputStream(a[0]));
        List<String> words = new ArrayList<>();
        for (int i = 1; i < lm.size(); i++) words.add(lm.word(i));
        SwipeDecoder dec = new SwipeDecoder(words);
        dec.endRadius = Float.parseFloat(System.getProperty("end", "1.35"));
        dec.passRadius = Float.parseFloat(System.getProperty("pass", "1.15"));
        List<String> sents = Files.readAllLines(Paths.get(a[1]));

        float[][] c = new float[26][];
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        float[] off = {0.5f, 1.0f, 2.0f};
        for (int r = 0; r < 3; r++) for (int i = 0; i < rows[r].length(); i++)
            c[rows[r].charAt(i) - 'a'] = new float[]{off[r] + i, 0.67f + r * 1.33f};

        // context checks
        check(lm, "", 0, -1);
        check(lm, "Hello. ", 0, -1);
        int the = lm.id("the");
        check(lm, "I saw the ", the, lm.id("saw"));
        check(lm, "I saw the ca", the, lm.id("saw"));

        float[] weights = a.length > 2 ? new float[]{Float.parseFloat(a[2])} : new float[]{0.04f, 0.06f, 0.08f, 0.10f, 0.13f};
        boolean first = true;
        for (float w : weights) {
            dec.lmWeight = w;
            Random rnd = new Random(11);
            int n = 0, base1 = 0, base3 = 0, uni1 = 0, ctx1 = 0, ctx3 = 0;
            for (String sent : sents) {
                String[] ws = sent.split(" ");
                StringBuilder before = new StringBuilder();
                for (String word : ws) {
                    String letters = word.toLowerCase().replace("'", "");
                    if (letters.length() >= 2 && n < 4000) {
                        float[][] g = gesture(letters, c, rnd);
                        final int[] ctx = lm.contextOf(before);
                        List<String> base = dec.decode(g[0], g[1], g[0].length, c, 1f, 3);
                        List<String> uni = dec.decode(g[0], g[1], g[0].length, c, 1f, 3, new SwipeDecoder.Scorer() {
                            public float cost(int idx) { return lm.cost(-1, -1, idx + 1); }
                        });
                        List<String> res = dec.decode(g[0], g[1], g[0].length, c, 1f, 3, new SwipeDecoder.Scorer() {
                            public float cost(int idx) { return lm.cost(ctx[0], ctx[1], idx + 1); }
                        });
                        n++;
                        if (!base.isEmpty() && base.get(0).equals(word)) base1++;
                        if (base.contains(word)) base3++;
                        if (!uni.isEmpty() && uni.get(0).equals(word)) uni1++;
                        if (!res.isEmpty() && res.get(0).equals(word)) ctx1++;
                        if (res.contains(word)) ctx3++;
                    }
                    before.append(word).append(' ');
                }
            }
            if (first) {
                System.out.printf("words tested: %d%n", n);
                System.out.printf("frequency rank only:           top1 %.1f%%  top3 %.1f%%%n", 100.0 * base1 / n, 100.0 * base3 / n);
                System.out.printf("unigram from model, no context: top1 %.1f%%%n", 100.0 * uni1 / n);
                first = false;
            }
            System.out.printf("n-gram context, weight %.2f:    top1 %.1f%%  top3 %.1f%%%n", w, 100.0 * ctx1 / n, 100.0 * ctx3 / n);
        }
    }

    static void check(LanguageModel lm, String before, int v, int u) {
        int[] ctx = lm.contextOf(before);
        if (ctx[1] != v || ctx[0] != u) throw new AssertionError("context of '" + before + "' = " + ctx[0] + "," + ctx[1] + " expected " + u + "," + v);
    }

    static float[][] gesture(String letters, float[][] c, Random rnd) {
        List<float[]> pts = new ArrayList<>();
        float[] prev = null;
        for (int k = 0; k < letters.length(); k++) {
            float[] kc = c[letters.charAt(k) - 'a'];
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
