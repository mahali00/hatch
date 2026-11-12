#!/bin/sh
# Builds libghostty-vt for arm64 and armeabi-v7a from the commit pinned in ghostty/src/main/cpp/ghostty/COMMIT.
# Usage: scripts/build-ghostty.sh [path-to-ghostty-checkout]
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC=${1:-$HOME/Developer/ghostty-src}
OUT=$ROOT/ghostty/src/main/cpp/ghostty
NDK=${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}

[ -d "$SRC/.git" ] || git clone https://github.com/ghostty-org/ghostty "$SRC"
git -C "$SRC" fetch -q origin
git -C "$SRC" checkout -q "$(cat "$OUT/COMMIT")"

for pair in aarch64-linux-android:arm64-v8a arm-linux-androideabi:armeabi-v7a; do
    target=${pair%%:*} abi=${pair#*:}
    (cd "$SRC" && ANDROID_NDK_HOME="$NDK" zig build -Demit-lib-vt=true \
        -Dtarget="$target" -Doptimize=ReleaseFast --prefix "zig-out-$abi")
    mkdir -p "$OUT/lib/$abi" && cp "$SRC/zig-out-$abi/lib/libghostty-vt.a" "$OUT/lib/$abi/"
done

rm -rf "$OUT/include" && cp -R "$SRC/zig-out-arm64-v8a/include" "$OUT/"
cp "$SRC/LICENSE" "$OUT/LICENSE"
echo "libghostty-vt $(cat "$OUT/COMMIT") -> $OUT"
