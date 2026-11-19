#!/usr/bin/env bash
# Builds a signed APK without Gradle, using the Android SDK command-line tools.
# Needs: JDK, android.jar (API 23+), aapt, dx (or d8), zipalign, apksigner.
# On Debian/Ubuntu:  apt install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-/usr/lib/android-sdk}
ANDROID_JAR=${ANDROID_JAR:-$(ls -d "$SDK"/platforms/*/android.jar | sort -V | tail -1)}
BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)
AAPT=${BT}aapt; [ -x "$AAPT" ] || AAPT=aapt
ZIPALIGN=${BT}zipalign; [ -x "$ZIPALIGN" ] || ZIPALIGN=zipalign
APKSIGNER=${BT}apksigner; [ -x "$APKSIGNER" ] || APKSIGNER=apksigner
if [ -x "${BT}d8" ]; then DEX="d8"; elif [ -x "${BT}dx" ]; then DEX="dx"; else DEX="dx-path"; fi

OUT=build
SRC=app/src/main
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/test"

echo "== unit tests"
javac -nowarn -d "$OUT/test" "$SRC/java/dev/vimkeyboard/VimEngine.java" test/VimEngineTest.java
java -cp "$OUT/test" VimEngineTest
javac -nowarn -d "$OUT/test" "$SRC/java/dev/vimkeyboard/SwipeDecoder.java" "$SRC/java/dev/vimkeyboard/LanguageModel.java" test/SwipeContextTest.java
java -cp "$OUT/test" SwipeContextTest "$SRC/assets/en_lm.bin" test/lm_heldout.txt 0.07

echo "== resources"
# Gradle keeps package/version/sdk in app/build.gradle; inject them for aapt.
sed -e 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.vimkeyboard" android:versionCode="11" android:versionName="2.0">\n    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="34" />#' \
    "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT" package -f -M "$OUT/AndroidManifest.xml" -S "$SRC/res" -A "$SRC/assets" -I "$ANDROID_JAR" -J "$OUT/gen" -F "$OUT/app.unsigned.apk"

echo "== compile"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
      -d "$OUT/classes" $(find "$SRC/java" "$OUT/gen" -name '*.java')

echo "== dex"
case $DEX in
  d8) "${BT}d8" --min-api 21 --output "$OUT" $(find "$OUT/classes" -name '*.class') ;;
  dx) "${BT}dx" --dex --output="$OUT/classes.dex" "$OUT/classes" ;;
  *)  dalvik-exchange --dex --output="$OUT/classes.dex" "$OUT/classes" ;;
esac
(cd "$OUT" && "$AAPT" add app.unsigned.apk classes.dex >/dev/null)

echo "== sign"
KEYSTORE=${KEYSTORE:-debug.keystore}
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias vimkb \
          -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Vim Keyboard" >/dev/null 2>&1
fi
"$ZIPALIGN" -f 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
             --out "$OUT/VimKeyboard.apk" "$OUT/app.aligned.apk"
"$APKSIGNER" verify "$OUT/VimKeyboard.apk"
echo "Built $OUT/VimKeyboard.apk"
