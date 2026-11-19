package dev.vimkeyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
 * Khmer (NiDA) layers, long-press extras, and swipe (gesture) typing for English.
 */
@SuppressLint("ViewConstructor")
public final class VimKeyboardView extends LinearLayout {

    public interface Listener {
        void onKey(String key);
        void onLongPressSpace();
        void onGesture(float[] xs, float[] ys, int count, float[][] centers, float keyWidth, int caseMode);
        void onPickSuggestion(String word);
        void onLanguageChanged(boolean khmer);
        /** Mode badge tapped: switch between Insert and Normal (command) mode. */
        void onModeToggle();
    }

    private static final int BG = 0xFF15171C, KEY = 0xFF2B2F38, KEY_PRESSED = 0xFF4A505E,
            KEY_SPECIAL = 0xFF20232A, TEXT = 0xFFE9EAEE, TEXT_DIM = 0xFF9AA0AD, HINT = 0xFF7C8394,
            ACCENT_ON = 0xFF3B82F6, TRAIL = 0xCC4ADE80;

    /** Row heights (dp). Khmer has 4 letter rows and no number row: same total height as English. */
    private static final int NUM_H = 46, ROW_H = 54, KM_H = (NUM_H + 3 * ROW_H) / 4;

    private static final String SHIFT = "SHIFT", SYM = "SYM", CTRL = "CTRL", LANG = "LANG";
    private static final Set<String> NORMAL_REPEAT = new HashSet<>(Arrays.asList(
            "h", "j", "k", "l", "w", "b", "e", "x", "W", "B", "E", "u", "<C-r>", "<C-d>", "<C-u>"));

    /** A character key: what it types normally, with shift, and on long-press. */
    static final class Spec {
        final String base, shifted;
        final String[] alts;
        final boolean letter;
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

