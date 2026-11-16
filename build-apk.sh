#!/usr/bin/env bash
# Build and sign a plain-Java Android app using Android SDK command-line tools.
set -euo pipefail

P=$(cd "${1:-.}" && pwd)
SDK_ROOT=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
SDK_JAR=${SDK_JAR:-$SDK_ROOT/platforms/android-36/android.jar}
BUILD_TOOLS=${ANDROID_BUILD_TOOLS:-$SDK_ROOT/build-tools/36.0.0}
if [ -d "$BUILD_TOOLS" ]; then
  PATH="$BUILD_TOOLS:$PATH"
  export PATH
fi
KEYDIR=${KEYDIR:-$HOME/.android/aod-signing}
OUT=$P/build

prop() { [ -f "$P/app.properties" ] && sed -n "s/^$1=//p" "$P/app.properties" | head -1 || true; }
VCODE=$(prop versionCode); VCODE=${VCODE:-1}
VNAME=$(prop versionName); VNAME=${VNAME:-1.0}
MINSDK=$(prop minSdk); MINSDK=${MINSDK:-26}

[ -f "$SDK_JAR" ] || { echo "android.jar not found: $SDK_JAR (set SDK_JAR)" >&2; exit 1; }
for t in javac d8 aapt2 apksigner keytool python3; do
  command -v "$t" >/dev/null || { echo "$t not found; install Android Build Tools 36 and JDK 17" >&2; exit 1; }
done
[ -f "$P/AndroidManifest.xml" ] || { echo "No AndroidManifest.xml in $P" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "1/5 Resources"
RES=()
if [ -d "$P/res" ] && [ -n "$(ls -A "$P/res")" ]; then
  aapt2 compile --dir "$P/res" -o "$OUT/res.zip"
  RES=("$OUT/res.zip")
fi
ASSETS=()
[ -d "$P/assets" ] && ASSETS=(-A "$P/assets")
aapt2 link -I "$SDK_JAR" --manifest "$P/AndroidManifest.xml" \
  --min-sdk-version "$MINSDK" --target-sdk-version 36 \
  --version-code "$VCODE" --version-name "$VNAME" \
  "${ASSETS[@]}" --java "$OUT/gen" -o "$OUT/base.apk" "${RES[@]}"

echo "2/5 Java"
javac --release 8 -classpath "$SDK_JAR" -Xlint:-options -encoding UTF-8 -nowarn \
  -d "$OUT/classes" $(find "$P/java" "$OUT/gen" -name '*.java')

echo "3/5 Dex"
d8 --release --min-api "$MINSDK" --lib "$SDK_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "4/5 Package"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/aligned.apk" <<'PY'
import struct, sys, zipfile
src, dex, dst = sys.argv[1:]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    entries = [(i, zin.read(i.filename)) for i in zin.infolist()]
    entries.append((zipfile.ZipInfo("classes.dex"), open(dex, "rb").read()))
    entries[-1][0].compress_type = zipfile.ZIP_DEFLATED
    for info, data in entries:
        out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        out.compress_type = info.compress_type
        out.external_attr = info.external_attr
        if out.compress_type == zipfile.ZIP_STORED:
            start = zout.fp.tell() + 30 + len(out.filename.encode())
            padding = (-(start + 4)) % 4
            # ZIP extra fields are TLVs; use a valid field header plus payload
            # so Android's ZIP reader can skip the bytes and the entry stays aligned.
            out.extra = struct.pack("<HH", 0xD935, padding) + bytes(padding)
        zout.writestr(out, data)
PY

echo "5/5 Sign"
mkdir -p "$KEYDIR"
chmod 700 "$KEYDIR"
if [ ! -f "$KEYDIR/password" ]; then
  python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > "$KEYDIR/password"
  chmod 600 "$KEYDIR/password"
fi
KEYPASS=$(cat "$KEYDIR/password")
if [ ! -f "$KEYDIR/aod.jks" ]; then
  keytool -genkeypair -keystore "$KEYDIR/aod.jks" -alias aod -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KEYPASS" -keypass "$KEYPASS" \
    -dname "CN=AOD Android" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYDIR/aod.jks" --ks-key-alias aod \
  --ks-pass "file:$KEYDIR/password" \
  --out "$OUT/app.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/app.apk"
echo "Built $OUT/app.apk"
