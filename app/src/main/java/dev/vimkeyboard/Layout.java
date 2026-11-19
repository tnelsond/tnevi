package dev.vimkeyboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A language pack's layout file (assets/layout.txt). Pure Java, so it can be tested on the JVM.
 *
 * <pre>
 * name=ខ្មែរ           display name
 * separator=​     inserted between swiped words
 * segment=true         text has no spaces between words (context needs dictionary segmentation)
 * sentence_end=។៕!?
 * [letters]            one row per line, keys separated by TAB;
 *                      a key = main output then options (shift / long-press), separated by spaces
 * [symbols]            the ?123 page, same format
 * [hardware]           US-keyboard character TAB output (physical keyboards)
 * </pre>
 * A pack without a [letters] section uses the built-in QWERTY layout.
 */
public final class Layout {
    public String name = "?";
    public String separator = " ";
    public boolean segment = false;
    public String sentenceEnds = ".!?";
    /** rows of keys; each key is {main, option...}. Null for the built-in QWERTY layout. */
    public List<List<String[]>> letters, symbols;
    public final Map<Character, String> hardware = new HashMap<>();

    public static Layout parse(InputStream in) throws IOException {
        Layout l = new Layout();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String section = "";
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) { section = line; continue; }
            switch (section) {
                case "":
                    int eq = line.indexOf('=');
                    if (eq < 0) break;
                    String k = line.substring(0, eq).trim(), v = unescape(line.substring(eq + 1));
                    if (k.equals("name")) l.name = v.trim();
                    else if (k.equals("separator")) l.separator = v;
                    else if (k.equals("segment")) l.segment = v.trim().equals("true");
                    else if (k.equals("sentence_end")) l.sentenceEnds = v.trim();
                    break;
                case "[letters]": case "[symbols]": {
                    List<String[]> row = new ArrayList<>();
                    for (String key : line.split("\t")) {
                        String[] opts = key.split(" ");
                        for (int i = 0; i < opts.length; i++) opts[i] = unescape(opts[i]);
                        if (opts.length > 0 && !opts[0].isEmpty()) row.add(opts);
                    }
                    if (section.equals("[letters]")) { if (l.letters == null) l.letters = new ArrayList<>(); l.letters.add(row); }
                    else { if (l.symbols == null) l.symbols = new ArrayList<>(); l.symbols.add(row); }
                    break;
                }
                case "[hardware]": {
                    int tab = line.indexOf('\t');
                    if (tab == 1) l.hardware.put(line.charAt(0), unescape(line.substring(2)));
                    break;
                }
            }
        }
        r.close();
        return l;
    }

    static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 5 < s.length() + 0 && s.charAt(i + 1) == 'u' && i + 6 <= s.length()) {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) { }
            }
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '\\') { sb.append('\\'); i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Swipe key index of each letters key (row-major), or -1 for keys that aren't swiped through. */
    public int[][] swipeIndices() {
        int[][] idx = new int[letters.size()][];
        int n = 0;
        for (int r = 0; r < letters.size(); r++) {
            idx[r] = new int[letters.get(r).size()];
            for (int i = 0; i < idx[r].length; i++) {
                String main = letters.get(r).get(i)[0];
                idx[r][i] = isSwipeable(main) ? n++ : -1;
            }
        }
        return idx;
    }

    public int swipeKeyCount() {
        int n = 0;
        for (List<String[]> row : letters) for (String[] k : row) if (isSwipeable(k[0])) n++;
        return n;
    }

    private static boolean isSwipeable(String main) {
        return !main.equals("​") && !main.isEmpty() && Character.isLetter(main.codePointAt(0))
                || (main.length() > 0 && isMark(main.charAt(0)));
    }

    private static boolean isMark(char c) {
        int t = Character.getType(c);
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.ENCLOSING_MARK;
    }

    /**
     * Maps words to swipe key paths: every output of a key (main and options) belongs to that key,
     * matched greedily longest-first (so "ាំ" uses its own key rather than ា + ំ).
     */
    public SwipeDecoder.Keyer keyer() {
        final Map<String, Integer> out = new HashMap<>();
        int[][] idx = swipeIndices();
        int maxLen = 1;
        for (int r = 0; r < letters.size(); r++)
            for (int i = 0; i < letters.get(r).size(); i++) {
                if (idx[r][i] < 0) continue;
                for (String o : letters.get(r).get(i)) {
                    if (!out.containsKey(o)) out.put(o, idx[r][i]);
                    maxLen = Math.max(maxLen, o.length());
                }
            }
        final int max = maxLen;
        return new SwipeDecoder.Keyer() {
            @Override public String keys(String word) {
                StringBuilder sb = new StringBuilder();
                int p = 0;
                while (p < word.length()) {
                    int len = Math.min(max, word.length() - p);
                    Integer k = null;
                    for (; len > 0; len--) {
                        k = out.get(word.substring(p, p + len));
                        if (k != null) break;
                    }
                    if (k == null) return null; // not typeable on this layout
                    sb.append((char) (int) k);
                    p += len;
                }
                return sb.toString();
            }
        };
    }

    /** Key label: combining marks get a dotted circle; the zero-width space key shows ›|‹. */
    public static String label(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.equals("​")) return "›|‹";
        if (isMark(s.charAt(0))) return "◌" + s;
        return s;
    }
}
