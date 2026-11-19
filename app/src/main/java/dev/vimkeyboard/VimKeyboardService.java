package dev.vimkeyboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** The input method: routes keys through {@link VimEngine} and applies its edits to the focused field. */
public final class VimKeyboardService extends InputMethodService
        implements VimEngine.Host, VimKeyboardView.Listener {

    public static final String PREFS = "settings";
    public static final String PREF_START_NORMAL = "start_normal";
    public static final String PREF_BLOCK_CURSOR = "block_cursor";
    public static final String PREF_KHMER = "khmer";
    public static final String PREF_SWIPE = "swipe";

    public static final String PREF_PREDICT = "predict";
    private static final String PREF_HIST_EX = "history_ex", PREF_HIST_SEARCH = "history_search";
    private static final String SEP = "\u0001";

    private volatile SwipeDecoder decoder;
    private volatile LanguageModel lm;
    /** What the suggestion bar shows: corrections for the last swiped word, or next-word predictions. */
    private static final int SUGGEST_NONE = 0, SUGGEST_ALTERNATIVES = 1, SUGGEST_PREDICTIONS = 2;
    private int suggestKind = SUGGEST_NONE;
    private boolean khmer;
    /** The text inserted by the last gesture (leading space + word), for suggestion replacement. */
    private String lastGestureWord, lastGesturePrefix;
    private List<String> lastAlternatives;

    private VimEngine engine;
    private VimKeyboardView view;

    /** Last known absolute selection of the field. */
    private int selStart, selEnd;
    private int textOffset;
    /** Selections we caused, so onUpdateSelection can tell them apart from taps/drags. */
    private final ArrayDeque<int[]> expected = new ArrayDeque<>();
    /** Fields with no text model (terminals): send raw key events instead of vim editing. */
    private boolean passthrough;
    private final Set<Integer> consumedHardwareKeys = new HashSet<>();

    @Override public void onCreate() {
        super.onCreate();
        engine = new VimEngine(this);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        khmer = prefs.getBoolean(PREF_KHMER, false);
        engine.setHistory(false, splitHistory(prefs.getString(PREF_HIST_EX, "")));
        engine.setHistory(true, splitHistory(prefs.getString(PREF_HIST_SEARCH, "")));
        new Thread(new Runnable() {
            @Override public void run() { loadDictionary(); }
        }, "swipe-dict").start();
    }

    private void loadDictionary() {
        try {
            LanguageModel m = LanguageModel.load(getAssets().open("en_lm.bin"));
            List<String> words = new ArrayList<>(m.size());
            for (int i = 1; i < m.size(); i++) words.add(m.word(i));
            decoder = new SwipeDecoder(words);
            lm = m;
        } catch (IOException | RuntimeException e) {
            Log.e("VimKeyboard", "language model", e);
        }
    }

    private static List<String> splitHistory(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(SEP)) if (!part.isEmpty()) out.add(part);
        return out;
    }

    private static String joinHistory(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String it : items) { if (sb.length() > 0) sb.append(SEP); sb.append(it); }
        return sb.toString();
    }

    private void saveHistory() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_HIST_EX, joinHistory(engine.getHistory(false)))
                .putString(PREF_HIST_SEARCH, joinHistory(engine.getHistory(true)))
                .apply();
    }

    @Override public void onDestroy() {
        saveHistory();
        super.onDestroy();
    }

    @Override public View onCreateInputView() {
        view = new VimKeyboardView(this, this, khmer);
        updateUi();
        return view;
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public void onStartInput(EditorInfo ei, boolean restarting) {
        super.onStartInput(ei, restarting);
        selStart = Math.max(0, ei.initialSelStart);
        selEnd = Math.max(0, ei.initialSelEnd);
        expected.clear();
        textOffset = 0;
        passthrough = (ei.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL;
    }

    @Override public void onStartInputView(EditorInfo ei, boolean restarting) {
        super.onStartInputView(ei, restarting);
        if (view != null) view.resetLayer();
        clearGesture();
        if (!passthrough) {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            engine.setBlockCursor(p.getBoolean(PREF_BLOCK_CURSOR, true));
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.beginBatchEdit();
            try {
                if (restarting) engine.syncCursor(selStart, selEnd);
                else engine.reset(p.getBoolean(PREF_START_NORMAL, false), selStart, selEnd);
            } finally {
                if (ic != null) ic.endBatchEdit();
            }
        }
        updateUi();
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        saveHistory();
        // Don't leave a block-cursor / visual selection behind in the field.
        if (!passthrough && engine.getMode() != VimEngine.Mode.INSERT && engine.getMode() != VimEngine.Mode.REPLACE) {
            engine.cancelPending();
            InputConnection ic = getCurrentInputConnection();
            if (ic != null && selStart != selEnd) {
                int p = Math.min(selStart, selEnd);
                ic.setSelection(p, p);
                selStart = selEnd = p;
                expect(p, p);
            }
        }
        super.onFinishInputView(finishingInput);
    }

    @Override public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        selStart = newSelStart;
        selEnd = newSelEnd;
        boolean ours = false;
        while (!expected.isEmpty()) {
            int[] e = expected.pollFirst();
            if (e[0] == newSelStart && e[1] == newSelEnd) { ours = true; break; }
        }
        if (ours || passthrough) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.beginBatchEdit();
        try {
            engine.onExternalSelection(newSelStart, newSelEnd);
        } finally {
            if (ic != null) ic.endBatchEdit();
        }
        updateUi();
    }

    private void expect(int s, int e) {
        expected.addLast(new int[]{s, e});
        while (expected.size() > 32) expected.pollFirst();
    }

    // ------------------------------------------------------------------ keyboard events

    @Override public void onKey(String key) {
        clearGesture();
        applyKey(key);
        if (key.equals(" ")) showPredictions();
    }

    private void applyKey(String key) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (passthrough) { sendTerminalKey(ic, key); return; }
        ic.beginBatchEdit();
        try {
            engine.handleKey(key);
        } catch (RuntimeException e) {
            // Never let an editing bug take down the keyboard.
            Log.e("VimKeyboard", "error handling " + key, e);
            engine.cancelPending();
        } finally {
            ic.endBatchEdit();
        }
        updateUi();
    }

    // ------------------------------------------------------------------ swipe typing

    private void clearGesture() {
        suggestKind = SUGGEST_NONE;
        lastGestureWord = null;
        lastAlternatives = null;
        if (view != null) view.setSuggestions(null);
    }

    @Override public void onGesture(float[] xs, float[] ys, int count, float[][] centers, float keyWidth, int caseMode) {
        SwipeDecoder d = decoder;
        final LanguageModel m = lm;
        if (d == null || m == null || !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_SWIPE, true)) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        // Context: the words before the cursor pick between similar swipes ("I'll see you" vs "I'll see toy").
        final int[] ctx = m.contextOf(ic.getTextBeforeCursor(150, 0));
        List<String> results = d.decode(xs, ys, count, centers, keyWidth, 4, new SwipeDecoder.Scorer() {
            @Override public float cost(int index) { return m.cost(ctx[0], ctx[1], index + 1); }
        });
        if (results.isEmpty()) return;
        if (caseMode == 0) {
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null && ic.getCursorCapsMode(ei.inputType) != 0) caseMode = 1;
        }
        List<String> cased = new ArrayList<>();
        for (String w : results) cased.add(applyCase(w, caseMode));
        String word = cased.get(0);
        lastGesturePrefix = commitWord(ic, word);
        lastGestureWord = word;
        lastAlternatives = cased;
        suggestKind = SUGGEST_ALTERNATIVES;
        showAlternatives();
    }

    /** Inserts a whole word, adding a space before it when it follows another word. Returns the prefix used. */
    private String commitWord(InputConnection ic, String word) {
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        String prefix = "";
        boolean inField = engine.getMode() == VimEngine.Mode.INSERT || engine.getMode() == VimEngine.Mode.REPLACE;
        if (inField && before != null && before.length() > 0) {
            char c = before.charAt(0);
            if (!Character.isWhitespace(c) && "([{\"'\u201C\u2018/-@#".indexOf(c) < 0) prefix = " ";
        }
        applyKey(prefix + word);
        return prefix;
    }

    /** Next-word predictions from the n-gram model, shown after a space (English, Insert mode). */
    private void showPredictions() {
        LanguageModel m = lm;
        InputConnection ic = getCurrentInputConnection();
        if (m == null || ic == null || view == null || khmer || passthrough) return;
        if (engine.getMode() != VimEngine.Mode.INSERT) return;
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_PREDICT, true)) return;
        EditorInfo ei = getCurrentInputEditorInfo();
        if (ei != null) {
            int variation = ei.inputType & InputType.TYPE_MASK_VARIATION;
            if ((ei.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT
                    || variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_URI
                    || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) return;
        }
        int[] ctx = m.contextOf(ic.getTextBeforeCursor(150, 0));
        boolean caps = ei != null && ic.getCursorCapsMode(ei.inputType) != 0;
        List<String> words = new ArrayList<>();
        for (int id : m.predict(ctx[0], ctx[1], 3)) words.add(applyCase(m.word(id), caps ? 1 : 0));
        if (words.isEmpty()) return;
        lastAlternatives = words;
        lastGestureWord = null;
        suggestKind = SUGGEST_PREDICTIONS;
        view.setSuggestions(words);
    }

    private void showAlternatives() {
        if (view == null || lastAlternatives == null) return;
        List<String> alts = new ArrayList<>();
        for (String w : lastAlternatives) if (!w.equals(lastGestureWord) && alts.size() < 3) alts.add(w);
        view.setSuggestions(alts);
    }

    private static String applyCase(String w, int caseMode) {
        if (w.equals("i")) w = "I";
        else if (w.startsWith("i'")) w = "I" + w.substring(1);
        if (caseMode == 2) return w.toUpperCase();
        if (caseMode == 1 && !w.isEmpty()) return Character.toUpperCase(w.charAt(0)) + w.substring(1);
        return w;
    }

    @Override public void onPickSuggestion(String word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (suggestKind == SUGGEST_PREDICTIONS) {
            commitWord(ic, word);
            showPredictions();
            return;
        }
        if (lastGestureWord == null) return;
        CharSequence before = ic.getTextBeforeCursor(lastGestureWord.length(), 0);
        if (before == null || !before.toString().equals(lastGestureWord)) { clearGesture(); return; }
        for (int i = 0; i < lastGestureWord.length(); i++) applyKey("<BS>");
        applyKey(word);
        lastGestureWord = word;
        showAlternatives();
    }

    @Override public void onLanguageChanged(boolean k) {
        khmer = k;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_KHMER, k).apply();
        clearGesture();
    }

    @Override public void onModeToggle() {
        clearGesture();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (passthrough) { sendTerminalKey(ic, "<Esc>"); return; }
        VimEngine.Mode m = engine.getMode();
        if (m == VimEngine.Mode.INSERT || m == VimEngine.Mode.REPLACE) {
            applyKey("<Esc>");
        } else {
            for (int i = 0; i < 3 && (engine.getMode() != VimEngine.Mode.NORMAL || !engine.statusText().isEmpty()); i++)
                applyKey("<Esc>");
            applyKey("i");
        }
    }

    @Override public void onLongPressSpace() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    private void updateUi() {
        if (view == null) return;
        if (passthrough) {
            view.setState("TERMINAL", 0xFF9CA3AF, true, true, "raw keys → app");
            return;
        }
        int color;
        switch (engine.getMode()) {
            case NORMAL: color = 0xFF60A5FA; break;
            case VISUAL: case VISUAL_LINE: color = 0xFFF59E0B; break;
            case CMDLINE: color = 0xFFC084FC; break;
            case REPLACE: color = 0xFFF87171; break;
            default: color = 0xFF4ADE80;
        }
        boolean insertLike = engine.getMode() == VimEngine.Mode.INSERT || engine.getMode() == VimEngine.Mode.REPLACE;
        boolean textEntry = insertLike || engine.getMode() == VimEngine.Mode.CMDLINE;
        view.setState(engine.modeLabel(), color, insertLike, textEntry, engine.statusText());
    }

    private void sendTerminalKey(InputConnection ic, String key) {
        switch (key) {
            case "<Esc>": case "<C-[>": sendKey(ic, KeyEvent.KEYCODE_ESCAPE, 0); return;
            case "<BS>": sendKey(ic, KeyEvent.KEYCODE_DEL, 0); return;
            case "<CR>": sendKey(ic, KeyEvent.KEYCODE_ENTER, 0); return;
            case "<Tab>": sendKey(ic, KeyEvent.KEYCODE_TAB, 0); return;
            case "<Up>": sendKey(ic, KeyEvent.KEYCODE_DPAD_UP, 0); return;
            case "<Down>": sendKey(ic, KeyEvent.KEYCODE_DPAD_DOWN, 0); return;
            case "<C-r>": break;
        }
        if (key.startsWith("<C-") && key.length() == 5) {
            char c = key.charAt(3);
            if (c >= 'a' && c <= 'z') {
                sendKey(ic, KeyEvent.KEYCODE_A + (c - 'a'), KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
                return;
            }
        }
        if (key.length() > 1 && key.startsWith("<")) return;
        ic.commitText(key, 1);
    }

    private static void sendKey(InputConnection ic, int code, int meta) {
        long now = SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta));
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta));
    }

    // ------------------------------------------------------------------ hardware keyboard

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (passthrough || getCurrentInputConnection() == null || getCurrentInputEditorInfo() == null)
            return super.onKeyDown(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed()) {
            // Ctrl+Space switches English <-> Khmer.
            khmer = !khmer;
            if (view != null) view.setKhmer(khmer);
            onLanguageChanged(khmer);
            consumedHardwareKeys.add(keyCode);
            updateUi();
            return true;
        }
        String k = translateHardwareKey(keyCode, event);
        if (k == null) return super.onKeyDown(keyCode, event);
        consumedHardwareKeys.add(keyCode);
        onKey(k);
        return true;
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (consumedHardwareKeys.remove(keyCode)) return true;
        return super.onKeyUp(keyCode, event);
    }

    private String translateHardwareKey(int keyCode, KeyEvent ev) {
        if (ev.isAltPressed() || ev.isMetaPressed()) return null;
        boolean insert = engine.getMode() == VimEngine.Mode.INSERT || engine.getMode() == VimEngine.Mode.REPLACE;
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE: return "<Esc>";
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return "<CR>";
            case KeyEvent.KEYCODE_DEL: return "<BS>";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "<Del>";
            case KeyEvent.KEYCODE_TAB: return insert ? "<Tab>" : null;
            case KeyEvent.KEYCODE_DPAD_LEFT: return insert ? null : "<Left>";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return insert ? null : "<Right>";
            case KeyEvent.KEYCODE_DPAD_UP: return insert ? null : "<Up>";
            case KeyEvent.KEYCODE_DPAD_DOWN: return insert ? null : "<Down>";
            case KeyEvent.KEYCODE_MOVE_HOME: return insert ? null : "<Home>";
            case KeyEvent.KEYCODE_MOVE_END: return insert ? null : "<End>";
        }
        if (ev.isCtrlPressed()) {
            int uc = ev.getUnicodeChar(ev.getMetaState() & ~KeyEvent.META_CTRL_MASK);
            if (uc == '[') return "<C-[>";
            if (insert && uc != 'w' && uc != 'u') return null; // let the app handle ctrl+c/v/z etc.
            if (uc >= 'a' && uc <= 'z') return "<C-" + (char) uc + ">";
            if (uc >= 'A' && uc <= 'Z') return "<C-" + Character.toLowerCase((char) uc) + ">";
            return null;
        }
        int uc = ev.getUnicodeChar(ev.getMetaState());
        if (uc == 0 || (uc & KeyCharacterMap.COMBINING_ACCENT) != 0) return null;
        boolean textEntry = insert || engine.getMode() == VimEngine.Mode.CMDLINE;
        if (khmer && textEntry && uc != ' ') {
            String km = KhmerLayout.fromUs((char) uc);
            if (km != null) return km;
        }
        return new String(Character.toChars(uc));
    }

    // ------------------------------------------------------------------ VimEngine.Host

    @Override public CharSequence getText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        ExtractedTextRequest req = new ExtractedTextRequest();
        req.hintMaxChars = 1000000;
        req.hintMaxLines = 100000;
        ExtractedText et = ic.getExtractedText(req, 0);
        if (et != null && et.text != null) {
            textOffset = et.startOffset;
            return et.text;
        }
        // Fallback for fields that don't support extracted text: a window around the cursor.
        CharSequence before = ic.getTextBeforeCursor(20000, 0);
        CharSequence sel = ic.getSelectedText(0);
        CharSequence after = ic.getTextAfterCursor(20000, 0);
        if (before == null) before = "";
        if (sel == null) sel = "";
        if (after == null) after = "";
        textOffset = Math.max(0, Math.min(selStart, selEnd) - before.length());
        return before.toString() + sel + after;
    }

    @Override public int textOffset() { return textOffset; }

    @Override public void replace(int start, int end, String t) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int a = start + textOffset, b = end + textOffset;
        ic.beginBatchEdit();
        if (t.isEmpty()) {
            if (selStart != b || selEnd != b) ic.setSelection(b, b);
            ic.deleteSurroundingText(b - a, 0);
        } else {
            if (selStart != a || selEnd != b) ic.setSelection(a, b);
            ic.commitText(t, 1);
        }
        ic.endBatchEdit();
        selStart = selEnd = a + t.length();
        expect(selStart, selEnd);
    }

    @Override public void setSelection(int start, int end) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int a = start + textOffset, b = end + textOffset;
        if (a == selStart && b == selEnd) return;
        ic.setSelection(a, b);
        selStart = a;
        selEnd = b;
        expect(a, b);
    }

    @Override public boolean isSingleLine() {
        EditorInfo ei = getCurrentInputEditorInfo();
        if (ei == null) return false;
        int type = ei.inputType;
        if ((type & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return true;
        return (type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0 && (type & InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) == 0;
    }

    @Override public void performEditorAction() {
        InputConnection ic = getCurrentInputConnection();
        EditorInfo ei = getCurrentInputEditorInfo();
        if (ic == null || ei == null) return;
        int action = ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            sendKey(ic, KeyEvent.KEYCODE_ENTER, 0);
        }
    }

    @Override public void hideKeyboard() { requestHideSelf(0); }

    @Override public String getClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return null;
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return null;
        CharSequence t = cd.getItemAt(0).coerceToText(this);
        return t == null ? null : t.toString();
    }

    @Override public void setClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("vim", text));
    }
}
