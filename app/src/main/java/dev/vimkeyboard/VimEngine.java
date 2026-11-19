package dev.vimkeyboard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A small vi/vim emulator that edits text through a {@link Host}.
 *
 * Pure Java (no Android dependencies) so it can be unit tested on the JVM.
 * Keys are strings: a single character ("d", "$", "J") or a special key in
 * angle brackets ("<Esc>", "<CR>", "<BS>", "<C-r>", ...).
 */
public final class VimEngine {

    public enum Mode { INSERT, REPLACE, NORMAL, VISUAL, VISUAL_LINE, CMDLINE }

    /** The text field being edited. Positions are relative to {@link #textOffset()}. */
    public interface Host {
        CharSequence getText();
        /** Absolute position of index 0 of {@link #getText()} (non-zero when only part of the text is visible). */
        int textOffset();
        void replace(int start, int end, String text);
        void setSelection(int start, int end);
        boolean isSingleLine();
        void performEditorAction();
        void hideKeyboard();
        String getClipboard();
        void setClipboard(String text);
    }

    // ------------------------------------------------------------------ types

    static final class Reg {
        final String text; final boolean linewise;
        Reg(String t, boolean l) { text = t; linewise = l; }
    }

    static final class Snap {
        final String text; final int cursor;
        Snap(String t, int c) { text = t; cursor = c; }
    }

    static final class Range {
        final int start, end; final boolean linewise;
        Range(int s, int e, boolean l) { start = s; end = e; linewise = l; }
    }

    private static final int EXCL = 0, INCL = 1, LINE = 2;

    static final class Target {
        final int pos, kind;
        Target(int p, int k) { pos = p; kind = k; }
    }

    static final class Cmd {
        char reg = 0;
        int count = 0, count2 = 0;
        String op, motion, arg, obj, action;
        boolean doubled;
        int total() { return Math.max(1, count) * Math.max(1, count2); }
        boolean hasCount() { return count > 0 || count2 > 0; }
    }

    private static final Cmd INCOMPLETE = new Cmd(), INVALID = new Cmd();

    private static final Set<String> SIMPLE_MOTIONS = new HashSet<>(Arrays.asList(
            "h", "j", "k", "l", "w", "W", "b", "B", "e", "E", "0", "^", "$", "G", "%", "{", "}",
            ";", ",", "n", "N", "*", "#", "_", "-", "+", "<BS>", " ", "<CR>", "<C-d>", "<C-u>",
            "<Left>", "<Right>", "<Up>", "<Down>", "<Home>", "<End>", "H", "L", "M"));
    private static final Set<String> ARG_MOTIONS = new HashSet<>(Arrays.asList("f", "F", "t", "T", "`", "'"));
    private static final Set<String> G_MOTIONS = new HashSet<>(Arrays.asList("g", "e", "E", "_", "0", "$"));
    private static final Set<String> SIMPLE_ACTIONS = new HashSet<>(Arrays.asList(
            "x", "X", "s", "S", "C", "D", "Y", "p", "P", "u", "U", "<C-r>", ".", "i", "a", "I", "A",
            "o", "O", "J", "~", "v", "V", ":", "/", "?", "<C-a>", "<C-x>", "R", "<Del>", "&"));
    private static final Set<String> CHANGE_ACTIONS = new HashSet<>(Arrays.asList(
            "x", "X", "s", "S", "C", "D", "p", "P", "J", "gJ", "~", "r", "i", "a", "I", "A",
            "o", "O", "R", "<C-a>", "<C-x>", "<Del>", "&"));
    private static final String OBJ_KINDS = "wWsp\"'`()b{}B[]<>";

    // ------------------------------------------------------------------ state

    private final Host host;
    private Mode mode = Mode.INSERT;
    private Mode cmdReturnMode = Mode.NORMAL;
    private String text = "";
    private int offset = 0;
    private int cursor = 0, anchor = 0;
    private int desiredCol = 0;
    private boolean blockCursor = true;

    private final List<String> pending = new ArrayList<>();
    private final StringBuilder cmdline = new StringBuilder();
    private char cmdType = ':';
    private Range cmdVisual;
    private String message = "";

    private final Map<Character, Reg> regs = new HashMap<>();
    private final Map<Character, Integer> marks = new HashMap<>();
    private String lastFindType, lastFindChar;
    private String lastSearch; private boolean lastSearchForward = true;
    private String lastSubPattern, lastSubReplacement, lastSubFlags = "";

    private final ArrayDeque<Snap> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private Snap pendingSnap;

    private List<String> lastChange, recording;
    private int replayDepth = 0;

    private final List<String> insertKeys = new ArrayList<>();
    private int insertRepeat = 1;
    private boolean replayingInsert = false;
    private boolean arrowRun = false;

    private Cmd pendingSearchOp;
    private List<String> searchOpKeys;

    private int lastVisAnchor = -1, lastVisCursor = -1;
    private Mode lastVisMode = Mode.VISUAL;

    public VimEngine(Host host) { this.host = host; }

    // ------------------------------------------------------------------ public API

    public Mode getMode() { return mode; }
    public int getCursor() { return cursor; }
    public void setBlockCursor(boolean b) { blockCursor = b; }

    /** Called when a new text field gains focus. Positions are absolute field offsets. */
    public void reset(boolean startInNormal, int selStart, int selEnd) {
        pending.clear(); cmdline.setLength(0);
        undo.clear(); redo.clear(); pendingSnap = null;
        recording = null; insertKeys.clear(); insertRepeat = 1;
        pendingSearchOp = null;
        marks.clear(); lastVisAnchor = lastVisCursor = -1;
        message = "";
        mode = startInNormal ? Mode.NORMAL : Mode.INSERT;
        syncCursor(selStart, selEnd);
        if (mode == Mode.NORMAL) render();
    }

    /** Moves the engine's cursor to the field's selection (absolute offsets) without rendering. */
    public void syncCursor(int start, int end) {
        refresh();
        cursor = clamp(Math.min(start, end) - offset);
        if (mode == Mode.NORMAL) cursor = clampNormal(cursor);
        desiredCol = col(cursor);
    }

    /** Leaves visual/command-line state (used when the keyboard is hidden). */
    public void cancelPending() {
        pending.clear();
        if (mode == Mode.CMDLINE) leaveCmdline();
        if (mode == Mode.VISUAL || mode == Mode.VISUAL_LINE) exitVisual();
    }

    /** Short label for the mode indicator. */
    public String modeLabel() {
        switch (mode) {
            case INSERT: return "INSERT";
            case REPLACE: return "REPLACE";
            case VISUAL: return "VISUAL";
            case VISUAL_LINE: return "V-LINE";
            case CMDLINE: return "COMMAND";
            default: return "NORMAL";
        }
    }

