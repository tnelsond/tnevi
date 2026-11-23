package dev.vimkeyboard;

/**
 * Built-in Latin letter layouts (phone versions: three rows of letters).
 * Long-press characters follow Gboard's positions: top row = % ^ ~ | [ ] < > { },
 * middle row = @ # & * - + = ( ), bottom row = _ $ " ' : ; /. QWERTZ and AZERTY add their accents.
 */
final class LatinLayouts {
    private LatinLayouts() { }

    static final String[] IDS = {"qwerty", "colemak", "colemak_dh", "dvorak", "workman", "qwertz", "azerty"};
    static final String[] NAMES = {"QWERTY", "Colemak", "Colemak-DH", "Dvorak", "Workman", "QWERTZ (German)", "AZERTY (French)"};

    private static final String[][] ROWS = {
            {"qwertyuiop", "asdfghjkl", "zxcvbnm"},
            {"qwfpgjluy;", "arstdhneio", "zxcvbkm"},
            {"qwfpbjluy;", "arstgmneio", "zxcdvkh"},
            {"',.pyfgcrl", "aoeuidhtns", ";qjkxbmwvz"},
            {"qdrwbjfup;", "ashtgyneoi", "zxmcvkl"},
            {"qwertzuiop", "asdfghjkl", "yxcvbnm"},
            {"azertyuiop", "qsdfghjklm", "wxcvbn"},
    };

    static final String TOP_SYMBOLS = "%^~|[]<>{}";
    static final String MID_SYMBOLS = "@#&*-+=()", BOTTOM_SYMBOLS = "_$\"':;/";

    static int index(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }

    static String[] rows(String id) { return ROWS[index(id)]; }

    /** Shifted form of a key: uppercase for letters, US-keyboard shift for punctuation. */
    static String shifted(char c) {
        if (Character.isLetter(c)) return String.valueOf(Character.toUpperCase(c));
        int i = ";',./".indexOf(c);
        return i >= 0 ? String.valueOf(":\"<>?".charAt(i)) : null;
    }

    /** Long-press options for the key at (row, position): accents first, then digit/symbol. */
    static String[] options(String id, int row, int pos, char c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String acc = accents(id, c);
        for (int i = 0; i < acc.length(); i++) out.add(String.valueOf(acc.charAt(i)));
        if (row == 0) {
            // digits are on the number row, so the top row only has symbols
            if (pos < TOP_SYMBOLS.length()) out.add(String.valueOf(TOP_SYMBOLS.charAt(pos)));
        } else if (row == 1) {
            if (pos < MID_SYMBOLS.length()) out.add(String.valueOf(MID_SYMBOLS.charAt(pos)));
        } else if (pos < BOTTOM_SYMBOLS.length()) {
            out.add(String.valueOf(BOTTOM_SYMBOLS.charAt(pos)));
        }
        out.remove(String.valueOf(c));
        return out.toArray(new String[0]);
    }

    private static String accents(String id, char c) {
        if (id.equals("qwertz")) {
            switch (c) { case 'a': return "ä"; case 'o': return "ö"; case 'u': return "ü"; case 's': return "ß"; }
        } else if (id.equals("azerty")) {
            switch (c) {
                case 'e': return "éèêë"; case 'a': return "àâ"; case 'c': return "ç"; case 'u': return "ùûü";
                case 'i': return "îï"; case 'o': return "ôœ"; case 'y': return "ÿ";
            }
        }
        return "";
    }
}
