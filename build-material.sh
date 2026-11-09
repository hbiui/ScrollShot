#!/bin/bash
# Manual Android build with AndroidX AppCompat + Material AARs, no Gradle daemon.
set -e
cd "$(dirname "$0")"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
BT="$ANDROID_HOME/build-tools/37.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-36/android.jar"
OUT="build/manual-material"
rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/aar-unpack" "$OUT/libjars"
export TMPDIR="$HOME/workspace/.tmp"
mkdir -p "$TMPDIR"

# Unpack AARs, collect res dirs and classes.jar
COMPILED=""
EXTRA_PKGS=""
CP="$PLATFORM"
LIBJARS=""
for aar in libs/*.aar; do
  name=$(basename "$aar" .aar)
  dir="$OUT/aar-unpack/$name"
  mkdir -p "$dir"
  unzip -q -o "$aar" -d "$dir"
  if [ -f "$dir/classes.jar" ]; then
    cp "$dir/classes.jar" "$OUT/libjars/$name.jar"
    LIBJARS="$LIBJARS $OUT/libjars/$name.jar"
    CP="$CP:$OUT/libjars/$name.jar"
  fi
  pkg=$(python3 -c "
import re,sys
m=open('$dir/AndroidManifest.xml',encoding='utf-8',errors='ignore').read()
mm=re.search(r'package=\"([^\"]+)\"',m)
print(mm.group(1) if mm else '')
")
  if [ -n "$pkg" ] && [ "$pkg" != "com.scrollshot" ]; then
    EXTRA_PKGS="$EXTRA_PKGS --extra-packages $pkg"
  fi
done
echo "[0/7] merge duplicate styleables across AARs"
python3 merge_styleables.py "$OUT/aar-unpack"
for aar in libs/*.aar; do
  name=$(basename "$aar" .aar)
  dir="$OUT/aar-unpack/$name"
  if [ -d "$dir/res" ] && [ "$(find "$dir/res" -type f | wc -l)" -gt 0 ]; then
    "$BT/aapt2" compile --dir "$dir/res" -o "$OUT/compiled/$name.zip"
    COMPILED="$COMPILED $OUT/compiled/$name.zip"
  fi
done
for jar in libs/*.jar; do
  [ -e "$jar" ] || continue
  cp "$jar" "$OUT/libjars/$(basename "$jar")"
  LIBJARS="$LIBJARS $OUT/libjars/$(basename "$jar")"
  CP="$CP:$OUT/libjars/$(basename "$jar")"
done
echo "extra packages:$EXTRA_PKGS"
echo "lib jars:$LIBJARS"

echo "[1/7] aapt2 compile app resources"
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/compiled.zip"

echo "[2/7] aapt2 link"
# shellcheck disable=SC2086
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" \
  --manifest app/src/main/AndroidManifest.xml \
  -I "$PLATFORM" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  $EXTRA_PKGS \
  "$OUT/compiled.zip" $COMPILED
find "$OUT/gen" -name "*.java" | head -40

echo "[3/7] javac"
find app/src/main/java -name "*.java" > "$OUT/sources.txt"
find "$OUT/gen" -name "*.java" >> "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$CP" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/7] d8 dex (multi-dex: library field count exceeds 64K)"
"$BT/d8" --lib "$PLATFORM" --min-api 24 --output "$OUT/dex" $(find "$OUT/classes" -name "*.class") $LIBJARS
ls -lh "$OUT/dex"

echo "[5/7] add dex files to APK"
cp "$OUT/app-unsigned.apk" "$OUT/app-dex.apk"
python3 - "$OUT/app-dex.apk" "$OUT/dex" << 'PY'
import sys, zipfile, os, glob
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    for dex in sorted(glob.glob(os.path.join(dexdir, "*.dex"))):
        z.write(dex, os.path.basename(dex))
        print("added", os.path.basename(dex))
PY

echo "[6/7] zipalign"
"$BT/zipalign" -f 4 "$OUT/app-dex.apk" "$OUT/app-aligned.apk"

echo "[7/7] sign"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias androiddebugkey \
    -keypass android -storepass android \
    -dname "CN=ScrollShot, OU=ScrollShot, O=ScrollShot" \
    -validity 10000 -keyalg RSA -keysize 2048 -noprompt
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 24 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/ScrollShot-1.3.0-debug.apk" "$OUT/app-aligned.apk"
"$BT/apksigner" verify -v "$OUT/ScrollShot-1.3.0-debug.apk" && echo "VERIFY OK"
ls -lh "$OUT/ScrollShot-1.3.0-debug.apk"
