# tnevi — a vi keyboard for Android

An Android keyboard (input method) that lets you edit any text field with vi commands:
Normal, Insert, Replace, Visual, Visual-line and `:` command-line modes.

## Install

1. Install `VimKeyboard.apk` (allow "install unknown apps" for your browser/file manager).
2. Open **Vim Keyboard** → tap **Enable Vim Keyboard** → turn it on.
3. Tap **Switch to Vim Keyboard** (or long-press the space bar later to switch keyboards).

Fields start in **Insert** mode so it works as a normal keyboard. Tap the **mode badge** (top left: INSERT / NORMAL …) to switch between Insert and Normal mode. `Ctrl-[` and `Ctrl-c` also work as Esc.
The setup screen has a toggle to start every field in Normal mode.

## The keyboard

- Status bar: mode badge (green INSERT, blue NORMAL, amber VISUAL, purple COMMAND, red REPLACE)
  plus pending keys / the command line / messages.
- **Long-press characters** (shown in each key's corner, no duplicates, Gboard's standard spots):
  - `q–p` → `% ^ ~ | [ ] < > { }`
  - `a–l` → `@ # & * - + = ( )`
  - `z–m` → `_ $ " ' : ; /`
  - `.` → `!` `?` (slide to pick)
  - `1` ctrl · `2` ↑ · `3` ↷ redo · `4` ↓ · `9` `` ` `` · `0` `\` (↑ ↓ repeat when held)
  - These work in every mode, including Normal (e.g. long-press `x` for `$`, `b` for `:`, `m` for `/`).
- ctrl is one-shot: long-press `1`, then `r` sends `<C-r>`, `[` sends Esc. While it's on, the 1 key is highlighted and the mode badge shows ⌃.
- The bottom row has `?123 , space . ⏎`.
- Hold ⌫ to repeat. Letters don't repeat when held, because long-press types the corner character; use counts like `5j` instead.
- In Normal mode the character under the cursor is selected to imitate a block cursor (can be turned off).
- Tapping/dragging in the field moves the cursor; dragging a selection in Normal mode enters Visual mode.
- Hardware keyboards work too (Esc, Ctrl-[, all vi keys).
- Terminal apps (fields with no text model, e.g. Termux) get raw keys: esc, ctrl-x, enter, backspace.

## Khmer keyboard

- Layout: Gboard's Khmer **ដើម** phone layout. It has 4 rows of 10 keys:
  - `ក ខ គ ឃ ង ច ឆ ជ ឈ ញ`
  - `ដ ◌ិ ◌ី ◌ឹ ណ ត ថ ទ ធ ន`
  - `ប ផ ព ភ ម ◌្ យ រ ល វ`
  - `⇧ ស ហ ឡ អ ◌់ ◌៉ ◌៊ ◌័ ។ ›|‹ ⌫`
- The small character in a key's corner is its second character.
  - **⇧** types it.
  - **Long-press** types it straight away. On keys with 2 choices (ឥ,ឦ · ឧ,ឩ · ឪ,ឳ · ឱ,ឲ) long-press opens a popup; slide to your choice and let go.
- **›|‹** types a zero-width space (an invisible word break).
- **?123** in Khmer shows:
  - Khmer digits ១–០
  - Khmer signs ៗ ៛ « » ៙ ៚ ៘ ៎ ៏ ៝
  - the usual punctuation
- In Normal and Visual modes the keyboard always shows English keys, so vi commands work.
  Insert mode and the `:` / `/` command line use the language you picked.
- `h l x` and other motions treat a Khmer cluster (e.g. ខ្មែ) as one character. A zero-width space counts as a word break for `w b e`.
- Physical keyboards use the standard NiDA computer layout for Khmer.

### Switching language

- **Long-press space** to open a menu with **English**, **ខ្មែរ** and **⌨ more** (the system keyboard picker). Slide to one and let go.
- Or slide left/right on the space bar to flip between English and Khmer.
- On a hardware keyboard, **Ctrl+Space** switches.

The keyboard remembers your last choice.

### Backspace in Khmer

In Insert mode, backspace removes one component at a time, like Gboard: ខ្មែ → ខ្ម → ខ្ → ខ.
In Normal mode, `x`, `h` and `l` still treat a whole cluster as one character.

## Swipe typing and next-word suggestions (English)

- Slide your finger across the letters without lifting. A green trail follows your finger, and the word is typed with a space before it when needed.
- **Context:** the decoder looks at the 1–2 words before the cursor (an n-gram language model), so "I'll see you" beats "I'll see toy", and "the United" suggests "States".
- Up to 3 alternatives appear in the status bar; tap one to replace the word.
- **Next-word suggestions:** after you type a space, the bar shows 3 likely next words. Tap one to insert it, and the next 3 appear.
- Shift or caps lock capitalises the swiped word. Words at the start of a sentence are capitalised in fields that ask for it.
- **Vocabulary:** 40,000 English words, checked against a spelling dictionary. That leaves out:
  - acronyms like tv, usa, bbc and lol
  - abbreviations like etc., govt and Oct
  - web tokens like http
  - stray 1–2 letter fragments

  Proper nouns keep their capitals (London, David). Contractions are included (don't, I'm, it's).
- **Measured accuracy** (`test/SwipeContextTest.java`: 4,000 words from held-out sentences, simulated gestures, same vocabulary for both rows):

  | | first guess right | right word in top 3 |
  |---|---|---|
  | careful swipes, frequency only (v1.9) | 91.9% | 98.0% |
  | careful swipes, n-gram context (v2.0) | 93.3% | 98.7% |
  | sloppy swipes, frequency only (v1.9) | 74.0% | 83.5% |
  | sloppy swipes, n-gram context (v2.0) | 84.2% | 92.8% |

- Swipe and suggestions are English only; Khmer is tap-typing. Both can be turned off on the setup screen.
- Suggestions don't appear in password, email or URL fields.

## Supported commands

| | |
|---|---|
| Modes | `i a I A o O gI` · `R` · `v V gv` · mode badge / `Ctrl-[` / `Ctrl-c` |
| Motions | `h j k l` `w W b B e E ge gE` `0 ^ $ g_` `gg G` `{ }` `%` `H M L` `f F t T ; ,` `+ - _` `Ctrl-d Ctrl-u` |
| Search | `/ ? n N * #` (Java regex, smartcase, `\c` `\C`, `\<word\>`, vim-style `\(\)` groups) |
| Operators | `d c y > < gu gU g~` with any motion, text object, count, or search (`d/foo`) |
| Text objects | `iw aw iW aW is as ip ap i" a" i' a'` ``i` a` `` `i( a( ib i{ a{ iB i[ a[ i< a<` |
| Edits | `x X s S C D Y r J gJ ~ p P` `Ctrl-a Ctrl-x` · counts everywhere (`3dw`, `2d3w`, `5x`) |
| Undo | `u` `U` `Ctrl-r`, `.` repeat (including inserts and `d/pattern`) |
| Registers | `"a`–`"z`, `"A` (append), `"_` black hole, `"0` last yank; unnamed register ↔ system clipboard |
| Marks | `ma` `` `a `` `'a` `''` |
| Visual | all motions/objects, `o`, `d x c s y > < ~ u U r J p I A`, `:` with `'<,'>` |
| Ex | `:s/pat/rep/gi` `:%s` `:'<,'>s` `:&` `&` `:g/re/d` `:v/re/d` `:d` `:y` `:j` `:>` `:<` `:sort[!] [i\|n\|u]` `:N` `:N,M` `:.` `:$` |
| History | `↑` in Normal mode opens `:` with your last command; `↑` `↓` in `:` / `/` / `?` step through earlier entries (only those starting with what you've typed). Kept across restarts. |
| Field actions | `:w` = press the field's action (Send / Search / Go / Done), `:q` = hide keyboard, `:wq` / `ZZ` = both |

Indent (`>`/`<`) uses 4 spaces.

## Building

The APK was built with the plain SDK command-line tools:

```sh
./build.sh          # runs engine tests, then aapt → javac → dx/d8 → zipalign → apksigner
```

On Debian/Ubuntu: `apt install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23`.

It is also a normal Gradle project: open it in Android Studio, or run `./gradlew assembleDebug`.
Pushing to GitHub builds the APK via `.github/workflows/build.yml`.

## Code

- `VimEngine.java` — the vi emulator. Pure Java (no Android imports), edits through a small `Host` interface.
- `VimKeyboardService.java` — the `InputMethodService`; maps engine edits onto the `InputConnection`,
  syncs the cursor when you tap the field, handles hardware keys and terminal pass-through.
- `VimKeyboardView.java` — the soft keyboard (English/Khmer layers, long-press, swipe trail, suggestions).
- `KhmerLayout.java` — the NiDA Khmer layout table (also used for hardware keyboards).
- `SwipeDecoder.java` — gesture decoder (pure Java).
- `LanguageModel.java` — loads `assets/en_lm.bin` (vocabulary, unigram/bigram/trigram costs) and predicts next words.
- `tools/build_lm.py` — rebuilds `en_lm.bin` from wordfreq, a spelling dictionary and the NLTK corpora (see the script header).
- `MainActivity.java` — setup screen, settings, practice field and cheat sheet.
- `test/SwipeContextTest.java` — swipe accuracy with/without context on held-out sentences (`test/lm_heldout.txt`).
- `test/VimEngineTest.java` — 168 behaviour tests (`java VimEngineTest`, no Android needed).

## Known limitations

- Undo history belongs to the keyboard, so it resets when you move to another field and doesn't include
  edits the app makes on its own.
- No visual-block mode (`Ctrl-v`), macros (`q`), or `:normal`.
- Some apps (certain web views and custom editors) report only part of their text to keyboards;
  motions then work within the visible window around the cursor.

## Data and licences

The code is CC0 (see `LICENSE`). `app/src/main/assets/en_lm.bin` is derived from third-party data:

- [wordfreq](https://github.com/rspeer/wordfreq) word frequencies (CC BY-SA 4.0): vocabulary and unigram probabilities.
- The `wamerican-large` spelling list (SCOWL, permissive licence): filters out acronyms and junk.
- [NLTK corpora](https://www.nltk.org/nltk_data/) for bigram/trigram counts: Brown, Reuters-21578, webtext, NPS Chat,
  Gutenberg selections, movie reviews and ABC. Several of these are licensed for research or non-commercial use only.

Only aggregate n-gram statistics are shipped, not the texts.
