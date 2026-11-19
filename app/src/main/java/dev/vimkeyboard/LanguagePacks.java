package dev.vimkeyboard;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Language packs are separate, code-free APKs (no permissions) whose assets hold a layout file and
 * an n-gram model. The keyboard reads them with createPackageContext() and only accepts packs
 * signed with the keyboard's own key, so other apps can't inject layouts or word lists.
 */
final class LanguagePacks {
    private LanguagePacks() { }

    /** Known packs, in menu order (English is built in; its pack only adds swipe/predictions). */
    static final String[] PACKAGES = {"dev.vimkeyboard.pack.en", "dev.vimkeyboard.pack.km"};
    static final String[] PACK_NAMES = {"English swipe & suggestions", "Khmer keyboard (ខ្មែរ)"};

    static final class Language {
        final String id;
        String name;
        /** Null for the built-in QWERTY layout. */
        Layout layout;
        String separator = " ";
        LanguageModel lm;
        SwipeDecoder decoder;

        Language(String id, String name) { this.id = id; this.name = name; }

        int[] context(CharSequence before) { return lm.contextOf(before, layout); }
    }

    enum Status { NOT_INSTALLED, INSTALLED, WRONG_SIGNATURE }

    static Status status(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            pm.getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return Status.NOT_INSTALLED;
        }
        return pm.checkSignatures(ctx.getPackageName(), pkg) == PackageManager.SIGNATURE_MATCH
                ? Status.INSTALLED : Status.WRONG_SIGNATURE;
    }

    /** Changes whenever a pack is installed, updated or removed. */
    static long fingerprint(Context ctx) {
        long f = 17;
        for (String pkg : PACKAGES) {
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
                f = f * 31 + pi.lastUpdateTime;
            } catch (PackageManager.NameNotFoundException e) {
                f = f * 31;
            }
        }
        return f;
    }

    /** English (built in) plus every installed, correctly signed pack. Slow: call off the UI thread. */
    static List<Language> load(Context ctx) {
        List<Language> out = new ArrayList<>();
        Language english = new Language("en", "English");
        out.add(english);
        for (String pkg : PACKAGES) {
            if (status(ctx, pkg) != Status.INSTALLED) continue;
            try {
                Context pc = ctx.createPackageContext(pkg, 0);
                Layout layout;
                InputStream in = pc.getAssets().open("layout.txt");
                try { layout = Layout.parse(in); } finally { in.close(); }
                String id = pkg.substring(pkg.lastIndexOf('.') + 1);
                Language lang = id.equals("en") ? english : new Language(id, layout.name);
                lang.name = layout.name;
                lang.separator = layout.separator;
                lang.layout = layout.letters == null ? null : layout;
                LanguageModel lm = LanguageModel.load(pc.getAssets().open("lm.bin"));
                List<String> words = new ArrayList<>(lm.size());
                for (int i = 1; i < lm.size(); i++) words.add(lm.word(i));
                lang.decoder = lang.layout == null
                        ? new SwipeDecoder(words)
                        : new SwipeDecoder(words, layout.keyer(), layout.swipeKeyCount());
                lang.lm = lm;
                if (lang != english) out.add(lang);
            } catch (PackageManager.NameNotFoundException | IOException | RuntimeException e) {
                Log.e("VimKeyboard", "language pack " + pkg, e);
            }
        }
        return out;
    }
}
