#!/usr/bin/env python3
"""Usage: scripts/video-scan.py {blank,flash,tint,header} VIDEO [--region X0:Y0:X1:Y1] [options]

Regions are fractions of the frame. Pitfall: Android screenrecord encodes only changed frames, so a
gap between two frames means nothing changed, not a dropped frame.
"""
import argparse, json, re, subprocess, sys
import numpy as np


def frames(video, width, rgb):
    probe = json.loads(subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                                       'stream=width,height', '-of', 'json', video], capture_output=True, check=True).stdout)
    w0, h0 = probe['streams'][0]['width'], probe['streams'][0]['height']
    w = min(width, w0) // 2 * 2; h = round(h0 * w / w0 / 2) * 2
    fmt, ch = ('rgb24', 3) if rgb else ('gray', 1)
    p = subprocess.run(['ffmpeg', '-v', 'info', '-i', video, '-vf', f'scale={w}:{h},showinfo', '-fps_mode', 'passthrough',
                        '-pix_fmt', fmt, '-f', 'rawvideo', '-'], capture_output=True)
    if p.returncode: sys.exit(p.stderr.decode(errors='ignore')[-2000:])
    ts = [float(t) for t in re.findall(rb'pts_time:([0-9.]+)', p.stderr)]
    fr = np.frombuffer(p.stdout, np.uint8).reshape((-1, h, w, ch) if rgb else (-1, h, w))
    n = min(len(ts), len(fr))
    return np.array(ts[:n]), fr[:n]


def crop(fr, region):
    x0, y0, x1, y1 = region; h, w = fr.shape[1:3]
    return fr[:, int(y0 * h):max(int(y1 * h), int(y0 * h) + 1), int(x0 * w):max(int(x1 * w), int(x0 * w) + 1)]


def blank(ts, fr, a):
    mean = crop(fr, a.region).mean(axis=(1, 2)); limit = a.dark_ratio * np.median(mean)
    if a.verbose: [print(f'{t:7.2f} mean {m:6.1f}') for t, m in zip(ts, mean)]
    hits = [(round(float(t), 2), round(float(m), 1)) for t, m in zip(ts, mean) if m < limit]
    return hits, f'blank frames (mean under {limit:.1f})'


def flash(ts, fr, a):
    g = crop(fr, a.region); n = len(g); ch, cw = g.shape[1] // a.rows, g.shape[2] // a.cols
    lit = np.zeros((a.rows, a.cols, n))
    for r in range(a.rows):
        for c in range(a.cols): lit[r, c] = (g[:, r * ch:(r + 1) * ch, c * cw:(c + 1) * cw] > 80).sum(axis=(1, 2))
    events = []
    for r in range(a.rows):
        for c in range(a.cols):
            s = lit[r, c]
            for i in range(1, n - 1):
                before = s[max(0, i - 3):i].mean()
                if before < 15 or s[i] > 0.25 * before: continue
                j = next((j for j in range(i, n) if ts[j] - ts[i] >= 0.6 or s[j] > 0.7 * before), n)
                if j < n and ts[j] - ts[i] < 0.6: events.append((float(ts[i]), float(ts[j] - ts[i]), r, c))
    merged = []
    for e in sorted(events):
        if merged and e[0] - merged[-1][-1][0] < 0.05: merged[-1].append(e)
        else: merged.append([e])
    hits = [(round(es[0][0], 2), f'{max(e[1] for e in es) * 1000:.0f} ms', len({(e[2], e[3]) for e in es})) for es in merged]
    return hits, 'flashes (time, duration, cells)'


def tint(ts, fr, a):
    t = crop(fr, a.region).astype(int)
    dark = t.sum(axis=3) < 120
    red = dark & ((t[..., 0] - t[..., 1]) >= a.red_over_green)
    frac = red.sum(axis=(1, 2)) / np.maximum(dark.sum(axis=(1, 2)), 1)
    if a.verbose: [print(f'{s:7.2f} red fraction {f:.3f}') for s, f in zip(ts, frac)]
    return [(round(float(s), 2), round(float(f), 2)) for s, f in zip(ts, frac) if f > a.max_fraction], \
        "frames in another client's colours (time, fraction)"


def header(ts, fr, a):
    x0, y0, x1, y1 = a.region
    strip = crop(fr, a.region).astype(int); below = crop(fr, (x0, y1, x1, min(1, 2 * y1 - y0))).astype(int)
    ds = np.abs(np.diff(strip, axis=0)).mean(axis=(1, 2)); db = np.abs(np.diff(below, axis=0)).mean(axis=(1, 2))
    if a.verbose: [print(f'{t:7.2f} strip {s:6.2f} below {b:6.2f}') for t, s, b in zip(ts[1:], ds, db)]
    return [(round(float(t), 2), round(float(s), 1)) for t, s, b in zip(ts[1:], ds, db) if s > a.jump and s > 0.5 * b], \
        'title-strip jumps (time, change)'


def region(s):
    v = tuple(float(x) for x in s.split(':'))
    if len(v) != 4 or not (0 <= v[0] < v[2] <= 1 and 0 <= v[1] < v[3] <= 1): raise argparse.ArgumentTypeError('X0:Y0:X1:Y1 fractions')
    return v


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('mode', choices=['blank', 'flash', 'tint', 'header']); ap.add_argument('video')
    ap.add_argument('--region', type=region, default=(0, 0, 1, 1)); ap.add_argument('--width', type=int)
    ap.add_argument('--dark-ratio', type=float, default=0.5)
    ap.add_argument('--cols', type=int, default=12); ap.add_argument('--rows', type=int, default=10)
    ap.add_argument('--red-over-green', type=int, default=3); ap.add_argument('--max-fraction', type=float, default=0.15)
    ap.add_argument('--jump', type=float, default=2.0)
    ap.add_argument('--max', type=int, default=0, help='hits allowed before FAIL (default 0)')
    ap.add_argument('--verbose', action='store_true')
    a = ap.parse_args()
    # Gray scans work at 1/10 of a 2520 px recording; colour needs more pixels per glyph gap.
    ts, fr = frames(a.video, a.width or (630 if a.mode == 'tint' else 252), a.mode == 'tint')
    if len(ts) == 0: print(f'FAIL {a.mode}: no frames decoded from {a.video}'); sys.exit(1)
    hits, what = {'blank': blank, 'flash': flash, 'tint': tint, 'header': header}[a.mode](ts, fr, a)
    print(f'{len(ts)} frames over {ts[-1] - ts[0]:.1f}s, region {a.region}')
    if hits: print(f'  {what}: {hits[:20]}')
    ok = len(hits) <= a.max
    print(f'{"PASS" if ok else "FAIL"} {a.mode}: {len(hits)} {what.split(" (")[0]} (max {a.max})')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
