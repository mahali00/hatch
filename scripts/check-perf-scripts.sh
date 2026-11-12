#!/usr/bin/env bash
# Usage: scripts/check-perf-scripts.sh
# Offline, no device: every scanner mode must pass a clean synthetic video and fail a flawed one.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
for f in "$here"/perf-lib.sh "$here"/trace.sh "$here"/perf-gate.sh "$here"/measure.sh "$here"/frames.sh "$here"/ab.sh "$here"/low-end.sh "$0"; do bash -n "$f"; done
python3 -m py_compile "$here/trace-frames.py" "$here/video-scan.py"

grid=""
for y in 60 120 180 240 300 360 420; do for x in 40 140 240 340 440 540; do
  grid+=",drawbox=x=$x:y=$y:w=60:h=20:c=white:t=fill"
done; done
clean="color=c=0x0a0a0a:s=640x480:r=30:d=5$grid,drawbox=x=40:y=10:w=200:h=20:c=white:t=fill"
bad="$clean,drawbox=x=240:y=180:w=200:h=100:c=0x0a0a0a:t=fill:enable='between(t,1,1.2)'"
bad+=",drawbox=x=0:y=0:w=640:h=480:c=0x200a0a:t=fill:enable='between(t,2,2.4)'"
bad+=",drawbox=x=0:y=0:w=640:h=480:c=black:t=fill:enable='between(t,3,3.1)'"
bad+=",drawbox=x=300:y=10:w=200:h=20:c=white:t=fill:enable='between(t,4,4.2)'"
ffmpeg -v error -y -f lavfi -i "$clean" -c:v libx264 -pix_fmt yuv420p "$tmp/clean.mp4"
ffmpeg -v error -y -f lavfi -i "$bad" -c:v libx264 -pix_fmt yuv420p "$tmp/bad.mp4"

failed=0
for mode in blank flash tint header; do
  args=(); [[ $mode == header ]] && args=(--region 0:0:1:0.08)
  python3 "$here/video-scan.py" "$mode" "$tmp/clean.mp4" "${args[@]+"${args[@]}"}" >/dev/null || { echo "clean $mode did not pass"; failed=1; }
  if python3 "$here/video-scan.py" "$mode" "$tmp/bad.mp4" "${args[@]+"${args[@]}"}" >/dev/null; then echo "bad $mode did not fail"; failed=1; fi
done
if (( failed )); then echo "FAIL check-perf-scripts"; exit 1; fi
echo "PASS check-perf-scripts: syntax ok, scanners pass the clean video and fail the bad one in every mode"
