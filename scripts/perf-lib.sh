# shellcheck shell=bash
# Sourced by the perf scripts. Device and package come from the environment:
#   SERIAL  adb serial (default: the only device in `adb devices`; also honours ANDROID_SERIAL)
#   PKG     dev.mahali.hatch (benchmark/release build, the one to measure) or dev.mahali.hatch.dev (debug)
#
# Pitfalls, true of every gate here:
# - Measure the benchmark build (./gradlew :app:installBenchmark). The debug build runs in the
#   interpreter, so its frame times say nothing about what users see.
# - Drive the app with coordinate taps (adb shell input tap X Y). Tapping by label through an
#   accessibility tool (agent-device, uiautomator dumps) walks the node tree on the UI thread and
#   inflates exactly the frame times being measured.
# - Android screenrecord encodes only frames that changed, so a gap between video frames can mean
#   "nothing changed", not "nothing was drawn".

serial=${SERIAL:-${ANDROID_SERIAL:-}}
if [[ -z $serial ]]; then
  serials=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  if [[ -z $serials || $(wc -l <<<"$serials") -ne 1 ]]; then
    echo "set SERIAL: $(wc -w <<<"$serials" | tr -d ' ') devices connected" >&2; exit 2
  fi
  serial=$serials
fi
pkg=${PKG:-dev.mahali.hatch}

sh() { adb -s "$serial" shell "$@" </dev/null; }

verdict() {
  if [[ $2 == 1 ]]; then echo "PASS $1: $3"; else echo "FAIL $1: $3"; exit 1; fi
}

terminal_layers() {
  sh dumpsys SurfaceFlinger --list | grep -o "[0-9a-f]* SurfaceView\[$pkg/[^]]*\]@[0-9]*(BLAST)#[0-9]*" | sort -u
}

# Output per layer, parsed by perf-gate.sh and measure.sh: "layer <id> presents N fps F longest_ms G gaps_over_2.5_vsync K late L vsync_ms V".
# "late": presented more than 2.5 vsyncs after it was queued (two is the normal pipeline).
layer_report() {
  local layer
  while read -r layer; do
    [[ -n $layer ]] || continue
    sh dumpsys SurfaceFlinger --latency "\"$layer\"" | python3 -c '
import sys
lines = sys.stdin.read().split("\n")
vsync = int(lines[0])
rows = sorted(tuple(map(int, l.split())) for l in lines[1:] if len(l.split()) == 3)
rows = [r for r in rows if 0 < r[1] < 2**62]
name = sys.argv[1].split()[0][:12]
if len(rows) < 3:
    print("layer %s idle" % name); sys.exit()
t = [r[1] for r in rows]
gaps = [b - a for a, b in zip(t, t[1:])]
span = (t[-1] - t[0]) / 1e9
print("layer %s presents %d fps %.1f longest_ms %.1f gaps_over_2.5_vsync %d late %d vsync_ms %.2f" % (
    name, len(t), (len(t) - 1) / span, max(gaps) / 1e6, sum(g > 2.5 * vsync for g in gaps),
    sum(1 for r in rows if r[0] > 0 and r[1] - r[0] > 2.5 * vsync), vsync / 1e6))
' "$layer"
  done < <(terminal_layers)
}

window_janky() { sh dumpsys gfxinfo "$pkg" | awk -F': ' '/Janky frames:/ { split($2, a, " "); print a[1]; exit }'; }
