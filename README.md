# TneVi — a vi keyboard for Android

An Android keyboard (input method) that lets you edit any text field with vi commands:
Normal, Insert, Replace, Visual, Visual-line and `:` command-line modes.

## Install

TneVi is split into small apps so the keyboard itself stays tiny and offline:

| APK | What it is | Size |
|---|---|---|
| `tnevi.apk` | the keyboard: vi editing, English QWERTY layout | 74 KB |
| `tnevi-pack-en.apk` | English swipe typing + next-word suggestions | 2.8 MB |
| `tnevi-pack-km.apk` | Khmer keyboard (Gboard ដើម layout) with swipe + suggestions | 4.6 MB |

1. Install `tnevi.apk`, plus any packs you want. Your browser or file manager needs permission to install unknown apps.
2. Open **TneVi** → tap **Enable TneVi** → turn it on.
3. Tap **Switch to TneVi** (or long-press the space bar later to switch keyboards).

The setup screen lists which language packs are installed. Packs are picked up automatically the next time a text field opens.

**No internet:** none of the apps request any permission, including INTERNET, so Android won't let them open a network connection. Packs contain only data (`hasCode="false"`: no code, no components). The keyboard reads them with `createPackageContext()`, and only accepts packs signed with the same key as itself. Another app can't pose as a pack.

Fields start in **Insert** mode so it works as a normal keyboard. Tap the **mode badge** (top left: INSERT / NORMAL …) to switch between Insert and Normal mode. `Ctrl-[` and `Ctrl-c` also work as Esc.
The setup screen has a toggle to start every field in Normal mode.

## The keyboard

- **Top bar:**
  - **Mode badge:** green INS, blue NOR, amber VIS / V-L, purple CMD, red REP. Tap it to switch between Insert and Normal mode.
  - **Middle:** pending keys, the command line, messages, or swipe and next-word suggestions.
  - **ctrl** (command modes): one-shot. Tap ctrl, then `r` sends `<C-r>` and `a` sends `<C-a>`.
  - **↑ ↓** (command modes): cursor movement and `:` / `/` history. Hold them to repeat.
  - **↶ undo:** in Insert mode it deletes the word you just swiped, with its space. Otherwise it deletes the word before the cursor; in Khmer it finds the last dictionary word. Hold it to keep deleting. In Normal and Visual mode it runs vim's `u`, and holding it runs redo (`Ctrl-r`).
