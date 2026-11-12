#!/usr/bin/env bash
# Usage: [SERIAL=..] [PKG=..] scripts/perf-gate.sh BLOCK
#
# BLOCK must be a temporary terminal of yours: the script types a timed output loop into it with the rex CLI.
# Pitfalls (benchmark build, coordinate taps, screenrecord gaps): see scripts/perf-lib.sh.
set -euo pipefail
# shellcheck source=perf-lib.sh source-path=SCRIPTDIR
source "$(dirname "$0")/perf-lib.sh"
block=${1:?usage: perf-gate.sh BLOCK}
total_frames() { sh dumpsys gfxinfo "$pkg" | awk -F': ' '/Total frames rendered/ { print $2 + 0; exit }'; }

echo "== idle 5 s"
sh dumpsys gfxinfo "$pkg" reset >/dev/null
sleep 5
idle=$(total_frames)
echo "  window: $idle frames"

echo "== streaming 5 s into ${block}"
sh dumpsys gfxinfo "$pkg" reset >/dev/null
sh dumpsys SurfaceFlinger --latency-clear >/dev/null
rex send -b "$block" $'for i in (seq 3000); echo "line $i "(string repeat -n 40 =); end\n'
sleep 5.5
janky=$(window_janky)
echo "  window: $(total_frames) frames, janky $janky"
layers=$(layer_report)
echo "${layers:-no terminal layers}" | sed 's/^/  /'
rex send -b "$block" $'clear\n'

gaps=$(awk '$3 == "presents" { g += $10 } END { print g + 0 }' <<<"$layers")
ok=$(( idle == 0 && janky == 0 && gaps == 0 ))
verdict perf-gate "$ok" "idle window frames $idle, streaming janky frames $janky, terminal gaps over 2.5 vsyncs $gaps (all must be 0)"
