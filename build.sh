#!/usr/bin/env bash
set -euo pipefail

project="$(cd "$(dirname "$0")" && pwd)"
: "${ANDROID_PLATFORM_JAR:?Set ANDROID_PLATFORM_JAR to the Android 35 android.jar}"
: "${ANDROID_BUILD_TOOLS:?Set ANDROID_BUILD_TOOLS to the Android 35.0.0 build-tools directory}"
out="${1:-$project/The-Last-Fuck-debug.apk}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex"

"$ANDROID_BUILD_TOOLS/aapt2" compile --dir "$project/res" -o "$work/resources.zip"
"$ANDROID_BUILD_TOOLS/aapt2" link -I "$ANDROID_PLATFORM_JAR" -A "$project/assets" \
    --manifest "$project/AndroidManifest.xml" -o "$work/base.apk" "$work/resources.zip"

mapfile -t sources < <(find "$project/src" -name '*.java' | sort)
if [[ -n "${ECJ_JAR:-}" ]]; then
    java -jar "$ECJ_JAR" -1.8 -proc:none -warn:none -cp "$ANDROID_PLATFORM_JAR" -d "$work/classes" "${sources[@]}"
else
    javac -source 8 -target 8 -cp "$ANDROID_PLATFORM_JAR" -d "$work/classes" "${sources[@]}"
fi
mapfile -t classes < <(find "$work/classes" -name '*.class' | sort)
"$ANDROID_BUILD_TOOLS/d8" --min-api 26 --lib "$ANDROID_PLATFORM_JAR" --output "$work/dex" "${classes[@]}"

python3 - "$work/base.apk" "$work/dex/classes.dex" "$project/native" <<'PY'
import pathlib
import sys
import zipfile

apk, dex, native = map(pathlib.Path, sys.argv[1:])
with zipfile.ZipFile(apk, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
    archive.write(dex, 'classes.dex')
    for binary in sorted(native.glob('*/*.so')):
        archive.write(binary, f'lib/{binary.parent.name}/{binary.name}', compress_type=zipfile.ZIP_STORED)
PY

"$ANDROID_BUILD_TOOLS/zipalign" -P 16 -f 4 "$work/base.apk" "$work/aligned.apk"
keystore="${LAST_FUCK_DEBUG_KEYSTORE:-$project/.debug.keystore}"
if [[ ! -f "$keystore" ]]; then
    keytool -genkeypair -noprompt -keystore "$keystore" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=The Last Fuck Debug"
fi
"$ANDROID_BUILD_TOOLS/apksigner" sign --ks "$keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --v4-signing-enabled false --out "$out" "$work/aligned.apk"
"$ANDROID_BUILD_TOOLS/apksigner" verify --verbose "$out"
"$ANDROID_BUILD_TOOLS/zipalign" -c -P 16 4 "$out"
