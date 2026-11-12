#!/usr/bin/env bash
# Usage: [SERIAL=..] [PKG=..] [KEEP=dir] scripts/trace.sh [SECONDS=8] [COMMAND [ARGS...]]
#
# Records a Perfetto trace of the app for SECONDS, running COMMAND meanwhile (or use the app by
# hand), imports it into strobe (github.com/Hona/strobe) and prints where a frame's time goes:
#   - the app's own sections: Hatch.parse (bytes into libghostty), Hatch.snapshot (terminal to
#     frame), Hatch.build (frame to vertices), Hatch.draw (build + GL + swap)
#   - frames drawn per render thread, and the gaps between them
#   - the main thread's Choreographer frames
#   - key to echo: Hatch.input, to the next bytes parsed (the host's round trip), to the next
#     draw finished (the app's share). Only meaningful while nothing else prints in the pane.
# Example, streaming into a temporary terminal of yours (an endless loop: stop it with
# rex send-key -b BLOCK ctrl+c; fish's (seq N) builds the whole list before it prints a line):
#   scripts/trace.sh 8 rex send -b BLOCK $'set i 0; while true; set i (math $i + 1); echo "line $i"; end\n'
# Typing, for key to echo (focus the pane on the device first):
#   scripts/trace.sh 10 bash -c 'for i in $(seq 15); do adb -s $SERIAL shell input text x; sleep 0.3; done'
# The handle it prints (rec_…) takes any query: strobe query rec_… sql "select …".
# Pitfalls (benchmark build, coordinate taps): see scripts/perf-lib.sh.
set -euo pipefail
# shellcheck source=perf-lib.sh source-path=SCRIPTDIR
source "$(dirname "$0")/perf-lib.sh"
command -v strobe >/dev/null || { echo "strobe is not on PATH" >&2; exit 2; }
secs=${1:-8}; shift || true
out=${KEEP:-$(mktemp -d)}; mkdir -p "$out"
remote=/data/misc/perfetto-traces/hatch.pftrace

adb -s "$serial" shell perfetto --txt -c - -o "$remote" >/dev/null 2>&1 <<EOF &
buffers { size_kb: 131072 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_wakeup"
  atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "input"
  atrace_apps: "$pkg" } } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
duration_ms: $((secs * 1000))
EOF
tracer=$!
sleep 1  # the trace has started before the command does
if (( $# )); then "$@" >/dev/null 2>&1 || echo "command failed: $*" >&2; fi
wait "$tracer"
adb -s "$serial" pull "$remote" "$out/hatch.pftrace" >/dev/null
rec=$(strobe record "file:$out/hatch.pftrace" | grep -o 'rec_[0-9a-f]*' | head -1)
[[ -n $rec ]] || { echo "strobe did not import $out/hatch.pftrace" >&2; exit 1; }
echo "$rec · $out/hatch.pftrace · $pkg · ${secs}s"

q() { echo "== $1"; strobe query "$rec" sql "$2" | sed 's/^/  /'; }
stats="count(*) n, round(percentile(dur, 50) / 1e6, 2) p50_ms, round(percentile(dur, 90) / 1e6, 2) p90_ms, round(percentile(dur, 99) / 1e6, 2) p99_ms, round(max(dur) / 1e6, 2) max_ms"
mine="join thread_track tt on s.track_id = tt.id join thread t using (utid) join process p using (upid) where p.name = '$pkg'"

q "app sections" "select s.name, $stats, round(sum(dur) / 1e6, 1) total_ms from slice s $mine and s.name like 'Hatch.%' and s.name != 'Hatch.input' group by 1 order by 1"
q "frames drawn, per render thread (gaps between draws; a pause in output is a gap too)" "
  with b as (select t.utid, t.name thread, s.ts, s.ts - lag(s.ts) over (partition by t.utid order by s.ts) dur
             from slice s $mine and s.name = 'Hatch.draw')
  select thread, count(*) + 1 frames, round(percentile(dur, 50) / 1e6, 2) gap_p50_ms, round(percentile(dur, 90) / 1e6, 2) gap_p90_ms,
         round(percentile(dur, 99) / 1e6, 2) gap_p99_ms, round(max(dur) / 1e6, 2) gap_max_ms from b where dur is not null group by utid order by 1"
q "main thread frames" "select 'Choreographer#doFrame' name, $stats from slice s $mine and t.is_main_thread and s.name like 'Choreographer#doFrame%'"
q "key to echo" "
  with k as (select s.ts from slice s $mine and s.name = 'Hatch.input'),
       e as (select k.ts key,
                    (select min(s.ts) from slice s $mine and s.name = 'Hatch.parse' and s.ts > k.ts) parsed from k),
       d as (select key, parsed,
                    (select min(s.ts + s.dur) from slice s $mine and s.name = 'Hatch.draw' and s.ts >= e.parsed) drawn from e)
  select 'key to bytes back' leg, count(*) n, round(percentile(parsed - key, 50) / 1e6, 1) p50_ms, round(percentile(parsed - key, 90) / 1e6, 1) p90_ms, round(max(parsed - key) / 1e6, 1) max_ms from d where parsed is not null
  union all
  select 'bytes back to drawn', count(*), round(percentile(drawn - parsed, 50) / 1e6, 1), round(percentile(drawn - parsed, 90) / 1e6, 1), round(max(drawn - parsed) / 1e6, 1) from d where drawn is not null"
q "CPU by thread (ms on a core)" "
  select t.name thread, round(sum(sc.dur) / 1e6, 1) cpu_ms from sched sc join thread t using (utid) join process p using (upid)
  where p.name = '$pkg' group by t.utid order by 2 desc limit 8"
