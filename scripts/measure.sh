#!/usr/bin/env bash
# Usage: [SERIAL=..] [PKG=..] [MAX_JANKY=0] [MAX_GAP_MS=100] scripts/measure.sh [SECONDS=5]
#
# Idle layers (no presents) are not counted: an unchanged terminal draws nothing.
# Pitfalls (benchmark build, coordinate taps, screenrecord gaps): see scripts/perf-lib.sh.
set -euo pipefail
# shellcheck source=perf-lib.sh source-path=SCRIPTDIR
source "$(dirname "$0")/perf-lib.sh"
secs=${1:-5}; max_janky=${MAX_JANKY:-0}; max_gap=${MAX_GAP_MS:-100}

pid=$(sh pidof "$pkg") || { echo "$pkg is not running" >&2; exit 2; }
jiffies() { sh cat "/proc/$pid/stat" | awk '{ print $14 + $15 }'; }
sh dumpsys gfxinfo "$pkg" reset >/dev/null
sh dumpsys SurfaceFlinger --latency-clear >/dev/null
t0=$(jiffies); sleep "$secs"; t1=$(jiffies)

echo "app CPU: $(( (t1 - t0) / secs ))% of one core ($((t1 - t0)) jiffies in ${secs}s at 100 Hz)"
sh dumpsys gfxinfo "$pkg" | grep -E 'Total frames rendered|Janky frames:|(50|90|95|99)th percentile' | sed 's/^/  window /'
layers=$(layer_report)
echo "${layers:-  no terminal layers}" | sed 's/^layer/  layer/'
echo "  threads: $(sh ps -T -p "$pid" | awk 'NR > 1' | wc -l | tr -d ' ')"
sh top -H -b -n 1 -p "$pid" | awk 'NR > 7 && $9 + 0 > 1 { print "  thread", $NF, $9 "%" }' | head -15

janky=$(window_janky)
worst=$(awk '$1 == "layer" && $3 == "presents" { if ($8 > w) w = $8 } END { print w + 0 }' <<<"$layers")
ok=$(awk -v j="$janky" -v g="$worst" -v mj="$max_janky" -v mg="$max_gap" 'BEGIN { print (j <= mj && g <= mg) ? 1 : 0 }')
verdict measure "$ok" "janky window frames $janky (max $max_janky), worst terminal gap ${worst} ms (max $max_gap)"
