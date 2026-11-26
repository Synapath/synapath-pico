#!/usr/bin/env python3
"""Inspect PXRCapture MP4 tracks and extract opaque depth and head-pose samples."""
import argparse
import collections
import csv
import json
import math
import statistics
import struct
import subprocess
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('video',type=Path);p.add_argument('--output',type=Path,required=True)
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
data=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_packets',
    '-show_entries','stream=index,id,codec_type,codec_tag_string,width,height,nb_frames,duration:packet=stream_index,pts_time,size,pos',
    '-of','json',str(a.video)],text=True))

def boxes(f,start,end):
    offset=start
    while offset+8<=end:
        f.seek(offset);size,kind=struct.unpack('>I4s',f.read(8));header=8
        if size==1:size=struct.unpack('>Q',f.read(8))[0];header=16
        if size==0:size=end-offset
        if size<header or offset+size>end:raise ValueError(f'Invalid MP4 box at {offset}')
        yield kind,offset+header,offset+size
        offset+=size

def child(f,start,end,kind):
    return next((x[1:] for x in boxes(f,start,end) if x[0]==kind),None)

metadata={}
with a.video.open('rb') as f:
    moov=child(f,0,a.video.stat().st_size,b'moov')
    if moov:
        for kind,start,end in boxes(f,*moov):
            if kind!=b'trak':continue
            tkhd=child(f,start,end,b'tkhd')
            if not tkhd:continue
            f.seek(tkhd[0]);version=f.read(1)[0]
            f.seek(tkhd[0]+(20 if version==1 else 12));track_id=struct.unpack('>I',f.read(4))[0]
            extent=(start,end)
            for name in [b'mdia',b'minf',b'stbl',b'stsd']:
                extent=child(f,*extent,name) if extent else None
            if extent:
                f.seek(extent[0]);description=f.read(extent[1]-extent[0])
                mime=next((v for v in ['application/pose','application/disparity'] if v.encode() in description),None)
                metadata[track_id]={'mime':mime,'contains_head_position_tag':b'head' in description}

summary={'video':str(a.video.resolve()),'tracks':[]}
with a.video.open('rb') as source:
    for stream in data['streams']:
        index=stream['index'];packets=[x for x in data['packets'] if x['stream_index']==index]
        ts=[float(x['pts_time']) for x in packets if 'pts_time' in x]
        intervals=[(b-a)*1000 for a,b in zip(ts,ts[1:])]
        track=dict(stream);track.update(metadata.get(int(stream['id'],0),{}));track['packet_count']=len(packets)
        track['packet_sizes']=dict(collections.Counter(int(x['size']) for x in packets))
        if len(ts)>1 and ts[-1]>ts[0]:
            track.update(pts_rate_hz=(len(ts)-1)/(ts[-1]-ts[0]),median_interval_ms=statistics.median(intervals),
                min_interval_ms=min(intervals),max_interval_ms=max(intervals),non_increasing=sum(d<=0 for d in intervals))
        if track.get('mime')=='application/pose':
            finite=0;norms=[];unique=set()
            with (a.output/f'pose-stream-{index}.csv').open('w') as out:
                writer=csv.writer(out);writer.writerow(['pts_seconds']+[f'd{i}' for i in range(7)])
                for packet in packets:
                    if int(packet['size'])!=56:continue
                    source.seek(int(packet['pos']));raw=source.read(56);values=struct.unpack('<7d',raw)
                    writer.writerow([packet.get('pts_time','')]+list(values));finite+=all(math.isfinite(v) for v in values)
                    norms.append(math.sqrt(sum(v*v for v in values[3:])));unique.add(raw)
            track.update(finite_7double_samples=finite,distinct_payloads=len(unique),last_four_norm_range=[min(norms),max(norms)] if norms else None)
            track['schema_note']='Raw little-endian doubles d0..d6; coordinate frame, unit and quaternion ordering need vendor confirmation.'
        if stream.get('codec_tag_string')=='raw1':
            depth_dir=a.output/f'raw-depth-stream-{index}';depth_dir.mkdir(exist_ok=True)
            with (depth_dir/'timestamps.csv').open('w') as out:
                writer=csv.writer(out);writer.writerow(['sample','pts_seconds','bytes'])
                for n,packet in enumerate(packets):
                    size=int(packet['size']);source.seek(int(packet['pos']));raw=source.read(size)
                    (depth_dir/f'{n:06d}.bin').write_bytes(raw);writer.writerow([n,packet.get('pts_time',''),size])
            track['schema_note']='Original bytes preserved. raw1 sample size matches 16 bits/pixel; depth units and invalid codes are not decoded.'
        summary['tracks'].append(track)
(a.output/'summary.json').write_text(json.dumps(summary,indent=2))
for s in summary['tracks']:
    print(s['index'],s.get('mime') or s.get('codec_tag_string'),s['packet_count'],s.get('pts_rate_hz'))
print(a.output/'summary.json')
