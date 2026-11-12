#!/usr/bin/env bash
# Usage: [SERIAL=..] [PKG=..] [SETUP='cmd'] [TAPS='name=X,Y ...'] [REPEAT=3] [STREAM_SECS=6] \
#        scripts/ab.sh BASELINE.apk CANDIDATE.apk
#
# TAPS are name=X,Y pixel pairs. Toggle taps (sidebar hide/show) need an even count, or REPEAT
# must be even, so each run ends where it started.
# PASS when every gate on CANDIDATE passed. BASELINE failures are shown for comparison only.
# Pitfalls (benchmark build, coordinate taps, screenrecord gaps): see scripts/perf-lib.sh.
set -euo pipefail
# shellcheck source=perf-lib.sh source-path=SCRIPTDIR
source "$(dirname "$0")/perf-lib.sh"
(( $# == 2 )) || { sed -n 2,3p "$0" >&2; exit 2; }
here=$(dirname "$0"); export SERIAL=$serial PKG=$pkg
repeat=${REPEAT:-3}; out=$(mktemp -d)

run() {
  adb -s "$serial" install -r "$1" >/dev/null
  sh am force-stop "$pkg"
  sh monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 4
  if [[ -n ${SETUP:-} ]]; then eval "$SETUP"; sleep 3; fi
  for i in $(seq "$repeat"); do
    "$here/measure.sh" "${STREAM_SECS:-6}" | sed -nE "/^(PASS|FAIL)/s/^/stream $i: /p" || true
  done
  for tap in ${TAPS:-}; do
    local name=${tap%%=*} xy=${tap#*=}
    for i in $(seq "$repeat"); do
      "$here/frames.sh" adb -s "$serial" shell input tap "${xy%,*}" "${xy#*,}" | sed -nE "/^(PASS|FAIL)/s/^/$name $i: /p" || true
    done
  done
}

run "$1" > "$out/a"; run "$2" > "$out/b"
printf '%-70s | %s\n' "BASELINE $(basename "$1")" "CANDIDATE $(basename "$2")"
paste -d '|' "$out/a" "$out/b" | awk -F'|' '{ printf "%-70s | %s\n", $1, $2 }'
fails=$(grep -c ': FAIL' "$out/b" || true); total=$(wc -l < "$out/b" | tr -d ' ')
ok=$(( fails == 0 && total > 0 ))
verdict ab "$ok" "candidate failed $fails of $total gates; baseline failed $(grep -c ': FAIL' "$out/a" || true)"
