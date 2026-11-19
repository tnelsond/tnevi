#!/usr/bin/env bash
# Builds the keyboard and the language packs without Gradle, using the Android SDK command-line tools:
#   build/tnevi.apk                 the keyboard (no permissions)
#   build/tnevi-pack-en.apk         English swipe + suggestions (data only)
#   build/tnevi-pack-km.apk         Khmer layout, swipe + suggestions (data only)
# All three must be signed with the same key: the keyboard ignores packs signed differently.
#
# Needs: JDK, android.jar (API 23+), aapt, dx (or d8), zipalign, apksigner.
# On Debian/Ubuntu:  apt install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=13 VERSION_NAME=3.2
PACK_VERSION_CODE=1 PACK_VERSION_NAME=1.0

SDK=${ANDROID_HOME:-/usr/lib/android-sdk}
ANDROID_JAR=${ANDROID_JAR:-$(ls -d "$SDK"/platforms/*/android.jar | sort -V | tail -1)}
BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)
AAPT=${BT}aapt; [ -x "$AAPT" ] || AAPT=aapt
ZIPALIGN=${BT}zipalign; [ -x "$ZIPALIGN" ] || ZIPALIGN=zipalign
APKSIGNER=${BT}apksigner; [ -x "$APKSIGNER" ] || APKSIGNER=apksigner
if [ -x "${BT}d8" ]; then DEX="d8"; elif [ -x "${BT}dx" ]; then DEX="dx"; else DEX="dx-path"; fi

OUT=build
SRC=app/src/main
J=$SRC/java/dev/vimkeyboard
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/test"

KEYSTORE=${KEYSTORE:-debug.keystore}
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias vimkb \
          -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Vim Keyboard" >/dev/null 2>&1
fi

sign() { # unsigned.apk out.apk
  "$ZIPALIGN" -f 4 "$1" "$1.aligned"
  "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --out "$2" "$1.aligned"
  "$APKSIGNER" verify "$2"
}

# Gradle keeps package/version/sdk in build.gradle; inject them into the manifest for aapt.
manifest() { # src package versionCode versionName out
  sed -e "s#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$2\" android:versionCode=\"$3\" android:versionName=\"$4\">\n    <uses-sdk android:minSdkVersion=\"21\" android:targetSdkVersion=\"34\" />#" "$1" > "$5"
}

echo "== unit tests"
javac -nowarn -d "$OUT/test" "$J/VimEngine.java" "$J/SwipeDecoder.java" "$J/LanguageModel.java" "$J/Layout.java" test/*.java
java -cp "$OUT/test" VimEngineTest
for L in en km; do
  echo "-- swipe accuracy ($L)"
  java -cp "$OUT/test" SwipeContextTest packs/$L/assets/lm.bin test/lm_heldout_$L.txt packs/$L/assets/layout.txt 0.07
done

echo "== keyboard"
manifest "$SRC/AndroidManifest.xml" dev.vimkeyboard $VERSION_CODE $VERSION_NAME "$OUT/AndroidManifest.xml"
"$AAPT" package -f -M "$OUT/AndroidManifest.xml" -S "$SRC/res" -I "$ANDROID_JAR" -J "$OUT/gen" -F "$OUT/app.unsigned.apk"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
      -d "$OUT/classes" $(find "$SRC/java" "$OUT/gen" -name '*.java')
case $DEX in
  d8) "${BT}d8" --min-api 21 --output "$OUT" $(find "$OUT/classes" -name '*.class') ;;
  dx) "${BT}dx" --dex --output="$OUT/classes.dex" "$OUT/classes" ;;
  *)  dalvik-exchange --dex --output="$OUT/classes.dex" "$OUT/classes" ;;
esac
(cd "$OUT" && "$AAPT" add app.unsigned.apk classes.dex >/dev/null)
sign "$OUT/app.unsigned.apk" "$OUT/tnevi.apk"

for L in en km; do
  echo "== language pack: $L"
  mkdir -p "$OUT/pack-$L"
  manifest packs/$L/AndroidManifest.xml dev.vimkeyboard.pack.$L $PACK_VERSION_CODE $PACK_VERSION_NAME "$OUT/pack-$L/AndroidManifest.xml"
  "$AAPT" package -f -M "$OUT/pack-$L/AndroidManifest.xml" -S packs/$L/res -A packs/$L/assets -I "$ANDROID_JAR" \
          -F "$OUT/pack-$L.unsigned.apk"
  sign "$OUT/pack-$L.unsigned.apk" "$OUT/tnevi-pack-$L.apk"
done
ls -la "$OUT"/*.apk | grep -v unsigned
