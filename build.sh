#!/bin/bash
# Manual APK build for 이상한 AI 키우기
# Uses: kotlinc, aapt2, dalvik-exchange (dx), apksigner, zipalign

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
SRC="$APP/src/main"
BUILD="$APP/build"
ANDROID_JAR="/usr/lib/android-sdk/platforms/android-23/android.jar"
KOTLIN_STDLIB="/usr/share/java/kotlin-stdlib.jar"

mkdir -p "$BUILD"
rm -rf "$BUILD/gen" "$BUILD/classes" "$BUILD/res-compiled" "$BUILD/dex"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/res-compiled" "$BUILD/dex"

echo "[1/7] aapt2 compile resources"
# aapt2 compile expects --dir or single files
shopt -s nullglob
for f in "$SRC"/res/*/*.xml "$SRC"/res/*/*.png "$SRC"/res/*/*.webp; do
  [ -f "$f" ] || continue
  aapt2 compile -o "$BUILD/res-compiled" "$f"
done

echo "[2/7] aapt2 link → resources.apk + R.java"
COMPILED_FLAGS=""
for cf in "$BUILD"/res-compiled/*.flat; do
  COMPILED_FLAGS="$COMPILED_FLAGS $cf"
done

aapt2 link \
  -I "$ANDROID_JAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --auto-add-overlay \
  -o "$BUILD/app-unsigned.apk" \
  $COMPILED_FLAGS

echo "[3/7] kotlinc compile"
KT_FILES=$(find "$SRC/kotlin" -name "*.kt" | tr '\n' ' ')
JAVA_FILES=$(find "$BUILD/gen" -name "*.java" 2>/dev/null | tr '\n' ' ' || true)

kotlinc \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  -jvm-target 1.8 \
  -nowarn \
  $KT_FILES $JAVA_FILES

echo "[4/7] javac R.java (if any)"
if [ -n "${JAVA_FILES// }" ]; then
  javac -source 1.8 -target 1.8 -classpath "$ANDROID_JAR:$BUILD/classes" -d "$BUILD/classes" $JAVA_FILES
fi

echo "[5/7] dx → classes.dex"
# Collect all classes + kotlin stdlib into one classpath input
CP_JAR="$BUILD/classpath.jar"
rm -f "$CP_JAR"
# Build a temporary jar of compiled classes
( cd "$BUILD/classes" && jar cf "$CP_JAR" . )

# Run dx with the compiled jar + kotlin stdlib
dalvik-exchange --dex --no-strict --output="$BUILD/classes.dex" \
  "$CP_JAR" "$KOTLIN_STDLIB"

echo "[6/7] add classes.dex into APK"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD" && zip -j app-with-dex.apk classes.dex )

echo "[7/7] zipalign + sign"
zipalign -f 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

# Create debug keystore if missing
KS="$BUILD/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 2>/dev/null
fi

apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/app-release.apk" "$BUILD/app-aligned.apk"
apksigner verify "$BUILD/app-release.apk"

echo ""
echo "=== Build complete ==="
ls -lh "$BUILD/app-release.apk"