    /** Text for the status line: command line, pending keys or the last message. */
    public String statusText() {
        if (mode == Mode.CMDLINE) return cmdType + cmdline.toString() + "█";
        if (!pending.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String k : pending) sb.append(k);
            return sb.toString();
        }
        return message;
    }

    /** The field's selection changed for a reason other than this engine (tap, drag, app). */
    public void onExternalSelection(int s, int e) {
        refresh();
        int a = clamp(Math.min(s, e) - offset), b = clamp(Math.max(s, e) - offset);
        pending.clear();
        switch (mode) {
            case INSERT: case REPLACE:
                cursor = b; return;
            case CMDLINE:
                cursor = a; return;
            default:
                if (a != b) {
                    if (mode != Mode.VISUAL_LINE) mode = Mode.VISUAL;
                    anchor = a; cursor = prev(b);
                } else {
                    if (mode == Mode.VISUAL || mode == Mode.VISUAL_LINE) mode = Mode.NORMAL;
                    cursor = clampNormal(a);
                }
                desiredCol = col(cursor);
                render();
        }
    }

    public void handleKey(String key) {
        refresh();
        message = "";
        dispatch(key);
        render();
    }

    // ------------------------------------------------------------------ dispatch

    private void dispatch(String key) {
        switch (mode) {
            case INSERT: case REPLACE: insertKey(key); break;
            case NORMAL: normalKey(key); break;
            case VISUAL: case VISUAL_LINE: visualKey(key); break;
            case CMDLINE: cmdKey(key); break;
        }
    }

    private static boolean isEscape(String k) {
        return k.equals("<Esc>") || k.equals("<C-[>") || k.equals("<C-c>");
    }

    private void refresh() {
        CharSequence cs = host.getText();
        text = cs == null ? "" : cs.toString();
        int off = host.textOffset();
        if (off != offset) {
            cursor -= off - offset; anchor -= off - offset; offset = off;
        }
        cursor = clamp(cursor);
        anchor = clamp(anchor);
    }

    private void render() {
        Mode m = mode == Mode.CMDLINE ? cmdReturnMode : mode;
        int n = text.length();
        switch (m) {
            case VISUAL: {
                int a = Math.min(anchor, cursor), b = Math.min(n, next(Math.max(anchor, cursor)));
                host.setSelection(a, b);
                break;
            }
            case VISUAL_LINE: {
                int a = lineStart(Math.min(anchor, cursor)), b = lineEnd(Math.max(anchor, cursor));
                host.setSelection(a, b);
                break;
            }
            case NORMAL:
                if (blockCursor && cursor < n && text.charAt(cursor) != '\n')
                    host.setSelection(cursor, next(cursor));
                else host.setSelection(cursor, cursor);
                break;
            default:
                host.setSelection(cursor, cursor);
        }
    }

    // ------------------------------------------------------------------ editing primitives

    private void replace(int s, int e, String r) {
        s = clamp(s); e = clamp(e);
        if (s == e && r.isEmpty()) return;
        host.replace(s, e, r);
        text = text.substring(0, s) + r + text.substring(e);
    }

    private void setText(String t) {
        int n = text.length(), m = t.length();
        int p = 0;
        while (p < n && p < m && text.charAt(p) == t.charAt(p)) p++;
        int q = 0;
        while (q < n - p && q < m - p && text.charAt(n - 1 - q) == t.charAt(m - 1 - q)) q++;
        replace(p, n - q, t.substring(p, m - q));
    }

    private void beginChange() {
        if (pendingSnap == null) pendingSnap = new Snap(text, cursor);
    }

    private void endChange() {
        if (replayDepth > 0) return;
        if (pendingSnap != null && !pendingSnap.text.equals(text)) {
            undo.push(pendingSnap);
            while (undo.size() > 500) undo.removeLast();
            redo.clear();
        }
        pendingSnap = null;
    }

    private void doUndo(int count) {
        pendingSnap = null;
        for (int i = 0; i < count; i++) {
            if (undo.isEmpty()) { message = "Already at oldest change"; break; }
            Snap s = undo.pop();
            redo.push(new Snap(text, cursor));
            setText(s.text);
            cursor = clampNormal(s.cursor);
        }
    }

    private void doRedo(int count) {
        pendingSnap = null;
        for (int i = 0; i < count; i++) {
            if (redo.isEmpty()) { message = "Already at newest change"; break; }
            Snap s = redo.pop();
            undo.push(new Snap(text, cursor));
            setText(s.text);
            cursor = clampNormal(s.cursor);
        }
    }

    // ------------------------------------------------------------------ registers

    private void writeReg(char reg, String t, boolean linewise, boolean yank) {
        if (reg == '_') return;
        Reg r = new Reg(t, linewise);
        if (reg >= 'A' && reg <= 'Z') {
            char lower = Character.toLowerCase(reg);
            Reg old = regs.get(lower);
            if (old != null) {
                String joined = old.linewise && !old.text.endsWith("\n") ? old.text + "\n" + t : old.text + t;
                r = new Reg(joined, old.linewise || linewise);
            }
            regs.put(lower, r);
        } else if (reg >= 'a' && reg <= 'z') {
            regs.put(reg, r);
        }
        regs.put('"', r);
        if (yank) regs.put('0', r);
        try { host.setClipboard(r.text); } catch (RuntimeException ignored) { }
    }

    private Reg readReg(char reg) {
        if (reg >= 'A' && reg <= 'Z') reg = Character.toLowerCase(reg);
        if (reg == 0 || reg == '"' || reg == '+' || reg == '*') {
            Reg u = regs.get('"');
            String clip = null;
            try { clip = host.getClipboard(); } catch (RuntimeException ignored) { }
            if (clip != null && !clip.isEmpty() && (u == null || !clip.equals(u.text))) return new Reg(clip, false);
            return u;
        }
        return regs.get(reg);
    }

    // ------------------------------------------------------------------ insert mode

    private void insertKey(String key) {
        if (isEscape(key)) { finishInsert(); return; }
        if (recording != null && !replayingInsert) recording.add(key);
        if (!replayingInsert) insertKeys.add(key);
        // Consecutive Up/Down presses keep the column they started from.
        if (!key.equals("<Up>") && !key.equals("<Down>")) arrowRun = false;
        else if (!arrowRun) { arrowRun = true; desiredCol = col(cursor); }
        int n = text.length();
        switch (key) {
            case "<BS>":
                if (cursor > 0) {
                    // One code point, not the whole cluster: ខ្មែ -> ខ្ម -> ខ្ -> ខ
                    int p = cursor - Character.charCount(text.codePointBefore(cursor));
                    if (mode == Mode.REPLACE) cursor = p;
                    else { replace(p, cursor, ""); cursor = p; }
                }
                break;
            case "<Del>":
                if (cursor < n) replace(cursor, next(cursor), "");
                break;
            case "<CR>":
                if (host.isSingleLine()) host.performEditorAction();
                else {
                    String ind = leadingWs(lineStart(cursor));
                    if (ind.length() > col(cursor)) ind = ind.substring(0, col(cursor));
                    insertText("\n" + ind, false);
                }
                break;
            case "<Tab>":
                insertText("\t", mode == Mode.REPLACE);
                break;
            case "<C-w>": {
                int ls = lineStart(cursor);
                int p = cursor > ls ? Math.max(ls, prevWordStart(cursor, false)) : Math.max(0, cursor - 1);
                replace(p, cursor, ""); cursor = p;
                break;
            }
            case "<C-u>": {
                int ls = lineStart(cursor);
                int p = cursor > ls ? ls : Math.max(0, cursor - 1);
                replace(p, cursor, ""); cursor = p;
                break;
            }
            case "<Left>": cursor = Math.max(0, prev(cursor)); break;
            case "<Up>": case "<Down>": {
                int l = lineNo(cursor), tl = key.equals("<Up>") ? l - 1 : l + 1;
                if (tl < 0 || tl > lastLine()) break;
                int ls = lineStartOf(tl);
                cursor = (int) Math.min((long) ls + desiredCol, lineEnd(ls));
                break;
            }
            case "<Right>": cursor = Math.min(n, next(cursor)); break;
            default:
                if (key.length() > 1 && key.startsWith("<") && key.endsWith(">")) break;
                insertText(key, mode == Mode.REPLACE);
        }
    }

    private void insertText(String s, boolean overwrite) {
        if (overwrite && !s.contains("\n")) {
            int e = cursor, le = lineEnd(cursor);
            for (int i = 0; i < s.codePointCount(0, s.length()) && e < le; i++) e = next(e);
            replace(cursor, e, s);
        } else {
            replace(cursor, cursor, s);
        }
        cursor += s.length();
    }

    private void finishInsert() {
        if (insertRepeat > 1 && !replayingInsert) {
            List<String> ks = new ArrayList<>(insertKeys);
            replayingInsert = true;
            for (int r = 1; r < insertRepeat; r++) for (String k : ks) insertKey(k);
            replayingInsert = false;
        }
        insertRepeat = 1;
        insertKeys.clear();
        if (recording != null) {
            recording.add("<Esc>");
            lastChange = recording;
            recording = null;
        }
        mode = Mode.NORMAL;
        if (cursor > lineStart(cursor)) cursor = prev(cursor);
        cursor = clampNormal(cursor);
        desiredCol = col(cursor);
        endChange();
    }

    private void startInsert(int count, Mode m) {
        mode = m;
        insertRepeat = Math.max(1, Math.min(count, 1000));
        insertKeys.clear();
    }

    // ------------------------------------------------------------------ normal mode

    private void normalKey(String key) {
        if (isEscape(key)) { pending.clear(); return; }
        if (key.equals("<Up>") && pending.isEmpty()) {
            // Up arrow in Normal mode: open ':' with the most recent command (keep pressing for older ones).
            if (exHistory.isEmpty()) { message = "No command history"; return; }
            enterCmdline(':', Mode.NORMAL);
            browseHistory(true);
            return;
        }
        pending.add(key);
        Cmd c = parse(pending, false);
        if (c == INCOMPLETE) return;
        List<String> keys = new ArrayList<>(pending);
        pending.clear();
        if (c == INVALID) return;
        execNormal(c, keys);
    }

    private boolean isChange(Cmd c) {
        if (c.op != null) return !c.op.equals("y");
        return c.action != null && CHANGE_ACTIONS.contains(c.action);
    }

    private void execNormal(Cmd c, List<String> keys) {
        if (replayDepth == 0 && isChange(c)) recording = new ArrayList<>(keys);
        beginChange();
        int count = c.total();
        if (c.op != null) doOperator(c, count);
        else if (c.motion != null) {
            Target t = motion(c, c.motion, count, c.hasCount(), cursor, false);
            if (t != null) moveTo(t, c.motion);
        } else doAction(c, count);
        if (mode == Mode.CMDLINE && pendingSearchOp != null) {
            searchOpKeys = new ArrayList<>(keys);
            recording = null;
            pendingSnap = null;
            return;
        }
        if (mode != Mode.INSERT && mode != Mode.REPLACE) {
            endChange();
            if (recording != null && replayDepth == 0) { lastChange = recording; recording = null; }
        }
    }

    private void moveTo(Target t, String m) {
        int old = cursor;
        cursor = clampNormal(t.pos);
        if (m.equals("$") || m.equals("<End>") || m.equals("g$")) desiredCol = Integer.MAX_VALUE;
        else if (!isVertical(m)) desiredCol = col(cursor);
        if (isJump(m) && old != cursor) marks.put('\'', old);
    }

    private static boolean isVertical(String m) {
        return m.equals("j") || m.equals("k") || m.equals("<Up>") || m.equals("<Down>")
                || m.equals("<C-d>") || m.equals("<C-u>");
    }

    private static boolean isJump(String m) {
        return m.equals("G") || m.equals("gg") || m.equals("n") || m.equals("N") || m.equals("%")
                || m.equals("*") || m.equals("#") || m.equals("{") || m.equals("}")
                || m.equals("`") || m.equals("'");
    }

    // ------------------------------------------------------------------ parser

    private static boolean isDigit(String k) {
        return k.length() == 1 && k.charAt(0) >= '0' && k.charAt(0) <= '9';
    }

    private Cmd parse(List<String> k, boolean visual) {
        Cmd c = new Cmd();
        int i = 0, n = k.size();
        if (k.get(0).equals("\"")) {
            if (n < 2) return INCOMPLETE;
            String r = k.get(1);
            if (r.length() != 1) return INVALID;
            c.reg = r.charAt(0);
            i = 2;
        }
        while (i < n && isDigit(k.get(i)) && !(c.count == 0 && k.get(i).equals("0"))) {
            c.count = Math.min(c.count * 10 + (k.get(i).charAt(0) - '0'), 99999); i++;
        }
        if (i >= n) return INCOMPLETE;
        String key = k.get(i);

        String op = null;
        if (key.equals("d") || key.equals("c") || key.equals("y") || key.equals(">") || key.equals("<")) op = key;
        else if (key.equals("g")) {
            if (i + 1 >= n) return INCOMPLETE;
            String k2 = k.get(i + 1);
            if (k2.equals("~") || k2.equals("u") || k2.equals("U")) { op = "g" + k2; i++; }
        }
        if (op != null) {
            if (visual) { c.action = op; return i + 1 == n ? c : INVALID; }
            c.op = op; i++;
            while (i < n && isDigit(k.get(i)) && !(c.count2 == 0 && k.get(i).equals("0"))) {
                c.count2 = Math.min(c.count2 * 10 + (k.get(i).charAt(0) - '0'), 99999); i++;
            }
            if (i >= n) return INCOMPLETE;
            String m = k.get(i);
            if (m.equals(op.substring(op.length() - 1))) {
                c.doubled = true;
                return i + 1 == n ? c : INVALID;
            }
            if (m.equals("i") || m.equals("a")) {
                if (i + 1 >= n) return INCOMPLETE;
                String o = k.get(i + 1);
                if (o.length() != 1 || OBJ_KINDS.indexOf(o.charAt(0)) < 0) return INVALID;
                c.obj = m + o;
                return i + 2 == n ? c : INVALID;
            }
            if (m.equals("/") || m.equals("?")) {
                c.motion = m;
                return i + 1 == n ? c : INVALID;
            }
            return parseMotion(k, i, c);
        }

        if (visual && (key.equals("i") || key.equals("a"))) {
            if (i + 1 >= n) return INCOMPLETE;
            String o = k.get(i + 1);
            if (o.length() != 1 || OBJ_KINDS.indexOf(o.charAt(0)) < 0) return INVALID;
            c.obj = key + o;
            return i + 2 == n ? c : INVALID;
        }

        Cmd m = parseMotion(k, i, c);
        if (m != INVALID) return m;

        if (key.equals("r") || key.equals("m")) {
            if (i + 1 >= n) return INCOMPLETE;
            c.action = key; c.arg = k.get(i + 1);
            return i + 2 == n ? c : INVALID;
        }
        if (key.equals("g") || key.equals("z") || key.equals("Z")) {
            if (i + 1 >= n) return INCOMPLETE;
            c.action = key + k.get(i + 1);
            return i + 2 == n ? c : INVALID;
        }
        if (SIMPLE_ACTIONS.contains(key)) {
            c.action = key;
            return i + 1 == n ? c : INVALID;
        }
        return INVALID;
    }

    private Cmd parseMotion(List<String> k, int i, Cmd c) {
        int n = k.size();
        String key = k.get(i);
        if (SIMPLE_MOTIONS.contains(key)) {
            c.motion = key;
            return i + 1 == n ? c : INVALID;
        }
        if (ARG_MOTIONS.contains(key)) {
            if (i + 1 >= n) return INCOMPLETE;
            c.motion = key; c.arg = k.get(i + 1);
            return i + 2 == n ? c : INVALID;
        }
        if (key.equals("g")) {
            if (i + 1 >= n) return INCOMPLETE;
            String k2 = k.get(i + 1);
            if (!G_MOTIONS.contains(k2)) return INVALID;
            c.motion = "g" + k2;
            return i + 2 == n ? c : INVALID;
        }
        return INVALID;
    }

    // ------------------------------------------------------------------ motions

    private Target motion(Cmd c, String m, int count, boolean hasCount, int from, boolean forOp) {
        int n = text.length();
        int line = lineNo(from), last = lastLine();
        switch (m) {
            case "h": case "<Left>": {
                int ls = lineStart(from), p = from;
                for (int i = 0; i < count && p > ls; i++) p = prev(p);
                return new Target(p, EXCL);
            }
            case "l": case "<Right>": {
                int le = lineEnd(from), p = from;
                for (int i = 0; i < count && p < le; i++) p = next(p);
                if (!forOp && p >= le && le > lineStart(from)) p = prev(le);
                return new Target(p, EXCL);
            }
            case "<BS>": {
                int p = from;
                for (int i = 0; i < count && p > 0; i++) {
                    p = prev(p);
                    if (!forOp && text.charAt(p) == '\n' && p > lineStart(p)) p = prev(p);
                }
                return new Target(p, EXCL);
            }
            case " ": {
                int p = from;
                for (int i = 0; i < count && p < n; i++) {
                    p = next(p);
                    if (!forOp && p < n && text.charAt(p) == '\n' && p > lineStart(p)) p++;
                }
                return new Target(Math.min(p, n), EXCL);
            }
            case "j": case "<Down>": case "k": case "<Up>": case "<C-d>": case "<C-u>": {
                int amt = (m.equals("<C-d>") || m.equals("<C-u>")) ? (hasCount ? count : 15) : count;
                boolean down = m.equals("j") || m.equals("<Down>") || m.equals("<C-d>");
                int tl = down ? Math.min(last, line + amt) : Math.max(0, line - amt);
                if (tl == line) return null;
                return new Target(posInLine(tl, desiredCol), LINE);
            }
            case "+": case "<CR>": {
                int tl = Math.min(last, line + count);
                if (tl == line) return null;
                return new Target(firstNonBlank(lineStartOf(tl)), LINE);
            }
            case "-": {
                int tl = Math.max(0, line - count);
                if (tl == line) return null;
                return new Target(firstNonBlank(lineStartOf(tl)), LINE);
            }
            case "_":
                return new Target(firstNonBlank(lineStartOf(Math.min(last, line + count - 1))), LINE);
            case "0": case "<Home>": case "g0":
                return new Target(lineStart(from), EXCL);
            case "^":
                return new Target(firstNonBlank(lineStart(from)), EXCL);
            case "$": case "<End>": case "g$": {
                int ls = lineStartOf(Math.min(last, line + count - 1)), le = lineEnd(ls);
                if (le == ls) return new Target(ls, EXCL);
                return new Target(prev(le), INCL);
            }
            case "g_": {
                int ls = lineStartOf(Math.min(last, line + count - 1)), p = lineEnd(ls);
                while (p > ls && isBlank(p - 1)) p--;
                return p == ls ? new Target(ls, EXCL) : new Target(prev(p), INCL);
            }
            case "w": case "W": {
                int p = from;
                for (int i = 0; i < count && p < n; i++) p = nextWordStart(p, m.equals("W"));
                return new Target(p, EXCL);
            }
            case "b": case "B": {
                int p = from;
                for (int i = 0; i < count; i++) p = prevWordStart(p, m.equals("B"));
                return new Target(p, EXCL);
            }
            case "e": case "E": {
                int p = from;
                for (int i = 0; i < count; i++) p = wordEnd(p, m.equals("E"));
                return new Target(Math.max(0, p), INCL);
            }
            case "ge": case "gE": {
                int p = from;
                for (int i = 0; i < count; i++) p = prevWordEnd(p, m.equals("gE"));
                return new Target(p, INCL);
            }
            case "G": {
                int tl = hasCount ? Math.min(last, count - 1) : last;
                return new Target(firstNonBlank(lineStartOf(tl)), LINE);
            }
            case "gg": {
                int tl = hasCount ? Math.min(last, count - 1) : 0;
                return new Target(firstNonBlank(lineStartOf(tl)), LINE);
            }
            case "H": return new Target(firstNonBlank(lineStartOf(Math.min(last, hasCount ? count - 1 : 0))), LINE);
            case "L": return new Target(firstNonBlank(lineStartOf(Math.max(0, last - (hasCount ? count - 1 : 0)))), LINE);
            case "M": return new Target(firstNonBlank(lineStartOf(last / 2)), LINE);
            case "f": case "F": case "t": case "T":
                lastFindType = m; lastFindChar = c.arg;
                return findChar(m, c.arg, count, from, false);
            case ";": case ",": {
                if (lastFindType == null) return null;
                String t = lastFindType;
                if (m.equals(",")) t = swapCase(t);
                return findChar(t, lastFindChar, count, from, true);
            }
            case "%": return matchPair(from);
            case "}": {
                int l = line;
                for (int i = 0; i < count; i++) {
                    while (l < last && isEmptyLine(l)) l++;
                    while (l < last && !isEmptyLine(l)) l++;
                }
                if (!isEmptyLine(l)) return new Target(forOp ? n : Math.max(0, n - 1), forOp ? EXCL : INCL);
                return new Target(lineStartOf(l), EXCL);
            }
            case "{": {
                int l = line;
                for (int i = 0; i < count; i++) {
                    while (l > 0 && isEmptyLine(l)) l--;
                    while (l > 0 && !isEmptyLine(l)) l--;
                }
                return new Target(lineStartOf(l), EXCL);
            }
            case "n": case "N": {
                if (lastSearch == null) { message = "No previous search"; return null; }
                boolean fwd = lastSearchForward ^ m.equals("N");
                int p = findMatch(lastSearch, fwd, from, count);
                if (p < 0) { message = "Pattern not found: " + lastSearch; return null; }
                return new Target(p, EXCL);
            }
            case "*": case "#": {
                int le = lineEnd(from), s = from;
                while (s < le && cls(s, false) != 1) s++;
                if (s >= le) { message = "No word under cursor"; return null; }
                while (s > 0 && cls(s - 1, false) == 1) s--;
                int e = s;
                while (e < n && cls(e, false) == 1) e++;
                lastSearch = "\\<" + text.substring(s, e) + "\\>";
                lastSearchForward = m.equals("*");
                int p = findMatch(lastSearch, lastSearchForward, s, count);
                if (p < 0) return null;
                return new Target(p, EXCL);
            }
            case "`": case "'": {
                if (c.arg == null || c.arg.length() != 1) return null;
                Integer p = marks.get(c.arg.charAt(0));
                if (p == null) { message = "Mark not set"; return null; }
                int pos = clamp(p);
                return m.equals("`") ? new Target(pos, EXCL) : new Target(firstNonBlank(lineStart(pos)), LINE);
            }
        }
        return null;
    }

    private static String swapCase(String t) {
        char ch = t.charAt(0);
        return String.valueOf(Character.isUpperCase(ch) ? Character.toLowerCase(ch) : Character.toUpperCase(ch));
    }

    private Target findChar(String type, String ch, int count, int from, boolean repeat) {
        if (ch == null || ch.isEmpty() || (ch.length() > 1 && ch.startsWith("<"))) return null;
        int ls = lineStart(from), le = lineEnd(from);
        boolean fwd = type.equals("f") || type.equals("t");
        boolean till = type.equals("t") || type.equals("T");
        int idx = -1;
        if (fwd) {
            int start = from + 1;
            if (till && repeat && text.startsWith(ch, from + 1)) start = from + 2;
            for (int i = 0; i < count; i++) {
                idx = text.indexOf(ch, start);
                if (idx < 0 || idx >= le) return null;
                start = idx + 1;
            }
            return new Target(till ? idx - 1 : idx, INCL);
        } else {
            int start = from - 1;
            if (till && repeat && from - 1 >= ls && text.startsWith(ch, from - 1)) start = from - 2;
            for (int i = 0; i < count; i++) {
                if (start < ls) return null;
                idx = text.lastIndexOf(ch, start);
                if (idx < ls) return null;
                start = idx - 1;
            }
            return new Target(till ? idx + ch.length() : idx, EXCL);
        }
    }

    private Target matchPair(int from) {
        String pairs = "()[]{}";
        int le = lineEnd(from), p = from;
        while (p < le && pairs.indexOf(text.charAt(p)) < 0) p++;
        if (p >= le) return null;
        char ch = text.charAt(p);
        int idx = pairs.indexOf(ch);
        char open = pairs.charAt(idx & ~1), close = pairs.charAt(idx | 1);
        int depth = 0;
        if (ch == open) {
            for (int i = p; i < text.length(); i++) {
                char x = text.charAt(i);
                if (x == open) depth++;
                else if (x == close && --depth == 0) return new Target(i, INCL);
            }
        } else {
            for (int i = p; i >= 0; i--) {
                char x = text.charAt(i);
                if (x == close) depth++;
                else if (x == open && --depth == 0) return new Target(i, INCL);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ operators

    private void doOperator(Cmd c, int count) {
        Range r;
        if (c.doubled) {
            int l1 = lineNo(cursor), l2 = Math.min(lastLine(), l1 + count - 1);
            r = lineRange(l1, l2);
        } else if (c.obj != null) {
            r = textObject(c.obj, count);
            if (r == null) return;
        } else {
            String m = c.motion;
            if (m.equals("/") || m.equals("?")) {
                pendingSearchOp = c;
                enterCmdline(m.charAt(0), Mode.NORMAL);
                return;
            }
            Target t;
            if (c.op.equals("c") && (m.equals("w") || m.equals("W")) && cursor < text.length() && !isBlank(cursor)
                    && text.charAt(cursor) != '\n') {
                boolean big = m.equals("W");
                int p = endOfCurrentWord(cursor, big);
                for (int i = 1; i < count; i++) p = wordEnd(p, big);
                t = new Target(p, INCL);
            } else {
                t = motion(c, m, count, c.hasCount(), cursor, true);
                if (t == null) return;
                if ((m.equals("w") || m.equals("W")) && t.pos > cursor) {
                    int nl = text.lastIndexOf('\n', t.pos - 1);
                    if (nl > cursor) t = new Target(nl, EXCL);
                }
            }
            r = rangeFrom(cursor, t);
        }
        applyOperator(c.op, r, c.reg, 1);
    }

    private Range rangeFrom(int from, Target t) {
        int a = Math.min(from, t.pos), b = Math.max(from, t.pos);
        if (t.kind == LINE) return lineRange(lineNo(a), lineNo(b));
        if (t.kind == INCL) b = Math.min(text.length(), next(b));
        return new Range(a, b, false);
    }

    private Range lineRange(int l1, int l2) {
        int s = lineStartOf(l1);
        return new Range(s, lineEnd(lineStartOf(l2)), true);
    }

    private void applyOperator(String op, Range r, char reg, int times) {
        int s = r.start, e = r.end;
        String seg = text.substring(s, e);
        switch (op) {
            case "y":
                writeReg(reg, r.linewise ? seg + "\n" : seg, r.linewise, true);
                if (!r.linewise || cursor < s || cursor > e) cursor = s;
                cursor = clampNormal(cursor);
                break;
            case "d":
                writeReg(reg, r.linewise ? seg + "\n" : seg, r.linewise, false);
                if (r.linewise) deleteLines(s, e);
                else { replace(s, e, ""); cursor = clampNormal(s); }
                break;
            case "c":
                writeReg(reg, r.linewise ? seg + "\n" : seg, r.linewise, false);
                if (r.linewise) {
                    String ind = leadingWs(s);
                    replace(s, e, ind);
                    cursor = s + ind.length();
                } else {
                    replace(s, e, "");
                    cursor = s;
                }
                startInsert(1, Mode.INSERT);
                break;
            case ">": case "<": {
                int l1 = lineNo(s), l2 = lineNo(r.linewise ? e : Math.max(s, e - 1));
                shiftLines(l1, l2, op.equals(">"), times);
                break;
            }
            case "g~": case "gu": case "gU": case "~": case "u": case "U": {
                String t;
                if (op.endsWith("u")) t = seg.toLowerCase();
                else if (op.endsWith("U")) t = seg.toUpperCase();
                else t = toggleCase(seg);
                replace(s, e, t);
                cursor = clampNormal(s);
                break;
            }
        }
    }

    private void deleteLines(int s, int e) {
        int n = text.length();
        if (e < n) { replace(s, e + 1, ""); cursor = clampNormal(firstNonBlank(s)); }
        else if (s > 0) { replace(s - 1, e, ""); cursor = clampNormal(firstNonBlank(lineStart(s - 1))); }
        else { replace(s, e, ""); cursor = 0; }
    }

    private void shiftLines(int l1, int l2, boolean right, int times) {
        int s = lineStartOf(l1), e = lineEnd(lineStartOf(l2));
        String[] lines = text.substring(s, e).split("\n", -1);
        StringBuilder sb = new StringBuilder();
        String unit = "    ";
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            for (int t = 0; t < times; t++) {
                if (right) { if (!ln.isEmpty()) ln = unit + ln; }
                else if (ln.startsWith("\t")) ln = ln.substring(1);
                else { int k = 0; while (k < 4 && k < ln.length() && ln.charAt(k) == ' ') k++; ln = ln.substring(k); }
            }
            if (i > 0) sb.append('\n');
            sb.append(ln);
        }
        replace(s, e, sb.toString());
        cursor = clampNormal(firstNonBlank(s));
    }

    private static String toggleCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            sb.append(Character.isUpperCase(ch) ? Character.toLowerCase(ch)
                    : Character.isLowerCase(ch) ? Character.toUpperCase(ch) : ch);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ actions

    private void doAction(Cmd c, int count) {
        String a = c.action;
        int n = text.length();
        switch (a) {
            case "x": case "<Del>": {
                int le = lineEnd(cursor), e = cursor;
                for (int i = 0; i < count && e < le; i++) e = next(e);
                if (e > cursor) {
                    writeReg(c.reg, text.substring(cursor, e), false, false);
                    replace(cursor, e, "");
                    cursor = clampNormal(cursor);
                }
                break;
            }
            case "X": {
                int ls = lineStart(cursor), s = cursor;
                for (int i = 0; i < count && s > ls; i++) s = prev(s);
                if (s < cursor) {
                    writeReg(c.reg, text.substring(s, cursor), false, false);
                    replace(s, cursor, "");
                    cursor = clampNormal(s);
                }
                break;
            }
            case "s": {
                int le = lineEnd(cursor), e = cursor;
                for (int i = 0; i < count && e < le; i++) e = next(e);
                if (e > cursor) writeReg(c.reg, text.substring(cursor, e), false, false);
                replace(cursor, e, "");
                startInsert(1, Mode.INSERT);
                break;
            }
            case "S": opShortcut("c", null, c, count, true); break;
            case "C": opShortcut("c", "$", c, count, false); break;
            case "D": opShortcut("d", "$", c, count, false); break;
            case "Y": opShortcut("y", null, c, count, true); break;
            case "p": case "P": paste(c.reg, count, a.equals("p")); break;
            case "u": doUndo(count); break;
            case "U": doUndo(1); break;
            case "<C-r>": doRedo(count); break;
            case ".": {
                if (lastChange == null) break;
                List<String> keys = new ArrayList<>(lastChange);
                replayDepth++;
                try {
                    for (int r = 0; r < (c.hasCount() ? count : 1); r++)
                        for (String k : keys) dispatch(k);
                    if (mode == Mode.INSERT || mode == Mode.REPLACE) dispatch("<Esc>");
                } finally { replayDepth--; }
                break;
            }
            case "i": startInsert(count, Mode.INSERT); break;
            case "a":
                if (cursor < n && text.charAt(cursor) != '\n') cursor = next(cursor);
                startInsert(count, Mode.INSERT);
                break;
            case "I": cursor = firstNonBlank(lineStart(cursor)); startInsert(count, Mode.INSERT); break;
            case "gI": cursor = lineStart(cursor); startInsert(count, Mode.INSERT); break;
            case "A": cursor = lineEnd(cursor); startInsert(count, Mode.INSERT); break;
            case "o": {
                int e = lineEnd(cursor);
                String ind = leadingWs(lineStart(cursor));
                replace(e, e, "\n" + ind);
                cursor = e + 1 + ind.length();
                startInsert(1, Mode.INSERT);
                break;
            }
            case "O": {
                int s = lineStart(cursor);
                String ind = leadingWs(s);
                replace(s, s, ind + "\n");
                cursor = s + ind.length();
                startInsert(1, Mode.INSERT);
                break;
            }
            case "R": startInsert(count, Mode.REPLACE); break;
            case "J": join(Math.max(2, count), true); break;
            case "gJ": join(Math.max(2, count), false); break;
            case "~": {
                int le = lineEnd(cursor), e = cursor;
                for (int i = 0; i < count && e < le; i++) e = next(e);
                if (e > cursor) {
                    replace(cursor, e, toggleCase(text.substring(cursor, e)));
                    cursor = clampNormal(e);
                }
                break;
            }
            case "r": {
                String ch = c.arg;
                if (ch.equals("<CR>")) ch = "\n";
                else if (ch.length() > 1 && ch.startsWith("<")) break;
                int le = lineEnd(cursor), e = cursor;
                for (int i = 0; i < count; i++) {
                    if (e >= le) return;
                    e = next(e);
                }
                if (ch.equals("\n")) { replace(cursor, e, "\n"); cursor = cursor + 1; }
                else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < count; i++) sb.append(ch);
                    replace(cursor, e, sb.toString());
                    cursor = clampNormal(cursor + sb.length() - ch.length());
                }
                break;
            }
            case "m":
                if (c.arg.length() == 1) marks.put(c.arg.charAt(0), cursor);
                break;
            case "v": mode = Mode.VISUAL; anchor = cursor; break;
            case "V": mode = Mode.VISUAL_LINE; anchor = cursor; break;
            case "gv":
                if (lastVisAnchor >= 0) {
                    mode = lastVisMode; anchor = clamp(lastVisAnchor); cursor = clamp(lastVisCursor);
                }
                break;
            case ":": case "/": case "?": enterCmdline(a.charAt(0), Mode.NORMAL); break;
            case "<C-a>": increment(count); break;
            case "<C-x>": increment(-count); break;
            case "&":
                if (lastSubPattern != null) {
                    int l = lineNo(cursor);
                    substitute(l, l, lastSubPattern, lastSubReplacement, "");
                }
                break;
            case "ZZ": host.performEditorAction(); host.hideKeyboard(); break;
            case "ZQ": host.hideKeyboard(); break;
            case "gu": case "gU": case "g~": break;
            default: break; // zz, zt, unknown g-commands: no-op
        }
    }

    private void opShortcut(String op, String motion, Cmd orig, int count, boolean doubled) {
        Cmd c = new Cmd();
        c.op = op; c.motion = motion; c.reg = orig.reg; c.doubled = doubled;
        c.count = orig.count; c.count2 = orig.count2;
        doOperator(c, count);
    }

    private void paste(char reg, int count, boolean after) {
        Reg r = readReg(reg);
        if (r == null || r.text.isEmpty()) { message = "Nothing in register"; return; }
        count = Math.min(count, 10000);
        StringBuilder sb = new StringBuilder();
        if (r.linewise) {
            String body = r.text.endsWith("\n") ? r.text : r.text + "\n";
            for (int i = 0; i < count; i++) sb.append(body);
            String rep = sb.toString();
            if (after) {
                int e = lineEnd(cursor);
                if (e < text.length()) { replace(e + 1, e + 1, rep); cursor = firstNonBlank(e + 1); }
                else { replace(e, e, "\n" + rep.substring(0, rep.length() - 1)); cursor = firstNonBlank(e + 1); }
            } else {
                int s = lineStart(cursor);
                replace(s, s, rep);
                cursor = firstNonBlank(s);
            }
            cursor = clampNormal(cursor);
        } else {
            for (int i = 0; i < count; i++) sb.append(r.text);
            String rep = sb.toString();
            int pos = cursor;
            if (after && cursor < text.length() && text.charAt(cursor) != '\n') pos = next(cursor);
            replace(pos, pos, rep);
            cursor = clampNormal(rep.contains("\n") ? pos : pos + rep.length() - 1);
        }
    }

    private void join(int lines, boolean spaces) {
        for (int i = 0; i < lines - 1; i++) {
            int e = lineEnd(cursor);
            if (e >= text.length()) break;
            int w = e + 1;
            if (spaces) while (w < text.length() && (text.charAt(w) == ' ' || text.charAt(w) == '\t')) w++;
            String ins = "";
            if (spaces) {
                boolean nextEmpty = w >= text.length() || text.charAt(w) == '\n';
                boolean endsWs = e > lineStart(e) && isBlank(e - 1);
                boolean closeParen = w < text.length() && text.charAt(w) == ')';
                if (!nextEmpty && !endsWs && !closeParen && e > lineStart(e)) ins = " ";
            }
            replace(e, w, ins);
            cursor = e;
        }
        cursor = clampNormal(cursor);
    }

    private void increment(int delta) {
        int ls = lineStart(cursor), le = lineEnd(cursor);
        int p = cursor;
        if (!(p < le && Character.isDigit(text.charAt(p)))) {
            while (p < le && !Character.isDigit(text.charAt(p))) p++;
            if (p >= le) return;
        }
        int s = p;
        while (s > ls && Character.isDigit(text.charAt(s - 1))) s--;
        int e = p;
        while (e < le && Character.isDigit(text.charAt(e))) e++;
        if (s > ls && text.charAt(s - 1) == '-') s--;
        try {
            long v = Long.parseLong(text.substring(s, e)) + delta;
            String rep = Long.toString(v);
            replace(s, e, rep);
            cursor = s + rep.length() - 1;
        } catch (NumberFormatException ignored) { }
    }

    // ------------------------------------------------------------------ text objects

    private Range textObject(String obj, int count) {
        boolean inner = obj.charAt(0) == 'i';
        char k = obj.charAt(1);
        switch (k) {
            case 'w': return wordObject(inner, false);
            case 'W': return wordObject(inner, true);
            case 's': return sentenceObject(inner);
            case 'p': return paragraphObject(inner);
            case '"': case '\'': case '`': return quoteObject(k, inner);
            case '(': case ')': case 'b': return bracketObject('(', ')', inner, count);
            case '{': case '}': case 'B': return bracketObject('{', '}', inner, count);
            case '[': case ']': return bracketObject('[', ']', inner, count);
            case '<': case '>': return bracketObject('<', '>', inner, count);
        }
        return null;
    }

    private Range wordObject(boolean inner, boolean big) {
        int n = text.length();
        if (n == 0) return null;
        int p = Math.min(cursor, n - 1);
        int ls = lineStart(p), le = lineEnd(p);
        if (ls == le) return null;
        if (p >= le) p = le - 1;
        int c = cls(p, big);
        int s = p, e = p + 1;
        while (s > ls && cls(s - 1, big) == c) s--;
        while (e < le && cls(e, big) == c) e++;
        if (!inner) {
            if (c != 0) {
                if (e < le && isBlank(e)) { while (e < le && isBlank(e)) e++; }
                else { while (s > ls && isBlank(s - 1)) s--; }
            } else if (e < le) {
                int c2 = cls(e, big);
                while (e < le && cls(e, big) == c2) e++;
            }
        }
        return new Range(s, e, false);
    }

    private Range sentenceObject(boolean inner) {
        int n = text.length();
        if (n == 0) return null;
        int p = Math.min(cursor, n - 1);
        int s = p;
        while (s > 0) {
            char ch = text.charAt(s - 1);
            if ((ch == '.' || ch == '!' || ch == '?') && s < n && Character.isWhitespace(text.charAt(s))) break;
            if (ch == '\n' && s - 2 >= 0 && text.charAt(s - 2) == '\n') break;
            s--;
        }
        while (s < p && Character.isWhitespace(text.charAt(s))) s++;
        int e = p;
        while (e < n) {
            char ch = text.charAt(e);
            if ((ch == '.' || ch == '!' || ch == '?') && (e + 1 >= n || Character.isWhitespace(text.charAt(e + 1)))) { e++; break; }
            if (ch == '\n' && e + 1 < n && text.charAt(e + 1) == '\n') break;
            e++;
        }
        if (!inner) while (e < n && (text.charAt(e) == ' ' || text.charAt(e) == '\t')) e++;
        return new Range(s, e, false);
    }

    private Range paragraphObject(boolean inner) {
        int l = lineNo(cursor), last = lastLine();
        boolean blank = isEmptyLine(l);
        int l1 = l, l2 = l;
        while (l1 > 0 && isEmptyLine(l1 - 1) == blank) l1--;
        while (l2 < last && isEmptyLine(l2 + 1) == blank) l2++;
        if (!inner && l2 < last) {
            boolean b2 = isEmptyLine(l2 + 1);
            while (l2 < last && isEmptyLine(l2 + 1) == b2) l2++;
        }
        return lineRange(l1, l2);
    }

    private Range quoteObject(char q, boolean inner) {
        int ls = lineStart(cursor), le = lineEnd(cursor);
        List<Integer> qs = new ArrayList<>();
        for (int i = ls; i < le; i++)
            if (text.charAt(i) == q && (i == ls || text.charAt(i - 1) != '\\')) qs.add(i);
        int s = -1, e = -1;
        for (int i = 0; i + 1 < qs.size(); i += 2) {
            int a = qs.get(i), b = qs.get(i + 1);
            if (a <= cursor && cursor <= b) { s = a; e = b; break; }
        }
        if (s < 0) for (int i = 0; i + 1 < qs.size(); i += 2) {
            if (qs.get(i) > cursor) { s = qs.get(i); e = qs.get(i + 1); break; }
        }
        if (s < 0) return null;
        if (inner) return new Range(s + 1, e, false);
        int end = e + 1, start = s;
        if (end < le && isBlank(end)) { while (end < le && isBlank(end)) end++; }
        else { while (start > ls && isBlank(start - 1)) start--; }
        return new Range(start, end, false);
    }

    private Range bracketObject(char open, char close, boolean inner, int count) {
        int n = text.length();
        if (n == 0) return null;
        int p = Math.min(cursor, n - 1);
        int o = -1;
        int searchFrom;
        if (text.charAt(p) == open) { o = p; searchFrom = -1; }
        else searchFrom = text.charAt(p) == close ? p - 1 : p;
        for (int level = 0; level < count; level++) {
            if (level > 0 || o < 0) {
                int from = level == 0 ? searchFrom : o - 1;
                o = -1;
                int depth = 0;
                for (int i = from; i >= 0; i--) {
                    char ch = text.charAt(i);
                    if (ch == close) depth++;
                    else if (ch == open) { if (depth == 0) { o = i; break; } depth--; }
                }
                if (o < 0) return null;
            }
        }
        int cl = -1, depth = 0;
        for (int i = o + 1; i < n; i++) {
            char ch = text.charAt(i);
            if (ch == open) depth++;
            else if (ch == close) { if (depth == 0) { cl = i; break; } depth--; }
        }
        if (cl < 0) return null;
        if (!inner) return new Range(o, cl + 1, false);
        int s = o + 1, e = cl;
        if (s < e && text.charAt(s) == '\n') {
            s++;
            int ls = lineStart(e);
            if (ls > s && text.substring(ls, e).trim().isEmpty()) e = ls;
        }
        return new Range(s, Math.max(s, e), false);
    }

    // ------------------------------------------------------------------ visual mode

    private void visualKey(String key) {
        if (isEscape(key)) {
            if (!pending.isEmpty()) { pending.clear(); return; }
            exitVisual();
            return;
        }
        pending.add(key);
        Cmd c = parse(pending, true);
        if (c == INCOMPLETE) return;
        pending.clear();
        if (c == INVALID) return;
        beginChange();
        execVisual(c);
        if (mode != Mode.INSERT && mode != Mode.REPLACE) endChange();
    }

    private Range visualRange() {
        if (mode == Mode.VISUAL_LINE) return lineRange(lineNo(Math.min(anchor, cursor)), lineNo(Math.max(anchor, cursor)));
        int a = Math.min(anchor, cursor), b = Math.min(text.length(), next(Math.max(anchor, cursor)));
        return new Range(a, b, false);
    }

    private void saveVisual() {
        lastVisAnchor = anchor; lastVisCursor = cursor; lastVisMode = mode;
        Range r = visualRange();
        marks.put('<', r.start); marks.put('>', Math.max(r.start, r.end - 1));
    }

    private void exitVisual() {
        saveVisual();
        mode = Mode.NORMAL;
        cursor = clampNormal(cursor);
    }

    private void execVisual(Cmd c) {
        int count = c.total();
        if (c.motion != null) {
            Target t = motion(c, c.motion, count, c.hasCount(), cursor, false);
            if (t != null) {
                cursor = clamp(t.pos);
                if (cursor == text.length() && cursor > 0 && text.charAt(cursor - 1) != '\n') cursor = prev(cursor);
                if (c.motion.equals("$")) desiredCol = Integer.MAX_VALUE;
                else if (!isVertical(c.motion)) desiredCol = col(cursor);
            }
            return;
        }
        if (c.obj != null) {
            Range r = textObject(c.obj, count);
            if (r != null && r.end > r.start) {
                if (r.linewise) mode = Mode.VISUAL_LINE;
                anchor = r.start; cursor = r.linewise ? r.end : prev(r.end);
            }
            return;
        }
        String a = c.action;
        if (a == null) return;
        Range r = visualRange();
        char reg = c.reg;
        switch (a) {
            case "o": { int t = anchor; anchor = cursor; cursor = t; return; }
            case "v": if (mode == Mode.VISUAL) exitVisual(); else mode = Mode.VISUAL; return;
            case "V": if (mode == Mode.VISUAL_LINE) exitVisual(); else mode = Mode.VISUAL_LINE; return;
            case ":": saveVisual(); enterCmdline(':', mode); return;
            case "/": case "?": enterCmdline(a.charAt(0), mode); return;
        }
        saveVisual();
        mode = Mode.NORMAL;
        switch (a) {
            case "d": case "x": case "<Del>": applyOperator("d", r, reg, 1); break;
            case "X": case "D": applyOperator("d", toLines(r), reg, 1); break;
            case "y": applyOperator("y", r, reg, 1); break;
            case "Y": applyOperator("y", toLines(r), reg, 1); break;
            case "c": case "s": applyOperator("c", r, reg, 1); break;
            case "C": case "S": case "R": applyOperator("c", toLines(r), reg, 1); break;
            case ">": case "<": applyOperator(a, r, reg, count); break;
            case "~": case "g~": applyOperator("g~", r, reg, 1); break;
            case "u": case "gu": applyOperator("gu", r, reg, 1); break;
            case "U": case "gU": applyOperator("gU", r, reg, 1); break;
            case "r": {
                String ch = c.arg;
                if (ch.length() > 1 && ch.startsWith("<")) break;
                StringBuilder sb = new StringBuilder();
                String seg = text.substring(r.start, r.end);
                for (int i = 0; i < seg.length(); ) {
                    int cp = seg.codePointAt(i);
                    if (cp == '\n') sb.append('\n'); else sb.append(ch);
                    i += Character.charCount(cp);
                }
                replace(r.start, r.end, sb.toString());
                cursor = clampNormal(r.start);
                break;
            }
            case "J": case "gJ": {
                cursor = r.start;
                int lines = lineNo(Math.max(r.start, r.end - 1)) - lineNo(r.start) + 1;
                join(Math.max(2, lines), a.equals("J"));
                break;
            }
            case "p": case "P": {
                Reg rg = readReg(reg);
                if (rg == null) { message = "Nothing in register"; break; }
                if (r.linewise) {
                    String body = rg.text.endsWith("\n") ? rg.text.substring(0, rg.text.length() - 1) : rg.text;
                    replace(r.start, r.end, body);
                } else {
                    String body = rg.linewise ? "\n" + (rg.text.endsWith("\n") ? rg.text.substring(0, rg.text.length() - 1) : rg.text) + "\n" : rg.text;
                    replace(r.start, r.end, body);
                    if (!rg.linewise && !body.isEmpty()) { cursor = clampNormal(r.start + body.length() - 1); break; }
                }
                cursor = clampNormal(r.start);
                break;
            }
            case "I": cursor = r.linewise ? firstNonBlank(r.start) : r.start; startInsert(1, Mode.INSERT); break;
            case "A": cursor = r.end; startInsert(1, Mode.INSERT); break;
            case "<C-a>": case "<C-x>": {
                int sign = a.equals("<C-a>") ? 1 : -1;
                int l1 = lineNo(r.start), l2 = lineNo(Math.max(r.start, r.end - 1));
                for (int l = l1; l <= l2; l++) { cursor = lineStartOf(l); increment(sign * count); }
                cursor = clampNormal(lineStartOf(l1));
                break;
            }
            default:
                cursor = clampNormal(cursor);
        }
    }

    private Range toLines(Range r) {
        if (r.linewise) return r;
        return lineRange(lineNo(r.start), lineNo(Math.max(r.start, r.end - 1)));
    }

    // ------------------------------------------------------------------ command line

    // History of ':' commands and '/' '?' searches, oldest first (like vim's).
    private static final int HISTORY_MAX = 100;
    private final List<String> exHistory = new ArrayList<>(), searchHistory = new ArrayList<>();
    private int histIndex;
    private String histPrefix;

    public List<String> getHistory(boolean search) { return new ArrayList<>(search ? searchHistory : exHistory); }

    public void setHistory(boolean search, List<String> items) {
        List<String> h = search ? searchHistory : exHistory;
        h.clear();
        for (String s : items) if (s != null && !s.isEmpty()) h.add(s);
        while (h.size() > HISTORY_MAX) h.remove(0);
    }

    private List<String> history() { return cmdType == ':' ? exHistory : searchHistory; }

    private void remember(char type, String s) {
        if (s.isEmpty() || replayDepth > 0) return;
        List<String> h = type == ':' ? exHistory : searchHistory;
        h.remove(s);
        h.add(s);
        while (h.size() > HISTORY_MAX) h.remove(0);
    }

    /** Up/Down in the command line: older/newer entries that start with what was typed. */
    private void browseHistory(boolean older) {
        List<String> h = history();
        if (histPrefix == null) { histPrefix = cmdline.toString(); histIndex = h.size(); }
        int i = histIndex;
        while (true) {
            i += older ? -1 : 1;
            if (i < 0) { message = h.isEmpty() ? "No history" : "Oldest in history"; return; }
            if (i >= h.size()) { histIndex = h.size(); cmdline.setLength(0); cmdline.append(histPrefix); return; }
            if (h.get(i).startsWith(histPrefix)) break;
        }
        histIndex = i;
        cmdline.setLength(0);
        cmdline.append(h.get(i));
    }

    private void enterCmdline(char type, Mode returnMode) {
        histPrefix = null;
        cmdType = type;
        cmdline.setLength(0);
        cmdReturnMode = returnMode;
        cmdVisual = (returnMode == Mode.VISUAL || returnMode == Mode.VISUAL_LINE) ? visualRange() : null;
        if (type == ':' && cmdVisual != null) cmdline.append("'<,'>");
        mode = Mode.CMDLINE;
    }

    private void leaveCmdline() {
        pendingSearchOp = null;
        if (cmdType == ':' || cmdReturnMode == Mode.NORMAL) {
            mode = Mode.NORMAL;
            cursor = clampNormal(cursor);
        } else {
            mode = cmdReturnMode;
        }
    }

    private void cmdKey(String key) {
        if (isEscape(key)) { leaveCmdline(); return; }
        if (key.equals("<Up>") || key.equals("<Down>")) { browseHistory(key.equals("<Up>")); return; }
        histPrefix = null;
        switch (key) {
            case "<BS>":
                if (cmdline.length() == 0) { leaveCmdline(); return; }
                cmdline.setLength(cmdline.length() - 1);
                return;
            case "<C-u>": cmdline.setLength(0); return;
            case "<C-w>": {
                int i = cmdline.length();
                while (i > 0 && cmdline.charAt(i - 1) == ' ') i--;
                while (i > 0 && cmdline.charAt(i - 1) != ' ') i--;
                cmdline.setLength(i);
                return;
            }
            case "<CR>": {
                String s = cmdline.toString();
                char type = cmdType;
                Range vis = cmdVisual;
                Cmd op = pendingSearchOp;
                remember(type, s);
                leaveCmdline();
                beginChange();
                if (op != null) searchOperator(op, s, type == '/');
                else if (type == ':') execEx(s, vis);
                else search(s, type == '/');
                if (mode != Mode.INSERT && mode != Mode.REPLACE) endChange();
                return;
            }
            default:
                if (key.length() > 1 && key.startsWith("<")) return;
                cmdline.append(key);
        }
    }

    private void searchOperator(Cmd op, String pat, boolean forward) {
        if (pat.isEmpty()) pat = lastSearch;
        if (pat == null) { message = "No previous search"; return; }
        lastSearch = pat;
        lastSearchForward = forward;
        int p = findMatch(pat, forward, cursor, op.total());
        if (p < 0) { message = "Pattern not found: " + pat; return; }
        if (replayDepth == 0 && !op.op.equals("y") && searchOpKeys != null) {
            List<String> rec = new ArrayList<>(searchOpKeys);
            for (int i = 0; i < pat.length(); i++) rec.add(String.valueOf(pat.charAt(i)));
            rec.add("<CR>");
            recording = rec;
        }
        applyOperator(op.op, rangeFrom(cursor, new Target(p, EXCL)), op.reg, 1);
        if (mode != Mode.INSERT && mode != Mode.REPLACE && recording != null && replayDepth == 0) {
            lastChange = recording; recording = null;
        }
    }

    private void search(String pat, boolean forward) {
        if (pat.isEmpty()) pat = lastSearch;
        if (pat == null) { message = "No previous search"; return; }
        lastSearch = pat;
        lastSearchForward = forward;
        int p = findMatch(pat, forward, cursor, 1);
        if (p < 0) { message = "Pattern not found: " + pat; return; }
        marks.put('\'', cursor);
        cursor = clampNormal(p);
        desiredCol = col(cursor);
    }

    static Pattern compileSearch(String pat, Boolean ignoreCase) {
        boolean ic;
        String p = pat;
        if (p.contains("\\c")) { ic = true; p = p.replace("\\c", ""); }
        else if (p.contains("\\C")) { ic = false; p = p.replace("\\C", ""); }
        else if (ignoreCase != null) ic = ignoreCase;
        else ic = p.equals(p.toLowerCase());  // smartcase
        String rx = p.contains("\\(") || p.contains("\\|") || p.contains("\\+") ? vimMagicToJava(p) : p;
        rx = rx.replace("\\<", "\\b").replace("\\>", "\\b");
        int flags = ic ? (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : 0;
        try {
            return Pattern.compile(rx, flags | Pattern.MULTILINE);
        } catch (PatternSyntaxException e) {
            return Pattern.compile(Pattern.quote(p), flags);
        }
    }

    /** Translates vim "magic" regex syntax (\( \) \| \+ \? \{) to Java syntax. */
    static String vimMagicToJava(String p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            if (ch == '\\' && i + 1 < p.length()) {
                char nx = p.charAt(++i);
                if ("()|+?{}".indexOf(nx) >= 0) sb.append(nx);
                else if (nx == '=') sb.append('?');
                else sb.append('\\').append(nx);
            } else if ("()|+?{}".indexOf(ch) >= 0) {
                sb.append('\\').append(ch);
            } else sb.append(ch);
        }
        return sb.toString();
    }

    private int findMatch(String pat, boolean forward, int from, int count) {
        Pattern p = compileSearch(pat, null);
        Matcher m = p.matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            if (m.end() == m.start() && m.end() >= text.length()) break;
            if (starts.size() > 100000) break;
        }
        if (starts.isEmpty()) return -1;
        int pos = from;
        for (int c = 0; c < count; c++) {
            int found = -1;
            if (forward) {
                for (int s : starts) if (s > pos) { found = s; break; }
                if (found < 0) { found = starts.get(0); message = "search hit BOTTOM, continuing at TOP"; }
            } else {
                for (int i = starts.size() - 1; i >= 0; i--) if (starts.get(i) < pos) { found = starts.get(i); break; }
                if (found < 0) { found = starts.get(starts.size() - 1); message = "search hit TOP, continuing at BOTTOM"; }
            }
            pos = found;
        }
        return pos;
    }

    private int[] parseAddr(String s, int[] idx) {
        // returns {line} or null; idx[0] is advanced
        int i = idx[0];
        if (i >= s.length()) return null;
        char ch = s.charAt(i);
        int line;
        if (ch == '.') { line = lineNo(cursor); i++; }
        else if (ch == '$') { line = lastLine(); i++; }
        else if (Character.isDigit(ch)) {
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
            try { line = Integer.parseInt(s.substring(i, j)) - 1; } catch (NumberFormatException e) { line = lastLine(); }
            i = j;
        } else if (ch == '+' || ch == '-') {
            line = lineNo(cursor);
        } else return null;
        while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            int sign = s.charAt(i) == '+' ? 1 : -1;
            i++;
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
            int amt = j > i ? Integer.parseInt(s.substring(i, Math.min(j, i + 6))) : 1;
            line += sign * amt;
            i = j;
        }
        idx[0] = i;
        return new int[]{Math.max(0, Math.min(lastLine(), line))};
    }

    private void execEx(String s, Range vis) {
        s = s.trim();
        while (s.startsWith(":")) s = s.substring(1).trim();
        int l1 = -1, l2 = -1;
        boolean hasRange = false;
        if (s.startsWith("%")) { l1 = 0; l2 = lastLine(); s = s.substring(1); hasRange = true; }
        else if (s.startsWith("'<,'>")) {
            if (vis != null) { l1 = lineNo(vis.start); l2 = lineNo(Math.max(vis.start, vis.end - 1)); }
            else { l1 = l2 = lineNo(cursor); }
            s = s.substring(5); hasRange = true;
        } else {
            int[] idx = {0};
            int[] a = parseAddr(s, idx);
            if (a != null) {
                l1 = l2 = a[0]; hasRange = true;
                if (idx[0] < s.length() && (s.charAt(idx[0]) == ',' || s.charAt(idx[0]) == ';')) {
                    idx[0]++;
                    int[] b = parseAddr(s, idx);
                    if (b != null) l2 = b[0];
                }
                s = s.substring(idx[0]);
            }
        }
        if (l1 > l2) { int t = l1; l1 = l2; l2 = t; }
        String cmd = s.trim();
        int cur = lineNo(cursor);
        if (!hasRange) { l1 = l2 = cur; }

        if (cmd.isEmpty()) {
            if (hasRange) { marks.put('\'', cursor); cursor = clampNormal(firstNonBlank(lineStartOf(l2))); }
            return;
        }
        if (cmd.matches("s(ubstitute)?([^A-Za-z0-9\\s].*)?") || cmd.equals("s")) {
            String rest = cmd.startsWith("substitute") ? cmd.substring(10) : cmd.substring(1);
            if (rest.isEmpty()) {
                if (lastSubPattern == null) { message = "No previous substitute"; return; }
                substitute(l1, l2, lastSubPattern, lastSubReplacement, lastSubFlags);
                return;
            }
            char delim = rest.charAt(0);
            List<String> parts = splitUnescaped(rest.substring(1), delim);
            String pat = parts.size() > 0 ? parts.get(0) : "";
            String rep = parts.size() > 1 ? parts.get(1) : "";
            String flags = parts.size() > 2 ? parts.get(2).trim() : "";
            if (pat.isEmpty()) pat = lastSearch != null ? lastSearch : lastSubPattern;
            if (pat == null) { message = "No previous regular expression"; return; }
            lastSubPattern = pat; lastSubReplacement = rep; lastSubFlags = flags;
            lastSearch = pat;
            substitute(l1, l2, pat, rep, flags);
            return;
        }
        if (cmd.matches("(g|global|v|vglobal)!?([^A-Za-z0-9\\s].*)")) {
            boolean invert = cmd.startsWith("v") || cmd.matches("g(lobal)?!.*");
            String rest = cmd.replaceFirst("^(g|global|v|vglobal)!?", "");
            char delim = rest.charAt(0);
            List<String> parts = splitUnescaped(rest.substring(1), delim);
            String pat = parts.size() > 0 ? parts.get(0) : "";
            String sub = parts.size() > 1 ? parts.get(1).trim() : "p";
            if (!hasRange) { l1 = 0; l2 = lastLine(); }
            if (!(sub.equals("d") || sub.equals("delete"))) { message = "Only :g/pat/d is supported"; return; }
            Pattern p = compileSearch(pat, null);
            int s0 = lineStartOf(l1), e0 = lineEnd(lineStartOf(l2));
            String[] lines = text.substring(s0, e0).split("\n", -1);
            List<String> keep = new ArrayList<>();
            int removed = 0;
            for (String ln : lines) {
                boolean match = p.matcher(ln).find();
                if (match == invert) keep.add(ln); else removed++;
            }
            if (removed == 0) { message = "Pattern not found: " + pat; return; }
            String joined = join("\n", keep);
            if (keep.isEmpty()) deleteLines(s0, e0);
            else { replace(s0, e0, joined); cursor = clampNormal(firstNonBlank(s0)); }
            message = removed + " fewer lines";
            return;
        }
        switch (cmd) {
            case "d": case "de": case "del": case "delete": {
                Range r = lineRange(l1, l2);
                writeReg('"', text.substring(r.start, r.end) + "\n", true, false);
                deleteLines(r.start, r.end);
                return;
            }
            case "y": case "ya": case "yank": {
                Range r = lineRange(l1, l2);
                writeReg('"', text.substring(r.start, r.end) + "\n", true, true);
                message = (l2 - l1 + 1) + " lines yanked";
                return;
            }
            case "j": case "join": {
                cursor = lineStartOf(l1);
                join(Math.max(2, l2 - l1 + 1), true);
                return;
            }
            case ">": case "<":
                shiftLines(l1, l2, cmd.equals(">"), 1);
                return;
            case "sort": case "sort!": case "sort i": case "sort! i": case "sort u": case "sort n": {
                if (!hasRange) { l1 = 0; l2 = lastLine(); }
                int s0 = lineStartOf(l1), e0 = lineEnd(lineStartOf(l2));
                List<String> lines = new ArrayList<>(Arrays.asList(text.substring(s0, e0).split("\n", -1)));
                if (cmd.endsWith(" i")) Collections.sort(lines, String.CASE_INSENSITIVE_ORDER);
                else if (cmd.endsWith(" n")) Collections.sort(lines, new java.util.Comparator<String>() {
                    @Override public int compare(String a, String b) { return Long.compare(leadingNumber(a), leadingNumber(b)); }
                });
                else Collections.sort(lines);
                if (cmd.startsWith("sort!")) Collections.reverse(lines);
                if (cmd.endsWith(" u")) {
                    List<String> u = new ArrayList<>();
                    for (String ln : lines) if (u.isEmpty() || !u.get(u.size() - 1).equals(ln)) u.add(ln);
                    lines = u;
                }
                replace(s0, e0, join("\n", lines));
                cursor = clampNormal(s0);
                return;
            }
            case "w": case "w!": case "write": case "up": case "update":
                host.performEditorAction();
                return;
            case "wq": case "wq!": case "x": case "x!": case "xit": case "exit":
                host.performEditorAction();
                host.hideKeyboard();
                return;
            case "q": case "q!": case "quit": case "qa": case "qa!": case "close":
                host.hideKeyboard();
                return;
            case "u": case "undo": doUndo(1); return;
            case "red": case "redo": doRedo(1); return;
            case "noh": case "nohlsearch": case "nohl": return;
            case "&": case "&&":
                if (lastSubPattern != null) substitute(l1, l2, lastSubPattern, lastSubReplacement, cmd.equals("&&") ? lastSubFlags : "");
                return;
        }
        message = "E492: Not an editor command: " + cmd;
    }

    private static long leadingNumber(String s) {
        Matcher m = Pattern.compile("-?\\d+").matcher(s);
        if (!m.find()) return Long.MIN_VALUE;
        try { return Long.parseLong(m.group()); } catch (NumberFormatException e) { return Long.MAX_VALUE; }
    }

    private static String join(String sep, List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) { if (i > 0) sb.append(sep); sb.append(parts.get(i)); }
        return sb.toString();
    }

    private static List<String> splitUnescaped(String s, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length() && s.charAt(i + 1) == delim) { cur.append(delim); i++; }
            else if (ch == '\\' && i + 1 < s.length()) { cur.append(ch).append(s.charAt(i + 1)); i++; }
            else if (ch == delim) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    /** Converts a vim replacement string (&, \1, \n, \t) into a Java Matcher replacement. */
    static String vimReplacement(String rep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rep.length(); i++) {
            char ch = rep.charAt(i);
            if (ch == '\\' && i + 1 < rep.length()) {
                char nx = rep.charAt(++i);
                if (Character.isDigit(nx)) sb.append('$').append(nx);
                else if (nx == 'n' || nx == 'r') sb.append('\n');
                else if (nx == 't') sb.append('\t');
                else if (nx == '\\') sb.append("\\\\");
                else if (nx == '$') sb.append("\\$");
                else sb.append(nx);
            } else if (ch == '&') sb.append("$0");
            else if (ch == '$') sb.append("\\$");
            else sb.append(ch);
        }
        return sb.toString();
    }

    private void substitute(int l1, int l2, String pat, String rep, String flags) {
        boolean global = flags.contains("g");
        Boolean ic = flags.contains("i") ? Boolean.TRUE : flags.contains("I") ? Boolean.FALSE : null;
        Pattern p = compileSearch(pat, ic);
        String jrep = vimReplacement(rep);
        int s0 = lineStartOf(l1), e0 = lineEnd(lineStartOf(l2));
        String[] lines = text.substring(s0, e0).split("\n", -1);
        int subs = 0, lastChanged = -1;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = p.matcher(lines[i]);
            StringBuffer sb = new StringBuffer();
            int c = 0;
            while (m.find()) {
                try { m.appendReplacement(sb, jrep); } catch (RuntimeException ex) { m.appendReplacement(sb, Matcher.quoteReplacement(rep)); }
                c++;
                if (!global) break;
            }
            m.appendTail(sb);
            if (c > 0) { subs += c; lastChanged = l1 + i; }
            if (i > 0) out.append('\n');
            out.append(sb);
        }
        if (subs == 0) { message = "Pattern not found: " + pat; return; }
        replace(s0, e0, out.toString());
        cursor = clampNormal(firstNonBlank(lineStartOf(Math.min(lastLine(), lastChanged))));
        if (subs > 1) message = subs + " substitutions";
    }

    // ------------------------------------------------------------------ text helpers

    private int clamp(int p) { return Math.max(0, Math.min(p, text.length())); }

    private static final char COENG = '\u17D2';

    /** True for characters that attach to the previous one (combining marks, joiners). */
    private static boolean isAttached(char ch) {
        int t = Character.getType(ch);
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK
                || t == Character.ENCLOSING_MARK || ch == '\u200D' || ch == '\u200C';
    }

    /** Start of the next character cluster: a base plus combining marks / Khmer subscripts. */
    private int next(int p) {
        int n = text.length();
        if (p >= n) return n;
        if (text.charAt(p) == '\n') return p + 1;
        boolean afterCoeng = false;
        if (Character.isHighSurrogate(text.charAt(p)) && p + 1 < n && Character.isLowSurrogate(text.charAt(p + 1))) p += 2;
        else { afterCoeng = text.charAt(p) == COENG; p++; }
        while (p < n && text.charAt(p) != '\n' && (afterCoeng || isAttached(text.charAt(p)))) {
            afterCoeng = text.charAt(p) == COENG;
            p++;
        }
        return p;
    }

    private int prev(int p) {
        if (p <= 0) return 0;
        if (p > text.length()) p = text.length();
        p--;
        if (text.charAt(p) == '\n') return p;
        if (Character.isLowSurrogate(text.charAt(p)) && p > 0 && Character.isHighSurrogate(text.charAt(p - 1))) p--;
        while (p > 0 && text.charAt(p - 1) != '\n'
                && (isAttached(text.charAt(p)) || text.charAt(p - 1) == COENG)) p--;
        return p;
    }

    private int lineStart(int p) { return text.lastIndexOf('\n', Math.min(p, text.length()) - 1) + 1; }

    private int lineEnd(int p) {
        int i = text.indexOf('\n', Math.max(0, p));
        return i < 0 ? text.length() : i;
    }

    private int lineNo(int p) {
        int c = 0, lim = Math.min(p, text.length());
        for (int i = 0; i < lim; i++) if (text.charAt(i) == '\n') c++;
        return c;
    }

    private int lastLine() { return lineNo(text.length()); }

    private int lineStartOf(int l) {
        if (l <= 0) return 0;
        int c = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n' && ++c == l) return i + 1;
        return lineStart(text.length());
    }

    private int col(int p) { return p - lineStart(p); }

    private boolean isEmptyLine(int l) {
        int s = lineStartOf(l);
        return lineEnd(s) == s;
    }

    private boolean isBlank(int i) {
        char ch = text.charAt(i);
        return ch == ' ' || ch == '\t';
    }

    private int firstNonBlank(int ls) {
        int le = lineEnd(ls), p = ls;
        while (p < le && isBlank(p)) p++;
        return p;
    }

    private String leadingWs(int ls) { return text.substring(ls, firstNonBlank(ls)); }

    private int posInLine(int l, int column) {
        int ls = lineStartOf(l), le = lineEnd(ls);
        if (le == ls) return ls;
        long target = (long) ls + column;
        return (int) Math.min(target, prev(le));
    }

    private int clampNormal(int p) {
        int n = text.length();
        p = Math.max(0, Math.min(p, n));
        int ls = lineStart(p), le = lineEnd(p);
        if (le > ls && p >= le) p = prev(le);
        return p;
    }

    private int cls(int i, boolean big) {
        char ch = text.charAt(i);
        if (Character.isWhitespace(ch) || ch == '\u200B') return 0;  // zero-width space separates Khmer words
        if (big) return 1;
        if (ch == '\u17D4' || ch == '\u17D5') return 2;  // Khmer full stops
        if (Character.isLetterOrDigit(ch) || ch == '_' || Character.isSurrogate(ch) || isAttached(ch) || ch > 0x2000) return 1;
        return 2;
    }

    private boolean emptyLineAt(int p) {
        return p < text.length() && text.charAt(p) == '\n' && (p == 0 || text.charAt(p - 1) == '\n');
    }

    private int nextWordStart(int p, boolean big) {
        int n = text.length();
        if (p >= n) return n;
        int c = cls(p, big);
        if (c != 0) while (p < n && cls(p, big) == c) p++;
        while (p < n && cls(p, big) == 0) {
            if (text.charAt(p) == '\n') {
                p++;
                if (p < n && text.charAt(p) == '\n') return p;
                continue;
            }
            p++;
        }
        return p;
    }

    private int wordEnd(int p, boolean big) {
        int n = text.length();
        if (n == 0) return 0;
        p++;
        while (p < n && cls(p, big) == 0) p++;
        if (p >= n) return n - 1;
        int c = cls(p, big);
        while (p + 1 < n && cls(p + 1, big) == c) p++;
        return p;
    }

    private int endOfCurrentWord(int p, boolean big) {
        int n = text.length(), c = cls(p, big);
        while (p + 1 < n && cls(p + 1, big) == c) p++;
        return p;
    }

    private int prevWordStart(int p, boolean big) {
        if (p <= 0) return 0;
        p--;
        while (p > 0 && cls(p, big) == 0) {
            if (emptyLineAt(p)) return p;
            p--;
        }
        if (cls(p, big) == 0) return p;
        int c = cls(p, big);
        while (p > 0 && cls(p - 1, big) == c) p--;
        return p;
    }

    private int prevWordEnd(int p, boolean big) {
        int n = text.length();
        if (p <= 0 || n == 0) return 0;
        p = Math.min(p, n - 1);
        int c = cls(p, big);
        if (c != 0) while (p > 0 && cls(p, big) == c) p--;
        while (p > 0 && cls(p, big) == 0) {
            if (emptyLineAt(p)) return p;
            p--;
        }
        return p;
    }
}
