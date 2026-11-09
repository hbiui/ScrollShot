#!/bin/bash
# Manual Android build without Gradle daemon (aapt2 + javac + d8 + zipalign + apksigner).
# Works on a machine with JDK 17 and an Android SDK (platform android-36, build-tools 37.0.0).
set -e
cd "$(dirname "$0")"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
# JDK may disappear on VM replacement (it lives outside $HOME); use the
# JDK downloaded into ~/workspace/tools if system javac is missing.
if ! command -v javac >/dev/null 2>&1; then
  for d in "$HOME"/workspace/tools/jdk-17*/ "$HOME"/workspace/tools/jdk-21*/; do
    if [ -x "${d}bin/javac" ]; then export PATH="${d}bin:$PATH"; break; fi
  done
fi
BT="$ANDROID_HOME/build-tools/37.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-36/android.jar"
OUT="build/manual"
rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"
export TMPDIR="$HOME/workspace/.tmp"
mkdir -p "$TMPDIR"

echo "[1/7] aapt2 compile resources"
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/compiled.zip"

echo "[2/7] aapt2 link (generates R.java + base APK)"
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" \
  --manifest app/src/main/AndroidManifest.xml \
  -I "$PLATFORM" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  "$OUT/compiled.zip"
find "$OUT/gen" -name "*.java" | head -20

echo "[3/7] javac"
find app/src/main/java -name "*.java" > "$OUT/sources.txt"
find "$OUT/gen" -name "*.java" >> "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/7] d8 dex"
# d8 wants individual .class files (a bare directory is rejected by this d8 build)
"$BT/d8" --lib "$PLATFORM" --output "$OUT/dex" $(find "$OUT/classes" -name "*.class")
ls -lh "$OUT/dex"

echo "[5/7] add classes.dex to APK"
cp "$OUT/app-unsigned.apk" "$OUT/app-dex.apk"
# d8 outputs classes.dex in $OUT/dex
python3 - "$OUT/app-dex.apk" "$OUT/dex/classes.dex" << 'PY'
import sys, zipfile, shutil
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("added classes.dex")
PY

echo "[6/7] zipalign"
"$BT/zipalign" -f 4 "$OUT/app-dex.apk" "$OUT/app-aligned.apk"

echo "[7/7] sign (debug key, created if missing)"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias androiddebugkey \
    -keypass android -storepass android \
    -dname "CN=ScrollShot, OU=ScrollShot, O=ScrollShot" \
    -validity 10000 -keyalg RSA -keysize 2048 -noprompt
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 24 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/ScrollShot-1.39.51-debug.apk" "$OUT/app-aligned.apk"
"$BT/apksigner" verify -v "$OUT/ScrollShot-1.39.51-debug.apk" && echo "VERIFY OK"
ls -lh "$OUT/ScrollShot-1.39.51-debug.apk"
