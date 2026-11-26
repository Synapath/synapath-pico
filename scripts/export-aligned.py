#!/usr/bin/env python3
"""Export a shared-origin timeline and RGB-to-sensor associations without rewriting MP4 PTS."""
import argparse
import bisect
import csv
from decimal import Decimal
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('video',type=Path)
p.add_argument('--output',required=True,type=Path)
p.add_argument('--max-depth-age-ms',type=int,default=250)
p.add_argument('--max-pose-delta-ms',type=int,default=5)
a=p.parse_args()
if a.max_depth_age_ms<0 or a.max_pose_delta_ms<0:p.error('Time tolerances must be nonnegative')
if a.output.exists() and any(a.output.iterdir()):p.error('Output directory must be empty to prevent mixing sessions')
a.output.mkdir(parents=True,exist_ok=True)
# Extract original sample bytes and original pose values first.
subprocess.run(['python3',str(Path(__file__).with_name('analyze-sensors.py')),str(a.video),'--output',str(a.output/'raw')],check=True)
summary=json.loads((a.output/'raw/summary.json').read_text())
tracks=summary['tracks']
def exactly_one(candidates,label):
    if len(candidates)!=1:raise SystemExit(f'Expected one {label} track, got {len(candidates)}')
    return candidates[0]
rgb=exactly_one([s for s in tracks if s.get('codec_type')=='video' and s.get('codec_tag_string')!='raw1'],'RGB')
pose=exactly_one([s for s in tracks if s.get('mime')=='application/pose' and s.get('contains_head_position_tag')],'head pose')
depth=exactly_one([s for s in tracks if s.get('codec_tag_string')=='raw1'],'depth')
packets=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_packets','-show_entries',
    'packet=stream_index,pts_time,pos,size','-of','json',str(a.video)],text=True))['packets']
def load(track):
    items=[v for v in packets if v['stream_index']==track['index']]
    if any('pts_time' not in v for v in items):raise SystemExit('Missing sample PTS')
    for i,v in enumerate(items):
        # ffprobe PTS text has microsecond precision; do not claim ns accuracy.
        v['original_sample_index']=i
        v['pts_us']=int(Decimal(v['pts_time'])*1_000_000)
    items.sort(key=lambda v:v['pts_us'])
    if not items:raise SystemExit('One or more required tracks have no samples')
    if any(b['pts_us']<=c['pts_us'] for c,b in zip(items,items[1:])):
        raise SystemExit('Non-increasing or duplicate PTS; inspect before exporting')
    return items
r,pv,d=load(rgb),load(pose),load(depth)
origin=max(v[0]['pts_us'] for v in (r,pv,d))
end=min(v[-1]['pts_us'] for v in (r,pv,d))
if end<=origin:raise SystemExit('No common time interval')

def write_csv(path,fields,rows):
    with path.open('w',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=fields);writer.writeheader();writer.writerows(rows)

# Preserve all source samples; identify the common-valid interval explicitly.
for name,items in [('rgb',r),('head_pose',pv),('tof',d)]:
    rows=[]
    for v in items:
        row={'sample_index':v['original_sample_index'],'original_pts_us':v['pts_us'],
            'aligned_time_us':v['pts_us']-origin,'inside_common_interval':origin<=v['pts_us']<=end}
        if name=='tof':row['raw_file']=f"raw/raw-depth-stream-{depth['index']}/{v['original_sample_index']:06d}.bin"
        rows.append(row)
    write_csv(a.output/f'{name}-timeline.csv',list(rows[0]),rows)

pose_times=[v['pts_us'] for v in pv];depth_times=[v['pts_us'] for v in d]
rows=[];invalid_depth=0;invalid_pose=0
for v in r:
    t=v['pts_us']
    if not origin<=t<=end:continue
    k=bisect.bisect_left(pose_times,t)
    candidates=[n for n in (k-1,k) if 0<=n<len(pv)]
    pi=min(candidates,key=lambda n:abs(pose_times[n]-t))
    di=bisect.bisect_right(depth_times,t)-1
    pose_delta=pose_times[pi]-t
    depth_age=t-depth_times[di] if di>=0 else None
    pose_valid=abs(pose_delta)<=a.max_pose_delta_ms*1000
    depth_valid=depth_age is not None and depth_age<=a.max_depth_age_ms*1000
    invalid_depth+=not depth_valid;invalid_pose+=not pose_valid
    rows.append({'rgb_sample_index':v['original_sample_index'],'original_rgb_pts_us':t,'aligned_time_us':t-origin,
        'pose_sample_index':pv[pi]['original_sample_index'],'pose_delta_us':pose_delta,'pose_time_match_valid':pose_valid,
        'tof_sample_index':d[di]['original_sample_index'] if di>=0 else '',
        'tof_age_us':depth_age if depth_age is not None else '', 'tof_time_match_valid':depth_valid})
if not rows:raise SystemExit('No RGB frames in common interval')
write_csv(a.output/'rgb-associations.csv',list(rows[0]),rows)
# A self-contained export keeps the original MP4, not a remux that might drop vendor tracks.
video_out=a.output/'capture.mp4';shutil.copyfile(a.video,video_out)
hash_value=hashlib.sha256()
with video_out.open('rb') as f:
    for chunk in iter(lambda:f.read(1024*1024),b''):hash_value.update(chunk)
manifest={'schema_version':1,'source_video':'capture.mp4','source_sha256':hash_value.hexdigest(),
    'timestamp_precision':'microseconds from ffprobe PTS text',
    'common_origin_original_pts_us':origin,'common_end_original_pts_us':end,'common_span_us':end-origin,
    'sample_counts':{'rgb':len(r),'head_pose':len(pv),'tof':len(d),'associated_rgb':len(rows)},
    'excluded_rgb_before_common_start':sum(v['pts_us']<origin for v in r),
    'excluded_rgb_after_common_end':sum(v['pts_us']>end for v in r),
    'association_policy':{'pose':'nearest original sample, no interpolation','tof':'latest sample not newer than RGB, no interpolation',
        'max_pose_delta_ms':a.max_pose_delta_ms,'max_depth_age_ms':a.max_depth_age_ms},
    'invalid_time_matches':{'pose':invalid_pose,'tof':invalid_depth},
    'notes':['Original MP4 and original PTS are preserved.',
        'Every aligned timestamp subtracts the same origin; negative times identify pre-overlap samples.',
        'Time matches do not validate pose coordinates, depth values, exposure synchronization, or compensate sensor latency.',
        'The association CSV references shared ToF samples; it does not create 60 Hz depth frames.']}
(a.output/'manifest.json').write_text(json.dumps(manifest,indent=2))
print(json.dumps(manifest,indent=2))
