import dev.vimkeyboard.VimEngine;

import java.util.ArrayList;
import java.util.List;

/** Plain-JVM tests for VimEngine. Run: javac + java (see build.sh). */
public class VimEngineTest {

    static final class FakeHost implements VimEngine.Host {
        StringBuilder sb = new StringBuilder();
        int selS, selE;
        String clip = null;
        int actions = 0;
        boolean singleLine = false;
        public CharSequence getText() { return sb.toString(); }
        public int textOffset() { return 0; }
        public void replace(int s, int e, String t) { sb.replace(s, e, t); selS = selE = s + t.length(); }
        public void setSelection(int s, int e) { selS = s; selE = e; }
        public boolean isSingleLine() { return singleLine; }
        public void performEditorAction() { actions++; }
        public void hideKeyboard() { }
        public String getClipboard() { return clip; }
        public void setClipboard(String t) { clip = t; }
    }

    static int passed = 0, failed = 0;

    static List<String> keys(String s) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                int j = s.indexOf('>', i);
                if (j > i + 1 && s.substring(i + 1, j).matches("[A-Za-z\\-\\[]+")) {
                    out.add(s.substring(i, j + 1)); i = j; continue;
                }
            }
            out.add(String.valueOf(c));
        }
        return out;
    }

    /** start: text with '|' marking the cursor. expect: text with '|' marking expected cursor (null = don't check). */
    static void t(String name, String start, String keySeq, String expect) {
        FakeHost h = new FakeHost();
        int c = start.indexOf('|');
        h.sb.append(start.replace("|", ""));
        VimEngine e = new VimEngine(h);
        e.setBlockCursor(false);
        h.selS = h.selE = c;
        e.reset(true, c, c);
        for (String k : keys(keySeq)) e.handleKey(k);
        String got = h.sb.toString();
        String gotWithCursor = new StringBuilder(got).insert(Math.min(e.getCursor(), got.length()), '|').toString();
        boolean ok = expect.contains("|") ? gotWithCursor.equals(expect) : got.equals(expect);
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  keys:   " + keySeq + "\n  expect: " + esc(expect) + "\n  got:    " + esc(gotWithCursor) + "  [" + e.modeLabel() + "] " + e.statusText());
        }
    }

    static String esc(String s) { return s.replace("\n", "\\n"); }

    public static void main(String[] a) {
        // motions
        t("w", "|foo bar baz", "w", "foo |bar baz");
        t("2w", "|foo bar baz", "2w", "foo bar |baz");
        t("w punct", "|foo.bar", "w", "foo|.bar");
        t("W", "|foo.bar baz", "W", "foo.bar |baz");
        t("b", "foo bar |baz", "b", "foo |bar baz");
        t("e", "|foo bar", "e", "fo|o bar");
        t("ge", "foo bar|", "ge", "fo|o bar");
        t("$", "|hello world", "$", "hello worl|d");
        t("0", "hello |world", "0", "|hello world");
        t("^", "   he|llo", "^", "   |hello");
        t("j col", "ab|cd\nxy\nlmnop", "jj", "abcd\nxy\nlm|nop");
        t("k", "abc\nde|f", "k", "ab|c\ndef");
        t("gg", "a\nb\n|c", "gg", "|a\nb\nc");
        t("G", "|a\nb\nc", "G", "a\nb\n|c");
        t("2G", "|a\nb\nc", "2G", "a\n|b\nc");
        t("f", "|abcabc", "fc", "ab|cabc");
        t("2f", "|abcabc", "2fc", "abcab|c");
        t("t", "|abcabc", "tc", "a|bcabc");
        t("F", "abcab|c", "Fa", "abc|abc");
        t(";", "|a,b,c", "f,;", "a,b|,c");
        t(",", "|a,b,c", "f,;,", "a|,b,c");
        t("%", "|(a [b] c)", "%", "(a [b] c|)");
        t("}", "|a\nb\n\nc", "}", "a\nb\n|\nc");
        t("{", "a\n\nb\n|c", "{", "a\n|\nb\nc");
        t("w empty line", "|foo\n\nbar", "w", "foo\n|\nbar");

        // operators
        t("dw", "|foo bar", "dw", "|bar");
        t("dw end of line", "foo |bar\nbaz", "dw", "foo| \nbaz");
        t("d2w", "|a b c d", "d2w", "|c d");
        t("2dw", "|a b c d", "2dw", "|c d");
        t("de", "|foo bar", "de", "| bar");
        t("cw", "|foo bar", "cwxy<Esc>", "x|y bar");
        t("cw single", "|a bar", "cwz<Esc>", "|z bar");
        t("dd", "a\n|b\nc", "dd", "a\n|c");
        t("dd last", "a\nb\n|c", "dd", "a\n|b");
        t("dd only", "|abc", "dd", "|");
        t("2dd", "|a\nb\nc", "2dd", "|c");
        t("dj", "|a\nb\nc", "dj", "|c");
        t("dk", "a\nb\n|c", "dk", "|a");
        t("dG", "a\n|b\nc", "dG", "|a");
        t("d$", "ab|cd", "d$", "a|b");
        t("D", "ab|cd", "D", "a|b");
        t("C", "ab|cd", "Cxy<Esc>", "abx|y");
        t("d0", "abc|d", "d0", "|d");
        t("dt", "|foo(bar)", "dt(", "|(bar)");
        t("df", "|foo(bar)", "df(", "|bar)");
        t("x", "|abc", "x", "|bc");
        t("3x", "|abcde", "3x", "|de");
        t("x eol", "ab|c", "x", "a|b");
        t("X", "ab|c", "X", "a|c");
        t("s", "|abc", "sz<Esc>", "|zbc");
        t("S", "  ab|c\nd", "Sxy<Esc>", "  x|y\nd");
        t("cc", "a\n|bcd\ne", "ccz<Esc>", "a\n|z\ne");
        t("yy p", "|a\nb", "yyp", "a\n|a\nb");
        t("yy P", "a\n|b", "yyP", "a\n|b\nb");
        t("yy p last", "a\n|b", "yyp", "a\nb\n|b");
        t("dd p", "|a\nb", "ddp", "b\n|a");
        t("yw P", "|foo bar", "ywP", "foo| foo bar");
        t("x p", "|ab", "xp", "b|a");
        t("3p", "|a", "yl3p", "aaa|a");
        t("ciw", "foo b|ar baz", "ciwX<Esc>", "foo |X baz");
        t("diw", "foo b|ar baz", "diw", "foo | baz");
        t("daw", "foo b|ar baz", "daw", "foo |baz");
        t("di\"", "x = \"he|llo\";", "di\"", "x = \"|\";");
        t("da\"", "x = \"he|llo\";", "da\"", "x =|;");
        t("ci(", "f(a, |b)", "ci(z<Esc>", "f(|z)");
        t("da(", "f(a, |b) x", "da(", "f| x");
        t("di( nested", "f(a, (|b), c)", "2di(", "f(|)");
        t("di{ multiline", "if {\n    fo|o\n}", "di{", "if {\n|}");
        t("dip", "a\n|b\n\nc", "dip", "|\nc");
        t("dap", "a\n|b\n\nc", "dap", "|c");
        t(">>", "|a\nb", ">>", "    |a\nb");
        t("<<", "    |a", "<<", "|a");
        t("3>>", "|a\nb\nc", "3>>", "    |a\n    b\n    c");
        t("gUiw", "|foo bar", "gUiw", "|FOO bar");
        t("guu", "FOO |BAR", "guu", "|foo bar");
        t("g~~", "|aBc", "g~~", "|AbC");
        t("~", "|abc", "~~", "AB|c");
        t("r", "|abc", "rx", "|xbc");
        t("3r", "|abcd", "3rx", "xx|xd");
        t("J", "|a\n  b", "J", "a| b");
        t("3J", "|a\nb\nc", "3J", "a b| c");
        t("gJ", "|a\n b", "gJ", "a| b");
        t("<C-a>", "x |9 y", "<C-a>", "x 1|0 y");
        t("5<C-x>", "|val 3", "5<C-x>", "val -|2");

        // insert
        t("i", "a|c", "ib<Esc>", "a|bc");
        t("a", "|ac", "ab<Esc>", "a|bc");
        t("A", "|ab", "Ac<Esc>", "ab|c");
        t("I", "  a|b", "Ix<Esc>", "  |xab");
        t("o", "  |a\nb", "ox<Esc>", "  a\n  |x\nb");
        t("O", "a\n|b", "Ox<Esc>", "a\n|x\nb");
        t("3i", "|", "3iab<Esc>", "ababa|b");
        t("insert BS", "|", "iabc<BS><BS>d<Esc>", "a|d");
        t("insert CR", "|", "iab<CR>cd<Esc>", "ab\nc|d");
        t("insert C-w", "|", "ifoo bar<C-w>x<Esc>", "foo |x");
        t("R", "|abcd", "Rxy<Esc>", "x|ycd");

        // undo / redo / dot
        t("u", "|foo bar", "dwu", "|foo bar");
        t("u insert", "|ab", "ixyz<Esc>u", "|ab");
        t("u twice", "|abc", "xxuu", "|abc");
        t("<C-r>", "|abc", "xu<C-r>", "|bc");
        t("dot dw", "|a b c d", "dw..", "|d");
        t("dot insert", "|x", "ia<Esc>.", "|aax");
        t("dot ciw", "|foo foo", "ciwbar<Esc>w.", "bar ba|r");
        t("dot count", "|abcdef", "x3.", "|ef");
        t("u after dot", "|a b c", "dw.u", "|b c");
        t("A dot", "|a\nb", "A;<Esc>j.", "a;\nb|;");

        // visual
        t("v d", "|abcdef", "vlld", "|def");
        t("v y p", "|ab", "vlyP", "a|bab");
        t("V d", "a\n|b\nc", "Vd", "a\n|c");
        t("V j d", "|a\nb\nc", "Vjd", "|c");
        t("v iw", "foo b|ar baz", "viwd", "foo | baz");
        t("v U", "|abc", "vlU", "|ABc");
        t("v c", "|abc", "vlcX<Esc>", "|Xc");
        t("v >", "|a\nb", "Vj>", "    |a\n    b");
        t("v o", "a|bcd", "vlohd", "|d");
        t("v r", "|abcd", "vllrx", "|xxxd");
        t("v p", "|foo bar", "yiwwviwp", "foo fo|o");
        t("V J", "|a\nb\nc", "VjjJ", "a b| c");

        // search / cmdline
        t("/", "|foo bar foo", "/foo<CR>", "foo bar |foo");
        t("/ wrap", "foo bar |foo", "/foo<CR>", "|foo bar foo");
        t("n", "|a x a x a", "/a<CR>n", "a x a x |a");
        t("N", "|a x a x a", "/a<CR>nN", "a x |a x a");
        t("?", "a x a |x", "?a<CR>", "a x |a x");
        t("*", "|foo bar foo", "*", "foo bar |foo");
        t("d/", "|foo bar foo", "d/bar<CR>", "bar foo");
        t(":s", "|foo foo\nfoo", ":s/foo/bar/<CR>", "bar foo\nfoo");
        t(":s g", "|foo foo\nfoo", ":s/foo/bar/g<CR>", "bar bar\nfoo");
        t(":%s", "|foo\nfoo", ":%s/foo/x/<CR>", "x\nx");
        t(":%s groups", "|ab cd", ":%s/\\(\\w\\)\\(\\w\\)/\\2\\1/g<CR>", "ba dc");
        t(":%s java groups", "|ab cd", ":%s/(\\w)(\\w)/\\2\\1/g<CR>", "ba dc");
        t(":%s &", "|ab", ":%s/b/[&]/<CR>", "a[b]");
        t(":s smartcase", "|Foo foo", ":s/foo/x/g<CR>", "x x");
        t(":'<,'>s", "a\n|b\nc\nd", "Vj:s/$/;/<CR>", "a\nb;\nc;\nd");
        t(":3", "|a\nb\nc", ":3<CR>", "a\nb\n|c");
        t(":d", "|a\nb", ":d<CR>", "|b");
        t(":2,3d", "|a\nb\nc\nd", ":2,3d<CR>", "a\n|d");
        t(":g/x/d", "|ax\nb\ncx", ":g/x/d<CR>", "b");
        t(":v/x/d", "|ax\nb\ncx", ":v/x/d<CR>", "ax\ncx");
        t(":sort", "|c\na\nb", ":sort<CR>", "a\nb\nc");
        t("&", "|a a\na a", ":s/a/b/<CR>j&", "b a\nb a");

        // registers & marks
        t("named reg", "|foo bar", "\"ayiwwdiw\"ap", "foo fo|o");
        t("black hole", "|foo bar", "yiww\"_diwp", "foo fo|o");
        t("marks", "|a\nb\nc", "majj'ax", "|\nb\nc");
        t("backtick mark", "a|bc\nd", "mbj`bx", "a|c\nd");

        // edge cases
        t("empty text dd", "|", "dd", "|");
        t("empty x", "|", "x", "|");
        t("j on last", "|a", "j", "|a");
        t("emoji x", "|😀b", "x", "|b");
        t("emoji l", "|😀b", "l", "😀|b");

        // Khmer: clusters move/delete as one character; ZWSP separates words
        t("khmer x cluster", "|\u1781\u17D2\u1798\u17C2\u179A", "x", "|\u179A");
        t("khmer l", "|\u1781\u17D2\u1798\u17C2\u179A", "l", "\u1781\u17D2\u1798\u17C2|\u179A");
        t("khmer h", "\u1781\u17D2\u1798\u17C2|\u179A", "h", "|\u1781\u17D2\u1798\u17C2\u179A");
        t("khmer w zwsp", "|\u1780\u17B6\u200B\u1781\u17C6", "w", "\u1780\u17B6\u200B|\u1781\u17C6");
        t("khmer diw", "|\u179F\u17BD\u179F\u17D2\u178F\u17B8 \u1780", "diw", "| \u1780");
        t("khmer insert", "|", "i\u1781\u17D2\u1798\u17C2\u179A<Esc>", "\u1781\u17D2\u1798\u17C2|\u179A");
        t("khmer BS component", "|", "i\u1781\u17D2\u1798\u17C2<BS><Esc>", "|\u1781\u17D2\u1798");
        t("khmer BS twice", "|", "i\u1781\u17D2\u1798\u17C2<BS><BS><BS>\u1780<Esc>", "\u1781|\u1780");
        t("emoji BS", "|", "ia\uD83D\uDE00<BS><Esc>", "|a");
        t("insert Up", "abc\nde|f", "i<Up>X<Esc>", "ab|Xc\ndef");
        t("insert Up sticky col", "abcd\nx\nabc|d", "i<Up><Up>X<Esc>", "abc|Xd\nx\nabcd");
        t("normal Up no history", "abc\nde|f", "<Up>", "abc\nde|f");
        t("history: Up reruns last :", "|foo foo", ":s/foo/x/<CR><Up><CR>", "x x");
        t("history: prefix match", "|a a c", ":s/a/b/<CR>:%s/c/d/<CR>:s<Up><CR>", "b b d");
        t("history: Up Up = older", "|aab", ":s/a/1/<CR>:s/x/y/<CR><Up><Up><CR>", "11b");
        t("history: Up Up Down = newer", "|aab", ":s/a/1/<CR>:s/x/y/<CR><Up><Up><Down><CR>", "1ab");
        t("history: Down past newest restores typed text", "|abc", ":s/a/1/<CR>:s/b/<Up><Down>2/<CR>", "12c");
        t("history: search", "|xb ab b", "/b<CR>/<Up><CR>", "xb a|b b");
        t("history: dupes moved to end", "|aaaa", ":s/a/b/<CR>:s/a/c/<CR>:s/a/b/<CR><Up><Up><CR>", "bcbc");
        t("swipe word insert", "|", "ihello<Esc>", "hell|o");

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
