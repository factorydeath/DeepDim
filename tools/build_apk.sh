#!/usr/bin/env bash
# Сборка DeepDim без Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BT="$SDK/build-tools/37.0.0"
PLATFORM="$SDK/platforms/android-37.0/android.jar"
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
KEYTOOL="${KEYTOOL:-keytool}"

MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
RES="$ROOT/app/src/main/res"
SRC="$ROOT/app/src/main/java"
OUT="$ROOT/build"
KEYSTORE="$ROOT/keystore/deepdim.jks"
KS_PASS="deepdim"
KS_ALIAS="deepdim"
VERSION_NAME="1.2.2"
VERSION_CODE="5"
MIN_SDK="26"
TARGET_SDK="37"

for tool in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$PLATFORM"; do
    if [ ! -e "$tool" ]; then
        echo "Не найден: $tool" >&2
        exit 1
    fi
done

command -v javac >/dev/null || { echo "Нет javac" >&2; exit 1; }
command -v zip >/dev/null || { echo "Нет zip" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/compiled"

echo "[1/7] aapt2 compile"
"$AAPT2" compile --dir "$RES" -o "$OUT/compiled/res.zip"

# Gradle задаёт package через namespace, поэтому для ручной сборки
# подставляем package в копию манифеста.
sed '0,/<manifest /s//<manifest package="com.deepdim" /' \
    "$MANIFEST" > "$OUT/AndroidManifest.xml"

echo "[2/7] aapt2 link"
"$AAPT2" link \
    -o "$OUT/unaligned.apk" \
    -I "$PLATFORM" \
    --manifest "$OUT/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --auto-add-overlay \
    "$OUT/compiled/res.zip"

echo "[3/7] javac"
find "$SRC" -name '*.java' > "$OUT/sources.txt"
find "$OUT/gen" -name '*.java' >> "$OUT/sources.txt"
javac --release 17 \
    -Xlint:-options \
    -classpath "$PLATFORM" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "[4/7] d8"
# shellcheck disable=SC2046
"$D8" --release --min-api "$MIN_SDK" --lib "$PLATFORM" \
    --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

echo "[5/7] упаковка classes.dex"
(cd "$OUT/dex" && zip -q -X "$OUT/unaligned.apk" classes.dex)

echo "[6/7] zipalign"
"$ZIPALIGN" -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "[7/7] подпись"
mkdir -p "$(dirname "$KEYSTORE")"
if [ ! -f "$KEYSTORE" ]; then
    echo "Создаём release-keystore: $KEYSTORE"
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass "$KS_PASS" \
        -keypass "$KS_PASS" \
        -alias "$KS_ALIAS" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=DeepDim, OU=Night, O=DeepDim, L=Local, S=Night, C=RU"
fi

FINAL="$ROOT/DeepDim-${VERSION_NAME}.apk"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KS_PASS" \
    --out "$FINAL" \
    "$OUT/aligned.apk"

"$APKSIGNER" verify --verbose --print-certs "$FINAL"

SIZE="$(du -h "$FINAL" | cut -f1)"
echo
echo "Готово: $FINAL ($SIZE)"
echo "Установка: adb install -r \"$FINAL\""
