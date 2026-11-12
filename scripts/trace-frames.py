#!/usr/bin/env python3
"""Usage: scripts/trace-frames.py TRACE [--package PKG] [--budget-ms 8.3] [--prep 1] [--max-slow 0]

Capture on the benchmark build while toggling:

    adb -s "$SERIAL" shell atrace -t 8 -b 65536 gfx view > out.trace   # toggle with coordinate taps meanwhile

BUDGET_MS 8.3 is one vsync at 120 Hz.
Pitfalls (benchmark build, coordinate taps): see scripts/perf-lib.sh.
"""
import argparse, collections, re, sys

LINE = re.compile(r'^\s*(.+?)-(\d+)\s+(?:\(\s*[\d-]+\)\s+)?\[\d+\]\s+\S+\s+([\d.]+): tracing_mark_write: ([BE])\|(\d+)\|?(.*)$')


def parse(lines):
    """(slices, comms): slices are (pid, tid, name, start_s, dur_ms, depth) of completed B/E pairs."""
    stacks = collections.defaultdict(list); comms = {}; out = []
    for line in lines:
        m = LINE.match(line)
        if not m: continue
        comm, tid, ts, phase, pid, name = m.groups(); ts = float(ts); comms[tid] = comm
        stack = stacks[tid]
        if phase == 'B': stack.append((name, ts, pid))
        elif stack:
            name, t0, pid = stack.pop()
            out.append((pid, tid, name, t0, (ts - t0) * 1000, len(stack)))
    return out, comms


def pct(xs, q): return xs[min(len(xs) - 1, int(len(xs) * q))]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('trace'); ap.add_argument('--package', default='dev.mahali.hatch')
    ap.add_argument('--budget-ms', type=float, default=8.3); ap.add_argument('--prep', type=int, default=1)
    ap.add_argument('--max-slow', type=int, default=0); ap.add_argument('--min-group', type=int, default=10)
    a = ap.parse_args()
    with open(a.trace, errors='ignore') as f: slices, comms = parse(f)
    # The main thread is named after the last 15 characters of the package, and its tid is the pid.
    main_tid = next((t for t, c in comms.items() if c == a.package[-15:]), None)
    if main_tid is None:
        print(f'FAIL trace-frames: no thread of {a.package} in {a.trace}'); sys.exit(1)
    app = [s for s in slices if s[0] == main_tid]
    frames = collections.defaultdict(list)
    for pid, tid, name, t0, d, depth in app:
        if depth == 0 and name.startswith('Choreographer#doFrame'): frames[tid].append((t0, d))
    for tid in sorted(frames, key=lambda t: (t != main_tid, comms[t])):
        d = sorted(x for _, x in frames[tid])
        print(f'{comms[tid]}-{tid}: {len(d)} frames, over {a.budget_ms} ms {sum(x > a.budget_ms for x in d)}, '
              f'p50 {pct(d, .5):.1f} p90 {pct(d, .9):.1f} p99 {pct(d, .99):.1f} max {d[-1]:.1f} ms')
    groups = []
    for t, d in sorted(frames.get(main_tid, [])):
        if not groups or t - groups[-1][-1][0] > 0.25: groups.append([])
        groups[-1].append((t, d))
    groups = [g for g in groups if len(g) >= a.min_group]
    slow = 0
    for g in groups:
        ds = [d for _, d in g]; prep, slide = ds[:a.prep], ds[a.prep:]
        over = sum(d > a.budget_ms for d in slide); slow += over
        print(f'  toggle at {g[0][0]:.2f}s: {len(ds)} frames, prep {[round(d, 1) for d in prep]} ms, '
              f'slide max {max(slide, default=0):.1f} ms, slide frames over {a.budget_ms} ms: {over}')
    swaps = sorted(d for _, tid, name, _, d, _ in app if name == 'eglSwapBuffers' and comms[tid].startswith('terminal-rend'))
    if swaps: print(f'eglSwapBuffers p50 {pct(swaps, .5):.2f} p99 {pct(swaps, .99):.2f} max {swaps[-1]:.1f} ms n={len(swaps)}')
    ok = groups and slow <= a.max_slow
    print(f'{"PASS" if ok else "FAIL"} trace-frames: {slow} slide frames over {a.budget_ms} ms in {len(groups)} toggles (max {a.max_slow})'
          + ('' if groups else ', no toggles found'))
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
