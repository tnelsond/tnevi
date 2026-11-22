package dev.vimkeyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Soft keyboard built in code: vim mode indicator, command strip, English (QWERTY) and
 * language-pack layers (e.g. Khmer), long-press extras, and swipe (gesture) typing.
 */
@SuppressLint("ViewConstructor")
public final class VimKeyboardView extends LinearLayout {

    /** A language as the keyboard view sees it: a name and an optional pack layout (null = QWERTY). */
    public static final class Lang {
        public final String id, name;
        public final Layout layout;
        public boolean swipe;
        public Lang(String id, String name, Layout layout) { this.id = id; this.name = name; this.layout = layout; }
    }

    public interface Listener {
        void onKey(String key);
        void onLongPressSpace();
        /** Returns false if no word was typed (the view then types the touched key instead). */
        boolean onGesture(float[] xs, float[] ys, int count, float[][] centers, float keyWidth, int caseMode);
        void onPickSuggestion(String word);
        void onLanguageChanged(int index);
        /** Mode badge tapped: switch between Insert and Normal (command) mode. */
        void onModeToggle();
        /** "Delete word" button on the top bar. */
        void onDeleteWord();
    }

    private static final int BG = 0xFF15171C, KEY = 0xFF2B2F38, KEY_PRESSED = 0xFF4A505E,
            KEY_SPECIAL = 0xFF20232A, TEXT = 0xFFE9EAEE, TEXT_DIM = 0xFF9AA0AD, HINT = 0xFF7C8394,
            ACCENT_ON = 0xFF3B82F6, TRAIL = 0xCC4ADE80;

    /** Row heights (dp). Pack layouts without a number row share the same total height. */
    private static final int NUM_H = 46, ROW_H = 54, KM_H = (NUM_H + 3 * ROW_H) / 4;

    private static final String SHIFT = "SHIFT", SYM = "SYM", CTRL = "CTRL", LANG = "LANG";
    private static final Set<String> NORMAL_REPEAT = new HashSet<>(Arrays.asList(
            "h", "j", "k", "l", "w", "b", "e", "x", "W", "B", "E", "u", "<C-r>", "<C-d>", "<C-u>"));

    /** A character key: what it types normally, with shift, and on long-press. */
    static final class Spec {
        final String base, shifted;
        final String[] alts;
        final boolean letter;
        /** Key index for swipe typing (QWERTY: a-z = 0-25; pack layouts: row-major), -1 = not swipeable. */
        int swipeIndex = -1;
        Spec(String base, String shifted, String alt, boolean letter) {
            this(base, shifted, alt == null || alt.isEmpty() ? new String[0] : new String[]{alt}, letter);
        }
        Spec(String base, String shifted, String[] alts, boolean letter) {
            this.base = base; this.shifted = shifted; this.alts = alts; this.letter = letter;
        }
        /** From a layout entry {main, options...}: shift types the first option. */
        static Spec of(String[] e) {
            String[] alts = new String[e.length - 1];
            System.arraycopy(e, 1, alts, 0, alts.length);
            return new Spec(e[0], alts.length > 0 ? alts[0] : null, alts, false);
        }
    }

    /**
     * A key. Its touch area is its whole cell (no dead gaps between keys); the visible key is drawn
     * inset by a small gap. Edge keys can claim extra empty space beside them (like Gboard's A and L),
     * with the visible key kept at normal size. A hint is drawn in the top-right corner.
     */
    static final class KeyView extends TextView {
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String hint = "";
        private final int gapX, gapY;
        private Drawable keyBackground;
        /** Fraction of the cell width that is empty touch area to the left/right of the visible key. */
        float extraLeft, extraRight;

        KeyView(Context c, float dp) {
            super(c);
            gapX = Math.round(2 * dp);
            gapY = Math.round(3 * dp);
            hintPaint.setColor(0xFFC9CED8);
            hintPaint.setTextSize(13 * dp * c.getResources().getConfiguration().fontScale);
            hintPaint.setTextAlign(Paint.Align.RIGHT);
        }

        void setKeyBackground(Drawable d) { keyBackground = d; applyInsets(getWidth()); }

        int insetLeft() { return gapX + Math.round(getWidth() * extraLeft); }
        int insetRight() { return gapX + Math.round(getWidth() * extraRight); }
        int visibleWidth() { return getWidth() - insetLeft() - insetRight(); }