- **Letter layouts** (setup screen → *English letter layout*): QWERTY, Colemak, Colemak-DH, Dvorak, Workman, QWERTZ (German) and AZERTY (French). Swipe typing works on all of them.
- **Number row** above the letters in every mode (Khmer uses its own 4-row layout; its digits are on ?123).
- **Long-press characters** (shown in each key's corner; positions follow Gboard; they work in every mode):
  - top row → `% ^ ~ | [ ] < > { }`
  - middle row → `@ # & * - + = ( )`
  - bottom row → `_ $ " ' : ; /`
  - `.` → `!` `?`
  - QWERTZ adds ä ö ü ß. AZERTY adds é è ê ë à â ç ù û î ï ô œ.
  - The ?123 page has digits and every other symbol, including `` ` `` and `\`.
- The bottom row has `?123 , space . ⏎`.
- Hold ⌫ to repeat. Letters don't repeat when held, because long-press types the corner character; use counts like `5j` instead.
- In Normal mode the character under the cursor is selected to imitate a block cursor (can be turned off).
- Tapping/dragging in the field moves the cursor; dragging a selection in Normal mode enters Visual mode.
- Hardware keyboards work too (Esc, Ctrl-[, all vi keys).
- Terminal apps (fields with no text model, e.g. Termux) get raw keys: esc, ctrl-x, enter, backspace.

## Khmer keyboard (Khmer pack)

- Layout: Gboard's Khmer **ដើម** phone layout. It has 4 rows of 10 keys:
  - `ក ខ គ ឃ ង ច ឆ ជ ឈ ញ`
  - `ដ ◌ិ ◌ី ◌ឹ ណ ត ថ ទ ធ ន`
  - `ប ផ ព ភ ម ◌្ យ រ ល វ`
  - `⇧ ស ហ ឡ អ ◌់ ◌៉ ◌៊ ◌័ ។ ›|‹ ⌫`
- The small character in a key's corner is its second character.
  - **⇧** types it.
  - **Long-press** types it straight away. On keys with 2 choices (ឥ,ឦ · ឧ,ឩ · ឪ,ឳ · ឱ,ឲ, plus ◌៌,◌៎ and ◌៍,◌៏) long-press opens a popup; slide to your choice and let go.
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

- **Tap space twice** quickly to switch to the next language. The first space is taken back.
- **Long-press space** to open a menu of the installed languages plus **⌨ more** (the system keyboard picker). Slide to one and let go.
- Or slide left/right on the space bar to go to the next language.
- On a hardware keyboard, **Ctrl+Space** cycles languages.

The keyboard remembers your last choice.

### Backspace in Khmer

In Insert mode, backspace removes one component at a time, like Gboard: ខ្មែ → ខ្ម → ខ្ → ខ.
In Normal mode, `x`, `h` and `l` still treat a whole cluster as one character.

## Swipe typing and next-word suggestions (English and Khmer packs)

- Slide your finger across the letters without lifting. A green trail follows your finger.
- **Spaces:** like Gboard, a swiped word (or a tapped suggestion) is followed by a space, so you can swipe the next word straight away.
  - Typing closing punctuation (`. , ! ? ; : ) ] }` …) right after it removes that space: "hello ." becomes "hello.".
  - Tapping space right after it doesn't add a second one.
  - In Khmer the break is a zero-width space, the invisible break Khmer uses instead of visible spaces, and ។ ៕ ៖ attach the same way. Tapping space turns it into a real space.
- **↶ undo** (top bar) takes back the word you just swiped, as described above.
- **Khmer swipes** follow typing order through the ដើម keys, including ◌្ for subscripts. For example, ខ្មែរ is ខ → ◌្ → ម → ◌ែ (on ព) → រ. Shifted characters belong to their key, so ែ is swiped through the ព key.
- **Context:** the decoder looks at the 1–2 words before the cursor (an n-gram language model). So "I'll see you" beats "I'll see toy", and "the United" suggests "States". Khmer text has no spaces, so the words before the cursor are found by splitting the text with the pack's dictionary.
- Up to 3 alternatives appear in the status bar; tap one to replace the word.
- **Next-word suggestions:** after a space (or ›|‹ in Khmer), the bar shows 3 likely next words. Tap one to insert it, and the next 3 appear.
- Shift or caps lock capitalises a swiped English word. English words at the start of a sentence are capitalised in fields that ask for it.
- **English vocabulary:** 40,000 words, checked against a spelling dictionary. That leaves out acronyms (tv, usa, lol), abbreviations (govt, Oct), web tokens (http) and stray 1–2 letter fragments. Proper nouns keep their capitals; contractions are included.
- **Khmer vocabulary:** 23,655 words from about 37 million words of segmented Khmer Wikipedia and web text. Words must be well-formed and typeable on the layout, and must appear in the SIL Khmer word lists unless common in the text. Buddhist scripture, which makes up much of the web text, is filtered out so suggestions reflect everyday Khmer.
- **Measured accuracy** (`test/SwipeContextTest.java`: 4,000 words from held-out sentences, simulated gestures, n-gram context on):

  | | first guess right | right word in top 3 |
  |---|---|---|
  | English, careful swipes | 93.5% | 98.6% |
  | English, sloppy swipes | 84.3% | 93.4% |
  | Khmer, careful swipes | 95.9% | 99.4% |
  | Khmer, sloppy swipes | 87.7% | 93.9% |

  Without context (frequency only) the first guess is 91.2% / 79.7% for English and 93.1% / 81.2% for Khmer.
- Swipe and suggestions can be turned off on the setup screen. Suggestions don't appear in password, email or URL fields.

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

```sh
./build.sh   # engine tests, swipe accuracy tests, then build/tnevi.apk and build/tnevi-pack-{en,km}.apk
```

It uses the plain SDK command-line tools. On Debian/Ubuntu: `apt install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23`.
All 3 APKs are signed with `debug.keystore`, which is created on first build and kept out of git.

It is also a Gradle project with modules `:app`, `:pack-en` and `:pack-km`: `./gradlew assembleDebug assembleRelease`.

### Releases and signing

Every push builds on GitHub (`.github/workflows/build.yml`). Publishing a release on the **Releases** page (any tag name) builds, signs and attaches the keyboard and both packs.

Release APKs are signed with the repository's own key, stored only in its Actions secrets:
- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`

