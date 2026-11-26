#!/usr/bin/env python3
"""Compare pipeline MP4 frame timestamps against the submitted source-frame CSV."""
import argparse
import csv
import json
import subprocess
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('video',type=Path);p.add_argument('csv',type=Path)
a=p.parse_args()
rows=list(csv.DictReader(a.csv.open()))
frames=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0',
    '-show_frames','-show_entries','frame=best_effort_timestamp_time','-of','json',str(a.video)],text=True))['frames']
source=[int(r['source_mono_ns']) for r in rows]
pts=[float(f['best_effort_timestamp_time']) for f in frames]
if len(source)<2 or len(source)!=len(pts):
    raise SystemExit(f'Frame count mismatch or insufficient data: submitted={len(source)}, encoded={len(pts)}')
errors=[abs(t-(s-source[0])/1e9)*1e6 for s,t in zip(source,pts)]
result={'submitted_frames':len(source),'encoded_frames':len(pts),
    'source_fps':(len(source)-1)*1e9/(source[-1]-source[0]),
    'file_pts_fps':(len(pts)-1)/(pts[-1]-pts[0]),
    'max_pts_error_us':max(errors),'non_increasing_source_timestamps':sum(b<=a for a,b in zip(source,source[1:])),
    'passes_50us_tolerance':max(errors)<=50}
print(json.dumps(result,indent=2))
if not result['passes_50us_tolerance'] or result['non_increasing_source_timestamps']:
    raise SystemExit(1)