        private void applyInsets(int w) {
            if (keyBackground == null) return;
            int l = gapX + Math.round(w * extraLeft), r = gapX + Math.round(w * extraRight);
            setBackground(new InsetDrawable(keyBackground, l, gapY, r, gapY));
            setPadding(l - gapX, 0, r - gapX, 0);  // centre the label on the visible key
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (extraLeft > 0 || extraRight > 0) applyInsets(w);
        }

        void setHint(String h) { hint = h == null ? "" : h; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (hint.isEmpty()) return;
            // Single-line centred TextViews scroll their content; draw relative to the visible area.
            float pad = visibleWidth() * 0.09f + gapY * 0.3f;
            float x = getScrollX() + getWidth() - insetRight() - pad + gapX;
            float y = getScrollY() + gapY + pad * 0.6f - hintPaint.ascent();
            float maxW = visibleWidth() * 0.8f;
            float tw = hintPaint.measureText(hint);
            if (tw > maxW) {
                float old = hintPaint.getTextSize();
                hintPaint.setTextSize(old * maxW / tw);
                canvas.drawText(hint, x, y, hintPaint);
                hintPaint.setTextSize(old);
            } else canvas.drawText(hint, x, y, hintPaint);
        }
    }

    private final Listener listener;
    private final float dp;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView modeBadge, statusText, deleteWord;
    private String modeLabel;
    private LinearLayout suggestionBar, keyRows;
    private final List<TextView> charKeys = new ArrayList<>();
    private TextView shiftKey, ctrlKey, langKey, spaceKey;

    private final List<Lang> langs = new ArrayList<>();
    private int lang;
    private boolean symbols, shift, capsLock, ctrl;
    private boolean insertLike = true, textEntry = true;
    private long lastShiftTap;

