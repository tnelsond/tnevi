package dev.vimkeyboard;

/**
 * Standard Khmer Unicode keyboard (NiDA), positioned on the US QWERTY keys.
 * Source: NiDA layout as shipped in xkeyboard-config (symbols/kh).
 * Each row lists, per physical key: normal, shift, long-press (AltGr) values; "" = none.
 */
final class KhmerLayout {
    private KhmerLayout() { }

    /** US QWERTY characters for each position (normal and shifted), used for hardware keyboards. */
    static final String[] US = {"1234567890-=", "qwertyuiop[]\\", "asdfghjkl;'", "zxcvbnm,./"};
    static final String[] US_SHIFT = {"!@#$%^&*()_+", "QWERTYUIOP{}|", "ASDFGHJKL:\"", "ZXCVBNM<>?"};

    static final String[][] NORMAL = {
            {"១", "២", "៣", "៤", "៥", "៦", "៧", "៨", "៩", "០", "ឥ", "ឲ"},
            {"ឆ", "ឹ", "េ", "រ", "ត", "យ", "ុ", "ិ", "ោ", "ផ", "ៀ", "ឪ", "ឮ"},
            {"ា", "ស", "ដ", "ថ", "ង", "ហ", "្", "ក", "ល", "ើ", "់"},
            {"ឋ", "ខ", "ច", "វ", "ប", "ន", "ម", "ុំ", "។", "៊"},
    };
    static final String[][] SHIFT = {
            {"!", "ៗ", "\"", "៛", "%", "៍", "័", "៏", "(", ")", "៌", "="},
            {"ឈ", "ឺ", "ែ", "ឬ", "ទ", "ួ", "ូ", "ី", "ៅ", "ភ", "ឿ", "ឧ", "ឭ"},
            {"ាំ", "ៃ", "ឌ", "ធ", "អ", "ះ", "ញ", "គ", "ឡ", "ោះ", "៉"},
            {"ឍ", "ឃ", "ជ", "េះ", "ព", "ណ", "ំ", "ុះ", "៕", "?"},
    };
    /** Long-press extras: NiDA AltGr layer; ASCII digits on the number row. */
    static final String[][] ALT = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "៙", "៎"},
            {"ៜ", "៝", "ឯ", "ឫ", "ឨ", "", "", "ឦ", "ឱ", "ឰ", "ឩ", "ឳ", "\\"},
            {"", "", "", "", "", "", "", "ឝ", "", "៖", "ៈ"},
            {"", "", "", "", "ឞ", "", "", ",", ".", "/"},
    };

    /**
     * Phone layout (as in Gboard's Khmer keyboard): 4 rows x 10 keys.
     * Each entry is {main, corner options...}. Shift types the first option;
     * long-press offers all options. "\u200B" is the zero-width-space key.
     */
    static final String[][][] PHONE = {
            {{"ក", "ឥ", "ឦ"}, {"ខ", "ឧ", "ឩ"}, {"គ", "ឪ", "ឳ"}, {"ឃ", "ឫ"}, {"ង", "ឬ"},
             {"ច", "ឭ"}, {"ឆ", "ឮ"}, {"ជ", "ឯ"}, {"ឈ", "ឰ"}, {"ញ", "ឱ", "ឲ"}},
            {{"ដ", "ា"}, {"ិ", "ឋ"}, {"ី", "ឌ"}, {"ឹ", "ឍ"}, {"ណ", "ឺ"},
             {"ត", "ុ"}, {"ថ", "ូ"}, {"ទ", "ួ"}, {"ធ", "ើ"}, {"ន", "ឿ"}},
            {{"ប", "ៀ"}, {"ផ", "េ"}, {"ព", "ែ"}, {"ភ", "ៃ"}, {"ម", "ោ"},
             {"្"}, {"យ", "ៅ"}, {"រ", "ុំ"}, {"ល", "ំ"}, {"វ", "ាំ"}},
            {{"ស", "ះ"}, {"ហ", "ៈ"}, {"ឡ", "េះ"}, {"អ", "ោះ"}, {"់", "៖"},
             {"៉", "៑"}, {"៊", "៌"}, {"័", "៍"}, {"។", "៕"}, {"\u200B"}},
    };

    /** Khmer ?123 layer: {main, long-press options...}. */
    static final String[][][] SYMBOLS = {
            {{"១"}, {"២"}, {"៣"}, {"៤"}, {"៥"}, {"៦"}, {"៧"}, {"៨"}, {"៩"}, {"០"}},
            {{"ៗ"}, {"៛", "$"}, {"«"}, {"»"}, {"៙"}, {"៚"}, {"៘"}, {"៎"}, {"៏"}, {"៝"}},
            {{"!"}, {"@"}, {"#"}, {"$"}, {"%"}, {"^"}, {"&"}, {"*"}, {"("}, {")"}},
            {{"-", "_"}, {"=", "+"}, {"/", "\\"}, {"\"", "`"}, {"'", "~"}, {":", "<"}, {";", ">"}, {"?", "|"}, {"[", "{"}, {"]", "}"}},
    };

    /** Maps a US-layout character from a hardware keyboard to Khmer, or null. */
    static String fromUs(char c) {
        for (int r = 0; r < US.length; r++) {
            int i = US[r].indexOf(c);
            if (i >= 0 && i < NORMAL[r].length) return NORMAL[r][i];
            i = US_SHIFT[r].indexOf(c);
            if (i >= 0 && i < SHIFT[r].length) return SHIFT[r][i];
        }
        return null;
    }

    /** Key label: combining marks get a dotted circle so they are visible on their own. */
    static String label(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.equals("\u200B")) return "\u203A|\u2039";
        int t = Character.getType(s.codePointAt(0));
        if (t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.ENCLOSING_MARK)
            return "◌" + s;
        return s;
    }
}
