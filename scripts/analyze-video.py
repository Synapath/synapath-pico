#!/usr/bin/env python3
"""Inspect actual video frame PTS; does not establish sensor exposure rate."""
import argparse
import json
import statistics
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('video', type=Path)
args = parser.parse_args()
result = subprocess.run([
    'ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_frames',
    '-show_streams', '-show_entries',
    'frame=best_effort_timestamp_time:stream=width,height,avg_frame_rate,duration,nb_frames',
    '-of', 'json', str(args.video),
], check=True, capture_output=True, text=True)
data = json.loads(result.stdout)
ts = [float(f['best_effort_timestamp_time']) for f in data['frames']]
if len(ts) < 2 or ts[-1] <= ts[0]:
    raise SystemExit('Not enough valid frame timestamps')
gaps = [(b-a)*1000 for a, b in zip(ts, ts[1:])]
print(json.dumps({
    'file': str(args.video.resolve()), 'frames': len(ts),
    'pts_fps': (len(ts)-1)/(ts[-1]-ts[0]),
    'median_interval_ms': statistics.median(gaps),
    'min_interval_ms': min(gaps), 'max_interval_ms': max(gaps),
    'non_increasing': sum(g <= 0 for g in gaps),
    'gaps_over_25ms': sum(g > 25 for g in gaps),
    'stream': data['streams'][0],
}, indent=2))