    // touch state
    private static final int DOWN_NONE = 0, DOWN_CHAR = 1, DOWN_SPACE = 2;
    private int downKind = DOWN_NONE;
    private Spec downSpec;
    private float downX, downY;
    private boolean swiping, longFired;
    private Runnable repeater, longPress;
    private final List<float[]> gesture = new ArrayList<>();
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] loc = new int[2];

    public VimKeyboardView(Context ctx, Listener listener, List<Lang> languages, int current) {
        super(ctx);
        this.listener = listener;
        langs.addAll(languages);
        lang = Math.max(0, Math.min(current, langs.size() - 1));
        this.dp = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        setPadding(0, px(2), 0, px(4));
        setWillNotDraw(false);
        trailPaint.setColor(TRAIL);
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeWidth(px(5));
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);

        buildStatusBar();
        keyRows = new LinearLayout(ctx);
        keyRows.setOrientation(VERTICAL);
        addView(keyRows, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        buildKeys();
    }

    private int px(float v) { return Math.round(v * dp); }

    // ------------------------------------------------------------------ layout

    private void buildStatusBar() {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(4), px(2), px(4), px(4));
        modeBadge = new TextView(getContext());
        modeBadge.setTextColor(0xFF0B0C0F);
        modeBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        modeBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        modeBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        modeBadge.setGravity(Gravity.CENTER);
        modeBadge.setPadding(px(12), 0, px(12), 0);
        modeBadge.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                listener.onModeToggle();
            }
        });
        bar.addView(modeBadge, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        statusText = new TextView(getContext());
        statusText.setTextColor(TEXT);
        statusText.setTypeface(Typeface.MONOSPACE);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.START);
        statusText.setPadding(px(10), 0, px(4), 0);
        bar.addView(statusText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        suggestionBar = new LinearLayout(getContext());
        suggestionBar.setOrientation(HORIZONTAL);
        suggestionBar.setVisibility(GONE);
        bar.addView(suggestionBar, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        // Delete the last swiped word (or the word before the cursor); hold to keep deleting.
        deleteWord = new TextView(getContext());
        deleteWord.setText("\u232B word");
        deleteWord.setTextColor(TEXT);
        deleteWord.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        deleteWord.setGravity(Gravity.CENTER);
        deleteWord.setPadding(px(10), 0, px(10), 0);
        deleteWord.setBackground(rounded(KEY_SPECIAL, px(12)));
        deleteWord.setOnTouchListener(new OnTouchListener() {
            private final Runnable again = new Runnable() {
                @Override public void run() { listener.onDeleteWord(); handler.postDelayed(this, 250); }
            };
            @SuppressLint("ClickableViewAccessibility")
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        listener.onDeleteWord();
                        handler.postDelayed(again, 500);
                        return true;
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        handler.removeCallbacks(again);
                        return true;
                }
                return true;
            }
        });
        LayoutParams dlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        dlp.setMargins(px(4), px(1), px(2), px(1));
        bar.addView(deleteWord, dlp);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, px(32)));
    }

    private LinearLayout row(int heightDp) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        r.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, px(heightDp)));
        return r;
    }

    private void spacer(LinearLayout r, float w) {
        r.addView(new View(getContext()), new LayoutParams(0, LayoutParams.MATCH_PARENT, w));
    }

    private Lang cur() { return langs.get(lang); }

    /** The current pack layout, when typing text in a language that has one (null = QWERTY). */
    private Layout packLayout() { return textEntry ? cur().layout : null; }

    private boolean packLettersActive() { return packLayout() != null && packLayout().letters != null && !symbols; }

    private void buildKeys() {
        keyRows.removeAllViews();
        charKeys.clear();
        ctrlKey = null;
        shiftKey = null;

        if (symbols && packLayout() != null && packLayout().symbols != null) {
            List<List<String[]>> rows = packLayout().symbols;
            int h = (NUM_H + 3 * ROW_H) / rows.size();
            for (int r = 0; r < rows.size(); r++) {
                LinearLayout row = row(h);
                for (int i = 0; i < rows.get(r).size(); i++) {
                    String[] e = rows.get(r).get(i);
                    if (r == 0 && i < 10) row.addView(numberKey(e[0], null, i));
                    else row.addView(charKey(Spec.of(e), 1f));
                }
                if (r == rows.size() - 1) row.addView(codeKey("\u232B", "<BS>", 1.4f, true));
                keyRows.addView(row);
            }
        } else if (symbols) {
            LinearLayout nums = row(NUM_H);
            for (int i = 0; i < 10; i++) nums.addView(numberKey(s("1234567890".charAt(i)), null, i));
            keyRows.addView(nums);
            String[] rows = {"!@#$%^&*()", "-_=+[]{}\\|", "`~<>;:'\"/?"};
            for (int i = 0; i < rows.length; i++) {
                LinearLayout r = row(ROW_H);
                for (char c : rows[i].toCharArray()) r.addView(charKey(new Spec(s(c), null, (String) null, false), 1f));
                if (i == 2) r.addView(codeKey("⌫", "<BS>", 1.5f, true));
                keyRows.addView(r);
            }
        } else if (packLettersActive()) {
            List<List<String[]>> rows = packLayout().letters;
            int[][] swipe = packLayout().swipeIndices();
            int h = (NUM_H + 3 * ROW_H) / rows.size();   // same total height as QWERTY + number row
            for (int r = 0; r < rows.size(); r++) {
                LinearLayout row = row(h);
                boolean last = r == rows.size() - 1;
                if (last) { shiftKey = codeKey("\u21E7", SHIFT, 1.35f, true); row.addView(shiftKey); }
                for (int i = 0; i < rows.get(r).size(); i++) {
                    Spec sp = Spec.of(rows.get(r).get(i));
                    sp.swipeIndex = swipe[r][i];
                    row.addView(charKey(sp, 1f));
                }
                if (last) row.addView(codeKey("\u232B", "<BS>", 1.35f, true));
                keyRows.addView(row);
            }
        } else {
            LinearLayout nums = row(NUM_H);
            String digits = "1234567890";
            for (int i = 0; i < 10; i++)
                nums.addView(numberKey(s(digits.charAt(i)), null, i));
            keyRows.addView(nums);
            LinearLayout r1 = row(ROW_H);
            for (char c : "qwertyuiop".toCharArray()) r1.addView(letter(c));
            keyRows.addView(r1);
            LinearLayout r2 = row(ROW_H);
            for (char c : "asdfghjkl".toCharArray()) {
                KeyView k = (KeyView) letter(c);
                if (c == 'a' || c == 'l') {
                    ((LayoutParams) k.getLayoutParams()).weight = 1.5f;
                    if (c == 'a') k.extraLeft = 1f / 3; else k.extraRight = 1f / 3;
                }
                r2.addView(k);
            }
            keyRows.addView(r2);
            LinearLayout r3 = row(ROW_H);
            shiftKey = codeKey("⇧", SHIFT, 1.5f, true);
            r3.addView(shiftKey);
            for (char c : "zxcvbnm".toCharArray()) r3.addView(letter(c));
            r3.addView(codeKey("⌫", "<BS>", 1.5f, true));
            keyRows.addView(r3);
        }

        LinearLayout bottom = row(ROW_H);
        bottom.addView(codeKey(symbols ? "abc" : "?123", SYM, 1.5f, true));
        bottom.addView(charKey(new Spec(",", null, (String) null, false), 1f));
        spaceKey = codeKey(spaceLabel(), " ", 5f, false);
        spaceKey.setTextColor(TEXT_DIM);
        bottom.addView(spaceKey);
        bottom.addView(charKey(new Spec(".", null, new String[]{"!", "?"}, false), 1f));
        bottom.addView(codeKey("⏎", "<CR>", 1.6f, true));
        keyRows.addView(bottom);
        refreshLabels();
    }

    private String spaceLabel() {
        if (!textEntry) return "space";
        return langs.size() > 1 ? "\u25C2 " + cur().name + " \u25B8" : cur().name;
    }

    private static String s(char c) { return String.valueOf(c); }

    /** Gboard-style long-press characters for English letters. */
    private static final String EN_TOP = "qwertyuiop", EN_TOP_ALT = "%^~|[]<>{}";  // Gboard standard
    private static final String EN_MID = "asdfghjkl", EN_MID_ALT = "@#&*-+=()";
    private static final String EN_BOT = "zxcvbnm", EN_BOT_ALT = "_$\"':;/";

    /** Long-press on the number keys 1..0: ctrl, up, redo and common vim symbols. */
    private static final String[] NUM_ALTS = {CTRL, "<Up>", "<C-r>", "<Down>", "", "", "", "", "`", "\\"};  // only what the letters don't have

    private TextView numberKey(String main, String shifted, int i) {
        TextView t = charKey(new Spec(main, shifted, NUM_ALTS[i], false), 1f);
        if (i == 0) ctrlKey = t;
        return t;
    }

    private static String altLabel(String a) {
        switch (a) {
            case CTRL: return "ctrl";
            case "<Up>": return "\u2191";
            case "<Down>": return "\u2193";
            case "<C-r>": return "\u21B7";
        }
        return a;
    }

    private TextView letter(char c) {
        String alt = null;
        int i;
        if ((i = EN_TOP.indexOf(c)) >= 0) alt = s(EN_TOP_ALT.charAt(i));
        else if ((i = EN_MID.indexOf(c)) >= 0) alt = s(EN_MID_ALT.charAt(i));
        else if ((i = EN_BOT.indexOf(c)) >= 0) alt = s(EN_BOT_ALT.charAt(i));
        Spec sp = new Spec(s(c), s(Character.toUpperCase(c)), alt, true);
        sp.swipeIndex = c - 'a';
        return charKey(sp, 1f);
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private TextView baseKey(Object tag, float weight, boolean special) {
        final KeyView t = new KeyView(getContext(), dp);
        t.setTag(tag);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(special ? TEXT_DIM : TEXT);
        t.setMaxLines(1);
        t.setHorizontallyScrolling(false);
        t.setIncludeFontPadding(false);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, rounded(KEY_PRESSED, px(6)));
        bg.addState(new int[]{android.R.attr.state_selected}, rounded(ACCENT_ON, px(6)));
        bg.addState(new int[]{}, rounded(special ? KEY_SPECIAL : KEY, px(6)));
        t.setKeyBackground(bg);
        t.setLayoutParams(new LayoutParams(0, LayoutParams.MATCH_PARENT, weight));
        t.setOnTouchListener(touch);
        return t;
    }

    private TextView codeKey(String label, String code, float weight, boolean special) {
        TextView t = baseKey(code, weight, special);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() > 2 ? 14 : 21);
        return t;
    }

    private TextView charKey(Spec spec, float weight) {
        TextView t = baseKey(spec, weight, false);
        charKeys.add(t);
        return t;
    }

    private void refreshLabels() {
        boolean upper = shift || capsLock;
        boolean kmText = packLayout() != null;
        for (TextView t : charKeys) {
            Spec sp = (Spec) t.getTag();
            boolean useShift = upper && sp.shifted != null;
            String main = useShift ? sp.shifted : sp.base;
            String label = kmText ? Layout.label(main) : main;
            StringBuilder hint = new StringBuilder();
            for (String a : sp.alts) {
                if (a.equals(main)) continue;
                if (hint.length() > 0) hint.append(',');
                hint.append(kmText && sp.alts.length == 1 && !a.startsWith("<") && !a.equals(CTRL) ? Layout.label(a) : altLabel(a));
            }
            if (useShift && sp.alts.length == 1) hint.setLength(0);
            t.setText(label);
            ((KeyView) t).setHint(hint.toString());
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, kmText ? 25 : (label.length() > 2 ? 14 : 24));
        }
        if (shiftKey != null) {
            shiftKey.setSelected(shift || capsLock);
            shiftKey.setText(capsLock ? "⇪" : "⇧");
        }
        if (ctrlKey != null) ctrlKey.setSelected(ctrl);
        if (modeBadge != null && modeLabel != null) modeBadge.setText(ctrl ? modeLabel + " \u2303" : modeLabel);
        if (spaceKey != null) spaceKey.setText(spaceLabel());
    }

    // ------------------------------------------------------------------ touch handling

    private final OnTouchListener touch = new OnTouchListener() {
        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Fast typing: the next finger lands before the previous one lifts.
                    // Commit the previous key now instead of losing it.
                    if (downView != null && downView != v) commitPending();
                    v.setPressed(true);
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    downView = v;
                    onDown(v.getTag(), ev.getRawX(), ev.getRawY());
                    showPreview(v);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // A second finger on the same key (e.g. alternating thumbs on a double letter).
                    if (v == downView) {
                        commitPending();
                        int idx = ev.getActionIndex();
                        float rx = ev.getX(idx) + (ev.getRawX() - ev.getX()), ry = ev.getY(idx) + (ev.getRawY() - ev.getY());
                        v.setPressed(true);
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        downView = v;
                        onDown(v.getTag(), rx, ry);
                        showPreview(v);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (v != downView) return true;   // a finger whose key was already committed
                    for (int h = 0; h < ev.getHistorySize(); h++) onMove(ev.getHistoricalAxisValue(MotionEvent.AXIS_X, h) + (ev.getRawX() - ev.getX()),
                            ev.getHistoricalAxisValue(MotionEvent.AXIS_Y, h) + (ev.getRawY() - ev.getY()));
                    onMove(ev.getRawX(), ev.getRawY());
                    if (swiping) { v.setPressed(false); hidePreview(); }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (v != downView) return true;
                    onUp();
                    downView = null;
                    handler.postDelayed(hidePreviewLater, 70);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (v != downView) return true;
                    downView = null;
                    hidePreview();
                    dismissOptions();
                    cancelTimers();
                    downKind = DOWN_NONE;
                    swiping = false;
                    gesture.clear();
                    invalidate();
                    return true;
            }
            return true;
        }
    };

    /** Types the key still held by a previous finger (if it is a plain tap) and forgets it. */
    private void commitPending() {
        View prev = downView;
        if (prev != null) prev.setPressed(false);
        if (popup == null && !longFired && !swiping) {
            if (downKind == DOWN_CHAR) send(resolve(downSpec), true);
            else if (downKind == DOWN_SPACE) send(" ", true);
        }
        dismissOptions();
        cancelTimers();
        downKind = DOWN_NONE;
        swiping = false;
        gesture.clear();
        downView = null;
        invalidate();
    }

    private void cancelTimers() {
        if (repeater != null) handler.removeCallbacks(repeater);
        if (longPress != null) handler.removeCallbacks(longPress);
        repeater = null;
        longPress = null;
    }

    private void startRepeat(final String sent) {
        repeater = new Runnable() {
            @Override public void run() {
                listener.onKey(sent);
                handler.postDelayed(this, 45);
            }
        };
        handler.postDelayed(repeater, 380);
    }

    private void onDown(Object tag, float x, float y) {
        cancelTimers();
        downKind = DOWN_NONE;
        swiping = false;
        longFired = false;
        gesture.clear();
        downX = x; downY = y;

        if (tag instanceof Spec) {
            final Spec sp = (Spec) tag;
            if (!textEntry && sp.alts.length == 0) {
                // Command modes: act immediately so held motions repeat.
                String sent = send(resolve(sp), true);
                if (NORMAL_REPEAT.contains(sent)) startRepeat(sent);
                return;
            }
            downKind = DOWN_CHAR;
            downSpec = sp;
            gesture.add(new float[]{x, y});
            if (sp.alts.length > 0) {
                longPress = new Runnable() {
                    @Override public void run() {
                        longFired = true;
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        hidePreview();
                        if (sp.alts.length > 1) { showOptions(sp); return; }
                        String a = sp.alts[0];
                        if (a.equals(CTRL)) { ctrl = !ctrl; refreshLabels(); }
                        else {
                            send(a, false);
                            if (a.equals("<Up>") || a.equals("<Down>")) startRepeat(a);
                        }
                    }
                };
                handler.postDelayed(longPress, 380);
            }
            return;
        }

        String code = (String) tag;
        switch (code) {
            case SHIFT: {
                long now = System.currentTimeMillis();
                if (capsLock) { capsLock = false; shift = false; }
                else if (shift && now - lastShiftTap < 350) capsLock = true;
                else shift = !shift;
                lastShiftTap = now;
                refreshLabels();
                return;
            }
            case SYM:
                symbols = !symbols;
                buildKeys();
                return;
            case CTRL:
                ctrl = !ctrl;
                refreshLabels();
                return;
            case LANG:
                toggleLanguage();
                return;
            case " ":
                downKind = DOWN_SPACE;
                longPress = new Runnable() {
                    @Override public void run() {
                        longFired = true;
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        String[] ids = new String[langs.size() + 1], names = new String[langs.size() + 1];
                        for (int i = 0; i < langs.size(); i++) { ids[i] = "L" + i; names[i] = langs.get(i).name; }
                        ids[langs.size()] = "IME";
                        names[langs.size()] = "\u2328 more";
                        showOptions(ids, names, px(92), 17, lang);
                    }
                };
                handler.postDelayed(longPress, 500);
                return;
        }
        String sent = send(code, true);
        if (code.equals("<BS>") || code.equals("<Up>") || (!insertLike && NORMAL_REPEAT.contains(sent))) startRepeat(sent);
    }

    private void onMove(float x, float y) {
        if (downKind == DOWN_SPACE) {
            if (popup != null) { selectOption(x); return; }
            if (Math.abs(x - downX) > px(36)) {
                cancelTimers();
                downKind = DOWN_NONE;
                toggleLanguage();
            }
            return;
        }
        if (downKind != DOWN_CHAR) return;
        if (popup != null) { selectOption(x); return; }
        boolean canSwipe = insertLike && downSpec.swipeIndex >= 0 && cur().swipe && !symbols && !ctrl;
        if (!canSwipe) return;
        gesture.add(new float[]{x, y});
        if (!swiping) {
            float dx = x - downX, dy = y - downY;
            float start = Math.max(px(24), downView != null ? 0.6f * ((KeyView) downView).visibleWidth() : 0);
            if (dx * dx + dy * dy > start * start) {
                swiping = true;
                cancelTimers();
            }
        }
        if (swiping) invalidate();
    }

    private void onUp() {
        int kind = downKind;
        boolean fired = longFired;
        cancelTimers();
        downKind = DOWN_NONE;
        if (kind == DOWN_SPACE) {
            if (popup != null) {
                String pick = popupSel >= 0 ? popupOptions[popupSel] : null;
                dismissOptions();
                if ("IME".equals(pick)) listener.onLongPressSpace();
                else if (pick != null && pick.startsWith("L")) selectLanguage(Integer.parseInt(pick.substring(1)));
            } else if (!fired) send(" ", true);
        }
        else if (kind == DOWN_CHAR) {
            if (popup != null) {
                String pick = popupSel >= 0 ? popupOptions[popupSel] : null;
                dismissOptions();
                if (pick != null) send(pick, false);
            } else if (swiping) {
                if (!finishGesture()) send(resolve(downSpec), true);  // no word found: it was a tap
            }
            else if (!fired) send(resolve(downSpec), true);
        }
        swiping = false;
        gesture.clear();
        invalidate();
    }

    private String resolve(Spec sp) {
        return (shift || capsLock) && sp.shifted != null ? sp.shifted : sp.base;
    }

    /** Applies ctrl, consumes one-shot shift, sends; returns what was sent. */
    private String send(String k, boolean applyModifiers) {
        if (applyModifiers && ctrl && k.length() == 1) {
            k = "<C-" + k.toLowerCase() + ">";
            ctrl = false;
            refreshLabels();
        }
        if (shift && !capsLock && !k.startsWith("<")) {
            shift = false;
            refreshLabels();
        }
        listener.onKey(k);
        return k;
    }

    // ------------------------------------------------------------------ key preview bubble

    private PopupWindow preview;
    private TextView previewText;
    private final Runnable hidePreviewLater = new Runnable() {
        @Override public void run() { hidePreview(); }
    };

    /** Shows an enlarged copy of the pressed key above it, like Gboard. */
    private void showPreview(View key) {
        handler.removeCallbacks(hidePreviewLater);
        Object tag = key.getTag();
        String label;
        if (tag instanceof Spec) {
            Spec sp = (Spec) tag;
            String main = (shift || capsLock) && sp.shifted != null ? sp.shifted : sp.base;
            label = packLayout() != null ? Layout.label(main) : main;
        } else {
            label = ((TextView) key).getText().toString();
            if (label.length() != 1 || ((String) tag).startsWith("<")) { hidePreview(); return; }
        }
        if (key.getWidth() == 0) return;
        KeyView kv = (KeyView) key;
        int w = kv.visibleWidth() + px(14), h = key.getHeight() + px(34);
        if (preview == null) {
            previewText = new TextView(getContext());
            previewText.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            previewText.setTextColor(TEXT);
            previewText.setIncludeFontPadding(false);
            previewText.setBackground(rounded(0xFF474C58, px(10)));
            preview = new PopupWindow(previewText, w, h, false);
            preview.setTouchable(false);
            preview.setClippingEnabled(false);
            preview.setAnimationStyle(0);
        }
        previewText.setText(label);
        previewText.setTextSize(TypedValue.COMPLEX_UNIT_SP, packLayout() != null ? 32 : 30);
        previewText.setPadding(0, px(6), 0, 0);
        key.getLocationInWindow(loc);
        int x = loc[0] + kv.insetLeft() - (w - kv.visibleWidth()) / 2;
        int y = loc[1] + key.getHeight() - h;   // bubble grows up from the key's bottom edge, covering it like Gboard
        y -= key.getHeight() / 2 + px(4);        // lift so the character is visible above the finger
        try {
            if (preview.isShowing()) preview.update(x, y, w, h);
            else preview.showAtLocation(key, Gravity.NO_GRAVITY, x, y);
        } catch (RuntimeException ignored) { }
    }

    private void hidePreview() {
        handler.removeCallbacks(hidePreviewLater);
        if (preview != null && preview.isShowing()) {
            try { preview.dismiss(); } catch (RuntimeException ignored) { }
        }
    }

    // ------------------------------------------------------------------ long-press options popup

    private PopupWindow popup;
    private LinearLayout popupRow;
    private String[] popupOptions;
    private int popupSel = -1, popupLeft, popupOptW;

    private View downView;

    private void showOptions(Spec sp) {
        boolean km = packLayout() != null;
        String[] labels = new String[sp.alts.length];
        for (int i = 0; i < labels.length; i++) labels[i] = km ? Layout.label(sp.alts[i]) : sp.alts[i];
        View anchor = downView;
        if (anchor == null) return;
        showOptions(sp.alts, labels, Math.max(anchor.getWidth(), px(40)), 22, -1);
    }

    /**
     * Shows a row of choices above the pressed key; the finger slides to choose and lifts to pick.
     * If initial >= 0 that choice starts selected, directly above the finger.
     */
    private void showOptions(String[] options, String[] labels, int optW, int textSp, int initial) {
        View anchor = downView;
        if (anchor == null) return;
        hidePreview();
        popupOptions = options;
        popupOptW = optW;
        int h = px(54);
        popupRow = new LinearLayout(getContext());
        popupRow.setOrientation(HORIZONTAL);
        popupRow.setBackground(rounded(0xFF3A3F4B, px(8)));
        popupRow.setPadding(px(3), px(3), px(3), px(3));
        for (String label : labels) {
            TextView t = new TextView(getContext());
            t.setText(label);
            t.setTextColor(TEXT);
            t.setSingleLine(true);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
            t.setGravity(Gravity.CENTER);
            popupRow.addView(t, new LayoutParams(popupOptW, h - px(6)));
        }
        int w = popupOptW * popupOptions.length + px(6);
        popup = new PopupWindow(popupRow, w, h, false);
        popup.setClippingEnabled(false);
        popup.setTouchable(false);
        anchor.getLocationInWindow(loc);
        int[] sloc = new int[2];
        anchor.getLocationOnScreen(sloc);
        int x = initial >= 0
                ? loc[0] + Math.round(downX - sloc[0]) - px(3) - initial * popupOptW - popupOptW / 2
                : loc[0] + anchor.getWidth() / 2 - w / 2;
        View root = getRootView();
        x = Math.max(0, Math.min(x, root.getWidth() - w));
        int y = loc[1] - h - px(4);
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
        popupLeft = sloc[0] - (loc[0] - x) + px(3);
        popupSel = -1;
        selectOption(downX);
    }

    private void selectOption(float rawX) {
        if (popupRow == null) return;
        int i = (int) Math.floor((rawX - popupLeft) / popupOptW);
        i = Math.max(0, Math.min(popupOptions.length - 1, i));
        if (i == popupSel) return;
        popupSel = i;
        for (int k = 0; k < popupRow.getChildCount(); k++)
            popupRow.getChildAt(k).setBackground(k == i ? rounded(ACCENT_ON, px(6)) : null);
    }

    private void dismissOptions() {
        if (popup != null) {
            try { popup.dismiss(); } catch (RuntimeException ignored) { }
        }
        popup = null;
        popupRow = null;
        popupSel = -1;
    }

    /** Decodes the swipe; returns false if it typed nothing (too short, or no matching word). */
    private boolean finishGesture() {
        int k = 0;
        for (TextView t : charKeys) k = Math.max(k, ((Spec) t.getTag()).swipeIndex + 1);
        float[][] centers = new float[k][];
        float keyW = 0;
        for (TextView t : charKeys) {
            Spec sp = (Spec) t.getTag();
            if (sp.swipeIndex < 0) continue;
            KeyView kv = (KeyView) t;
            t.getLocationOnScreen(loc);
            centers[sp.swipeIndex] = new float[]{loc[0] + kv.insetLeft() + kv.visibleWidth() / 2f, loc[1] + t.getHeight() / 2f};
            keyW = Math.max(keyW, kv.visibleWidth() + px(4));  // centre-to-centre spacing of keys
        }
        int n = gesture.size();
        float[] xs = new float[n], ys = new float[n];
        float length = 0;
        for (int i = 0; i < n; i++) {
            xs[i] = gesture.get(i)[0];
            ys[i] = gesture.get(i)[1];
            if (i > 0) length += (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        }
        if (length < keyW * 0.9f) return false;  // a sliding tap, not a word
        int caseMode = capsLock ? 2 : shift ? 1 : 0;
        boolean typed = listener.onGesture(xs, ys, n, centers, keyW, caseMode);
        if (typed && shift && !capsLock) { shift = false; refreshLabels(); }
        return typed;
    }

    private void toggleLanguage() {
        if (langs.size() > 1) selectLanguage((lang + 1) % langs.size());
    }

    private void selectLanguage(int index) {
        if (index == lang || index < 0 || index >= langs.size()) return;
        lang = index;
        shift = false;
        capsLock = false;
        symbols = false;
        buildKeys();
        listener.onLanguageChanged(lang);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!swiping || gesture.size() < 2) return;
        getLocationOnScreen(loc);
        Path p = new Path();
        p.moveTo(gesture.get(0)[0] - loc[0], gesture.get(0)[1] - loc[1]);
        for (int i = 1; i < gesture.size(); i++) p.lineTo(gesture.get(i)[0] - loc[0], gesture.get(i)[1] - loc[1]);
        canvas.drawPath(p, trailPaint);
    }

    // ------------------------------------------------------------------ public state

    /** Resets transient layer state when a new field is focused. */
    public void resetLayer() {
        cancelTimers();
        hidePreview();
        dismissOptions();
        boolean rebuild = symbols;
        symbols = false; shift = false; capsLock = false; ctrl = false;
        setSuggestions(null);
        if (rebuild) buildKeys(); else refreshLabels();
    }

    /** Replaces the language list (e.g. after a language pack is installed or removed). */
    public void setLanguages(List<Lang> languages, int current) {
        langs.clear();
        langs.addAll(languages);
        lang = Math.max(0, Math.min(current, langs.size() - 1));
        buildKeys();
    }

    public void setLanguage(int index) {
        if (index == lang || index < 0 || index >= langs.size()) return;
        lang = index;
        buildKeys();
    }

    /**
     * @param insertLike  insert/replace mode (keys type text)
     * @param textEntry   insert/replace/command-line: show the chosen language; otherwise English command keys
     */
    public void setState(String modeLabel, int modeColor, boolean insertLike, boolean textEntry, String status) {
        this.insertLike = insertLike;
        this.modeLabel = modeLabel;
        modeBadge.setText(ctrl ? modeLabel + " \u2303" : modeLabel);
        modeBadge.setBackground(rounded(modeColor, px(4)));
        statusText.setText(status == null ? "" : status);
        if (textEntry != this.textEntry) {
            this.textEntry = textEntry;
            if (cur().layout != null) buildKeys(); else refreshLabels();
        }
        if (!insertLike) setSuggestions(null);
        deleteWord.setVisibility(insertLike ? VISIBLE : GONE);
    }

    /** Shows tappable alternatives for the last swiped word (null/empty hides them). */
    public void setSuggestions(List<String> words) {
        suggestionBar.removeAllViews();
        if (words == null || words.isEmpty()) {
            suggestionBar.setVisibility(GONE);
            statusText.setVisibility(VISIBLE);
            return;
        }
        for (final String w : words) {
            TextView chip = new TextView(getContext());
            chip.setText(w);
            chip.setTextColor(TEXT);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setBackground(rounded(KEY_SPECIAL, px(12)));
            LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(px(4), px(1), px(4), px(1));
            chip.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { listener.onPickSuggestion(w); }
            });
            suggestionBar.addView(chip, lp);
        }
        statusText.setVisibility(GONE);
        suggestionBar.setVisibility(VISIBLE);
    }
}
