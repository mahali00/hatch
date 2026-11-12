#!/usr/bin/env bash
# Usage: [SERIAL=..] [PKG=..] [BUDGET_MS=8.3] [MAX_SLOW=0] [SETTLE=2] scripts/frames.sh COMMAND [ARGS...]
#
# Example: scripts/frames.sh adb -s "$SERIAL" shell input tap 1180 96
# BUDGET_MS 8.3 is one vsync at 120 Hz.
# Pitfalls (benchmark build, coordinate taps, screenrecord gaps): see scripts/perf-lib.sh.
set -euo pipefail
# shellcheck source=perf-lib.sh source-path=SCRIPTDIR
source "$(dirname "$0")/perf-lib.sh"
(( $# )) || { sed -n 2p "$0" >&2; exit 2; }
budget=${BUDGET_MS:-8.3}; max_slow=${MAX_SLOW:-0}

sh dumpsys gfxinfo "$pkg" reset >/dev/null
"$@" >/dev/null 2>&1
sleep "${SETTLE:-2}"
sh dumpsys gfxinfo "$pkg" | grep -E 'Total frames rendered|Janky frames:|(50|90|99)th percentile|Number (Slow UI|Slow bitmap|Slow issue|Frame deadline)' | sed 's/^/  /'
# Columns are looked up by name: Android 12 inserted FrameTimelineVsyncId, shifting the old positions.
read -r slow total worst < <(sh dumpsys gfxinfo "$pkg" framestats | python3 -c '
import sys
budget = float(sys.argv[1]); cols = None; times = []
for line in sys.stdin:
    f = line.strip().rstrip(",").split(",")
    if f[0] == "Flags": cols = {name: i for i, name in enumerate(f)}; continue
    if cols is None or len(f) < len(cols) or f[0] not in ("0", "1"): continue  # other flags: invalid frames
    start, end = int(f[cols["IntendedVsync"]]), int(f[cols["FrameCompleted"]])
    if start > 0 and end > start: times.append((end - start) / 1e6)
print(sum(t > budget for t in times), len(times), "%.1f" % max(times, default=0))
' "$budget")
ok=$(( slow <= max_slow ))
verdict frames "$ok" "$slow of $total frames over $budget ms (max $max_slow), worst $worst ms"
