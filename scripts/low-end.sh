#!/usr/bin/env bash
# Usage: scripts/low-end.sh BLOCK
#
# Low-end profile: 2 GB RAM, 2 cores, 128 MB app heap, low-RAM flag so DeviceClass reads Low.
# The image is arm64: the app ships ARM code only, and Apple Silicon cannot run x86 images.
# Hatch on the emulator must be paired with a host and showing BLOCK before the gate runs.
set -euo pipefail
block=${1:?usage: low-end.sh BLOCK}
root=$(cd "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
avd=${AVD:-hatch-low-end}
image=${IMAGE:-"system-images;android-30;google_apis;arm64-v8a"}
port=${PORT:-5584}
serial=emulator-$port
emulator=$sdk/emulator/emulator

if ! "$emulator" -list-avds | grep -qx "$avd"; then
  if [[ ! -d $sdk/${image//;//} ]]; then
    echo "missing $image; install it with:" >&2
    echo "  $sdk/cmdline-tools/latest/bin/sdkmanager \"$image\"" >&2
    exit 2
  fi
  echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n "$avd" -k "$image" -d pixel_4
  cfg=$HOME/.android/avd/$avd.avd/config.ini
  grep -vE '^(hw\.ramSize|hw\.cpu\.ncore|vm\.heapSize)=' "$cfg" >"$cfg.tmp" || true
  printf 'hw.ramSize=2048\nhw.cpu.ncore=2\nvm.heapSize=128\n' >>"$cfg.tmp"
  mv "$cfg.tmp" "$cfg"
fi

if ! adb devices | grep -q "^$serial[[:space:]]*device"; then
  echo "== booting $avd on $serial"
  # ro.config.low_ram makes ActivityManager.isLowRamDevice true.
  "$emulator" -avd "$avd" -port "$port" -no-snapshot-save -no-audio -no-boot-anim \
    -prop ro.config.low_ram=true >/dev/null 2>&1 &
  adb -s "$serial" wait-for-device
  until [[ $(adb -s "$serial" shell getprop sys.boot_completed </dev/null | tr -d '\r') == 1 ]]; do sleep 2; done
fi

echo "== installing the benchmark build"
(cd "$root" && ANDROID_SERIAL=$serial ./gradlew -q :app:installBenchmark)
adb -s "$serial" shell monkey -p dev.mahali.hatch -c android.intent.category.LAUNCHER 1 >/dev/null </dev/null
read -r -p "Open $block in Hatch on $serial, then press Enter to run the gate. "

SERIAL=$serial PKG=dev.mahali.hatch "$root/scripts/perf-gate.sh" "$block"