    /** Key with a small hint drawn in its top-right corner (like Gboard). */
    static final class KeyView extends TextView {
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String hint = "";
        KeyView(Context c, float dp) {
            super(c);
            hintPaint.setColor(0xFFC9CED8);
            hintPaint.setTextSize(13 * dp * c.getResources().getConfiguration().fontScale);
            hintPaint.setTextAlign(Paint.Align.RIGHT);
        }
        void setHint(String h) { hint = h == null ? "" : h; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (hint.isEmpty()) return;
            // Single-line centred TextViews scroll their content; draw relative to the visible area.
            float pad = getWidth() * 0.09f;
            float x = getScrollX() + getWidth() - pad;
            float y = getScrollY() + pad * 0.6f - hintPaint.ascent();
            float maxW = getWidth() * 0.8f;
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

    private TextView modeBadge, statusText;
    private String modeLabel;
    private LinearLayout suggestionBar, keyRows;
    private final List<TextView> charKeys = new ArrayList<>();
    private TextView shiftKey, ctrlKey, langKey, spaceKey;

    private boolean khmer, symbols, shift, capsLock, ctrl;
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

    public VimKeyboardView(Context ctx, Listener listener, boolean khmer) {
        super(ctx);
        this.listener = listener;
        this.khmer = khmer;
        this.dp = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        setPadding(px(3), px(2), px(3), px(6));
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

    private boolean khmerActive() { return khmer && textEntry && !symbols; }

    private void buildKeys() {
        keyRows.removeAllViews();
        charKeys.clear();
        ctrlKey = null;
        shiftKey = null;

        if (symbols && khmer && textEntry) {
            for (int r = 0; r < 4; r++) {
                LinearLayout row = row(KM_H);
                for (int i = 0; i < KhmerLayout.SYMBOLS[r].length; i++) {
                    String[] e = KhmerLayout.SYMBOLS[r][i];
                    if (r == 0) row.addView(numberKey(e[0], null, i));
                    else row.addView(charKey(Spec.of(e), 1f));
                }
                if (r == 3) row.addView(codeKey("\u232B", "<BS>", 1.4f, true));
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
        } else if (khmerActive()) {
            for (int r = 0; r < 4; r++) {
                LinearLayout row = row(KM_H);
                if (r == 3) { shiftKey = codeKey("\u21E7", SHIFT, 1.35f, true); row.addView(shiftKey); }
                for (String[] e : KhmerLayout.PHONE[r]) row.addView(charKey(Spec.of(e), 1f));
                if (r == 3) row.addView(codeKey("\u232B", "<BS>", 1.35f, true));
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
            spacer(r2, 0.5f);
            for (char c : "asdfghjkl".toCharArray()) r2.addView(letter(c));
            spacer(r2, 0.5f);
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
        return khmerActive() ? "◂ ខ្មែរ ▸" : "◂ English ▸";
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
        return charKey(new Spec(s(c), s(Character.toUpperCase(c)), alt, true), 1f);
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
        t.setBackground(bg);
        LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(px(2), px(3), px(2), px(3));
        t.setLayoutParams(lp);
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
        boolean km = khmerActive();
        boolean kmText = khmer && textEntry;
        for (TextView t : charKeys) {
            Spec sp = (Spec) t.getTag();
            boolean useShift = upper && sp.shifted != null;
            String main = useShift ? sp.shifted : sp.base;
            String label = kmText ? KhmerLayout.label(main) : main;
            StringBuilder hint = new StringBuilder();
            for (String a : sp.alts) {
                if (a.equals(main)) continue;
                if (hint.length() > 0) hint.append(',');
                hint.append(kmText && sp.alts.length == 1 && !a.startsWith("<") && !a.equals(CTRL) ? KhmerLayout.label(a) : altLabel(a));
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
        if (langKey != null) langKey.setText(khmer ? "EN" : "ខ្មែរ");
        if (spaceKey != null) spaceKey.setText(spaceLabel());
    }

    // ------------------------------------------------------------------ touch handling

    private final OnTouchListener touch = new OnTouchListener() {
        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    downView = v;
                    onDown(v.getTag(), ev.getRawX(), ev.getRawY());
                    showPreview(v);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    for (int h = 0; h < ev.getHistorySize(); h++) onMove(ev.getHistoricalAxisValue(MotionEvent.AXIS_X, h) + (ev.getRawX() - ev.getX()),
                            ev.getHistoricalAxisValue(MotionEvent.AXIS_Y, h) + (ev.getRawY() - ev.getY()));
                    onMove(ev.getRawX(), ev.getRawY());
                    if (swiping) { v.setPressed(false); hidePreview(); }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    onUp();
                    handler.postDelayed(hidePreviewLater, 70);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
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
                        showOptions(new String[]{"EN", "KM", "IME"},
                                new String[]{"English", "\u1781\u17D2\u1798\u17C2\u179A", "\u2328 more"},
                                px(92), 17, khmer ? 1 : 0);
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
        boolean canSwipe = insertLike && downSpec.letter && !khmerActive() && !symbols && !ctrl;
        if (!canSwipe) return;
        gesture.add(new float[]{x, y});
        if (!swiping) {
            float dx = x - downX, dy = y - downY;
            if (dx * dx + dy * dy > px(22) * px(22)) {
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
                else if (pick != null && pick.equals("KM") != khmer) toggleLanguage();
            } else if (!fired) send(" ", true);
        }
        else if (kind == DOWN_CHAR) {
            if (popup != null) {
                String pick = popupSel >= 0 ? popupOptions[popupSel] : null;
                dismissOptions();
                if (pick != null) send(pick, false);
            } else if (swiping) finishGesture();
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
            label = khmer && textEntry ? KhmerLayout.label(main) : main;
        } else {
            label = ((TextView) key).getText().toString();
            if (label.length() != 1 || ((String) tag).startsWith("<")) { hidePreview(); return; }
        }
        if (key.getWidth() == 0) return;
        int w = key.getWidth() + px(14), h = key.getHeight() + px(34);
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
        previewText.setTextSize(TypedValue.COMPLEX_UNIT_SP, khmer && textEntry ? 32 : 30);
        previewText.setPadding(0, px(6), 0, 0);
        key.getLocationInWindow(loc);
        int x = loc[0] - (w - key.getWidth()) / 2;
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
        boolean km = khmer && textEntry;
        String[] labels = new String[sp.alts.length];
        for (int i = 0; i < labels.length; i++) labels[i] = km ? KhmerLayout.label(sp.alts[i]) : sp.alts[i];
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

    private void finishGesture() {
        float[][] centers = new float[26][];
        float keyW = 0;
        for (TextView t : charKeys) {
            Spec sp = (Spec) t.getTag();
            if (!sp.letter) continue;
            t.getLocationOnScreen(loc);
            centers[sp.base.charAt(0) - 'a'] = new float[]{loc[0] + t.getWidth() / 2f, loc[1] + t.getHeight() / 2f};
            keyW = Math.max(keyW, t.getWidth() + px(4));
        }
        int n = gesture.size();
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) { xs[i] = gesture.get(i)[0]; ys[i] = gesture.get(i)[1]; }
        int caseMode = capsLock ? 2 : shift ? 1 : 0;
        if (shift && !capsLock) { shift = false; refreshLabels(); }
        listener.onGesture(xs, ys, n, centers, keyW, caseMode);
    }

    private void toggleLanguage() {
        khmer = !khmer;
        shift = false;
        capsLock = false;
        symbols = false;
        buildKeys();
        listener.onLanguageChanged(khmer);
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

    public boolean isKhmer() { return khmer; }

    public void setKhmer(boolean k) {
        if (k == khmer) return;
        khmer = k;
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
            if (khmer) buildKeys(); else refreshLabels();
        }
        if (!insertLike) setSuggestions(null);
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