The same key has to sign every release, so that updates install over each other and the keyboard accepts the packs. A release published without these secrets gets an error instead of APKs that couldn't be updated later.

To make the key with `openssl` (preinstalled on most Linux/macOS; Termux: `pkg install openssl-tool`):
```sh
openssl req -x509 -newkey rsa:4096 -nodes -keyout key.pem -out cert.pem -days 36500 -subj "/CN=tnevi"
openssl pkcs12 -export -inkey key.pem -in cert.pem -name tnevi -out tnevi-release.p12   # asks for a password
rm key.pem cert.pem
base64 -w0 tnevi-release.p12   # paste the output into the KEYSTORE_BASE64 secret
```
Set `KEYSTORE_PASSWORD` to the password you chose and `KEY_ALIAS` to `tnevi`.
(With a JDK, `keytool -genkeypair -keystore tnevi-release.jks -alias tnevi -keyalg RSA -keysize 4096 -validity 36500` does the same.)
Keep a backup of the `.p12` file and its password: without them no one can sign an update.

Builds without the secret (pull requests, forks) still produce installable debug APKs as workflow artifacts. Those are signed with a throwaway key, so install the keyboard and packs from the same run.

Rebuilding the language data (`packs/*/assets/lm.bin`) is described at the top of `tools/build_lm.py`.

## Code

- `VimEngine.java`: the vi emulator. Pure Java (no Android imports), edits through a small `Host` interface.
- `VimKeyboardService.java`: the `InputMethodService`.
  - Maps engine edits onto the `InputConnection`.
  - Syncs the cursor when you tap the field.
  - Handles hardware keys, terminal pass-through, swipe and suggestions.
- `VimKeyboardView.java`: the soft keyboard: QWERTY, pack layouts, long-press, previews, swipe trail, suggestions.
- `LatinLayouts.java`: QWERTY, Colemak, Dvorak and the other built-in letter layouts, with their long-press characters.
- `LanguagePacks.java`: finds installed packs, checks their signature and loads them.
- `Layout.java`: parses a pack's `layout.txt` (rows, long-press options, hardware map) and maps words to swipe key paths.
- `SwipeDecoder.java`: the gesture decoder, for any key layout (pure Java).
- `LanguageModel.java`: loads `lm.bin` (vocabulary plus unigram/bigram/trigram costs), predicts next words and finds context, including dictionary segmentation for Khmer.
- `MainActivity.java`: setup screen, settings, pack status, practice field and cheat sheet.
- `packs/en`, `packs/km`: language packs (`AndroidManifest.xml`, `assets/layout.txt`, `assets/lm.bin`).
- `tools/build_lm.py`: builds a pack's `lm.bin` and held-out test sentences.
- `test/VimEngineTest.java`: 168 behaviour tests (`java VimEngineTest`, no Android needed).
- `test/SwipeContextTest.java`: swipe accuracy with and without context on held-out sentences.

### Adding a language

1. Create `packs/<id>/` like `packs/km`, with a `layout.txt` (format at the top of `Layout.java`).
2. Build `lm.bin` with a new function in `tools/build_lm.py`.
3. Add the package name to `LanguagePacks.PACKAGES` and to `<queries>` in the keyboard's manifest.

## Known limitations

- Undo history belongs to the keyboard, so it resets when you move to another field and doesn't include
  edits the app makes on its own.
- No visual-block mode (`Ctrl-v`), macros (`q`), or `:normal`.
- Some apps (certain web views and custom editors) report only part of their text to keyboards;
  motions then work within the visible window around the cursor.

## Data and licences

The code is CC0 (see `LICENSE`). The pack data files (`packs/*/assets/lm.bin`) are derived from third-party data.
They contain only vocabulary and aggregate n-gram statistics, not the source texts:

- English
  - [wordfreq](https://github.com/rspeer/wordfreq) word frequencies (CC BY-SA 4.0)
  - the `wamerican-large` spelling list (SCOWL)
  - [NLTK corpora](https://www.nltk.org/nltk_data/): Brown, Reuters-21578, webtext, NPS Chat, Gutenberg selections, movie reviews, ABC. Several are licensed for research or non-commercial use only.
- Khmer
  - word-segmented Khmer Wikipedia (CC BY-SA) and OSCAR web text (Common Crawl derived), from [phylypo/khmer-text-data](https://github.com/phylypo/khmer-text-data)
  - [SIL Khmer line-breaking word lists](https://github.com/silnrsi/khmerlbdict) (MIT)
