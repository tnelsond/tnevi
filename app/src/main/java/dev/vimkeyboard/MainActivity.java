package dev.vimkeyboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Setup screen: enable/select the keyboard, settings, a practice field and a cheat sheet. */
public final class MainActivity extends Activity {

    private TextView status, packs;
    private float dp;

    private static final String CHEAT =
            "MODES     i a I A o O  insert   R replace   v V visual\n" +
            "          tap the mode badge: Insert <-> Normal   (Ctrl-[ / Ctrl-c = Esc)\n" +
            "MOVE      h j k l  w b e W B E ge  0 ^ $  gg G  { }  %  H M L\n" +
            "FIND      f F t T ; ,   / ? n N * #   ma `a 'a\n" +
            "OPERATORS d c y > < gu gU g~   + motion / text object\n" +
            "OBJECTS   iw aw iW i\" a\" i' i( a( i{ i[ i< ip ap is\n" +
            "EDIT      x X s S C D Y r J gJ ~ p P  Ctrl-a Ctrl-x\n" +
            "UNDO      u  Ctrl-r (↷)  .  repeat\n" +
            "REGISTERS \"a-\"z  \"A append  \"_ black hole  (unnamed = clipboard)\n" +
            "EX        :s/a/b/g  :%s  :'<,'>s  :g/re/d  :v/re/d  :sort  :12  :d  :y\n" +
            "          :w = send/submit   :q = hide keyboard   :wq / ZZ = both\n" +
            "LONG-PRESS top row % ^ ~ | [ ] < > { }   middle @ # & * - + = ( )   bottom _ $ \" ' : ; /   . ! ?\n" +
            "TOP BAR   mode badge = Insert/Normal   Normal: ctrl \u2191 \u2193 (history, cursor)   \u21B6 undo (hold: redo)\n" +
            "HISTORY   \u2191 in Normal opens : with the last command; \u2191 \u2193 in : and / browse history\n" +
            "          q-p % ^ ~ | [ ] < > { }   a-l @ # & * - + = ( )   z-m _ $ \" ' : ; /\n" +
            "LANGUAGE  tap space twice, slide on space, or long-press space for the list;\n" +
            "          Ctrl+Space on a hardware keyboard\n" +
            "          Khmer pack: \u21E7 for the corner character, long-press for extras\n" +
            "          command modes always show English keys\n" +
            "SWIPE     slide across the letters (English and Khmer packs); tap a suggestion to fix the word\n" +
            "          terminal apps get raw keys (Esc, Ctrl-x, arrows not remapped)";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = getSharedPreferences(VimKeyboardService.PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = px(20);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);

        TextView title = new TextView(this);
        title.setText("TneVi");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = text("Edit any text field with vi commands. The keyboard starts in Insert mode; tap the mode badge (top left) to switch to Normal mode and back.", 15);
        col.addView(sub);

        status = text("", 15);
        status.setPadding(0, px(12), 0, px(4));
        col.addView(status);

        Button enable = new Button(this);
        enable.setText("1.  Enable TneVi");
        enable.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)); }
        });
        col.addView(enable);

        Button pick = new Button(this);
        pick.setText("2.  Switch to TneVi");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
            }
        });
        col.addView(pick);

        col.addView(toggle("Start each field in Normal mode", VimKeyboardService.PREF_START_NORMAL, false, prefs));
        col.addView(toggle("Block cursor in Normal mode (selects the character under the cursor)",
                VimKeyboardService.PREF_BLOCK_CURSOR, true, prefs));

        TextView layoutTitle = text("English letter layout", 15);
        layoutTitle.setPadding(0, px(12), 0, 0);
        col.addView(layoutTitle);
        android.widget.Spinner layouts = new android.widget.Spinner(this);
        layouts.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, LatinLayouts.NAMES));
        layouts.setSelection(LatinLayouts.index(prefs.getString(VimKeyboardService.PREF_LATIN_LAYOUT, "qwerty")));
        layouts.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                prefs.edit().putString(VimKeyboardService.PREF_LATIN_LAYOUT, LatinLayouts.IDS[pos]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        col.addView(layouts);

        col.addView(toggle("Swipe typing", VimKeyboardService.PREF_SWIPE, true, prefs));
        col.addView(toggle("Next-word suggestions", VimKeyboardService.PREF_PREDICT, true, prefs));

        TextView packsTitle = text("Language packs", 17);
        packsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        packsTitle.setPadding(0, px(16), 0, px(4));
        col.addView(packsTitle);
        col.addView(text("Installed separately; they contain only data (no code, no permissions). "
                + "The keyboard itself has no internet access.", 13));
        packs = text("", 15);
        packs.setPadding(0, px(6), 0, 0);
        col.addView(packs);

        TextView tryIt = text("Practice here:", 15);
        tryIt.setPadding(0, px(16), 0, px(4));
        col.addView(tryIt);

        EditText field = new EditText(this);
        field.setTypeface(Typeface.MONOSPACE);
        field.setMinLines(6);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setText("The quick brown fox jumps over the lazy dog.\n\n" +
                "function greet(name) {\n    return \"Hello, \" + name;\n}\n\n" +
                "Try: ciw  dd  yyp  di(  ci\"  :%s/fox/cat/g  .  u");
        col.addView(field);

        TextView cheatTitle = text("Cheat sheet", 17);
        cheatTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cheatTitle.setPadding(0, px(16), 0, px(6));
        col.addView(cheatTitle);

        TextView cheat = text(CHEAT, 11);
        cheat.setTypeface(Typeface.MONOSPACE);
        cheat.setHorizontallyScrolling(true);
        cheat.setTextIsSelectable(true);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.addView(cheat);
        col.addView(hs);

        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refreshStatus();
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LanguagePacks.PACKAGES.length; i++) {
            LanguagePacks.Status st = LanguagePacks.status(this, LanguagePacks.PACKAGES[i]);
            sb.append(st == LanguagePacks.Status.INSTALLED ? "\u2713 " : "\u2717 ").append(LanguagePacks.PACK_NAMES[i]);
            if (st == LanguagePacks.Status.WRONG_SIGNATURE) sb.append("  (signed with a different key: ignored)");
            if (st == LanguagePacks.Status.NOT_INSTALLED) sb.append("  (not installed)");
            sb.append('\n');
        }
        packs.setText(sb.toString().trim());
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean enabled = false;
        if (imm != null) for (InputMethodInfo info : imm.getEnabledInputMethodList())
            if (info.getPackageName().equals(getPackageName())) enabled = true;
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected = current != null && current.startsWith(getPackageName() + "/");
        status.setText((enabled ? "✓ Enabled" : "✗ Not enabled yet") + "     "
                + (selected ? "✓ Selected" : "✗ Not selected"));
    }

    private int px(float v) { return Math.round(v * dp); }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private Switch toggle(String label, final String key, boolean def, final SharedPreferences prefs) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.setPadding(0, px(10), 0, px(10));
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { prefs.edit().putBoolean(key, on).apply(); }
        });
        return s;
    }
}
