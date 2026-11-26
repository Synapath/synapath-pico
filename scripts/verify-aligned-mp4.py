#!/usr/bin/env python3
"""Verify byte-identical retained samples and a shared PTS shift in APK-aligned MP4."""
import argparse
import hashlib
import json
import struct
import subprocess
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('original',type=Path);p.add_argument('aligned',type=Path)
p.add_argument('--output',type=Path)
a=p.parse_args()

def atoms(f,start,end):
    while start+8<=end:
        f.seek(start);size,kind=struct.unpack('>I4s',f.read(8));header=8
        if size==1:size=struct.unpack('>Q',f.read(8))[0];header=16
        if size==0:size=end-start
        if size<header or start+size>end:raise ValueError('Invalid atom extent')
        yield kind,start+header,start+size
        start+=size

def moov(path):
    with path.open('rb') as f:
        for kind,start,end in atoms(f,0,path.stat().st_size):
            if kind==b'moov':f.seek(start);return f.read(end-start)
    raise ValueError('Missing moov')

def stsd_by_track(raw):
    import io
    f=io.BytesIO(raw);result={}
    def child(s,e,name):return next((v[1:] for v in atoms(f,s,e) if v[0]==name),None)
    for kind,s,e in atoms(f,0,len(raw)):
        if kind!=b'trak':continue
        v=child(s,e,b'tkhd');f.seek(v[0]);version=f.read(1)[0];f.seek(v[0]+(20 if version==1 else 12));track_id=struct.unpack('>I',f.read(4))[0]
        extent=(s,e)
        for name in [b'mdia',b'minf',b'stbl',b'stsd']:extent=child(*extent,name)
        f.seek(extent[0]);result[track_id]=f.read(extent[1]-extent[0])
    return result

raw=moov(a.aligned)
# The application uses this exact 16-byte UUID identifier.
marker=b'SYNAPATHPICOTIME'
idx=raw.find(marker)
if idx<0:raise SystemExit('Missing in-file alignment provenance')
box_start=idx-8;size=struct.unpack_from('>I',raw,box_start)[0]
metadata=json.loads(raw[idx+16:box_start+size])
origin=metadata['origin_us']/1e6

def probe(path):return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_packets',
    '-show_entries','stream=index,id,codec_type,codec_tag_string:packet=stream_index,pts_time,pos,size',
    '-of','json',str(path)],text=True))
source,target=probe(a.original),probe(a.aligned)
assert len(source['streams'])==len(target['streams'])
# Vendor sample descriptions must be unchanged except for the shifted tbtm base.
src_desc=stsd_by_track(moov(a.original));dst_desc=stsd_by_track(raw)
assert src_desc.keys()==dst_desc.keys()
for track_id,desc in src_desc.items():
    expected=bytearray(desc)
    for i in range(4,len(expected)-11):
        if expected[i:i+4]==b'tbtm' and struct.unpack_from('>I',expected,i-4)[0]==16:
            base=struct.unpack_from('>Q',expected,i+4)[0]
            struct.pack_into('>Q',expected,i+4,base+metadata['origin_us'])
    assert bytes(expected)==dst_desc[track_id],f'Vendor description changed in track {track_id}'
results=[]
with a.original.open('rb') as f,a.aligned.open('rb') as g:
    for stream in target['streams']:
        # Audio payloads may repeat; compare all matching source candidates.
        index=stream['index'];original={}
        for item in source['packets']:
            if item['stream_index']!=index:continue
            f.seek(int(item['pos']));digest=hashlib.sha256(f.read(int(item['size']))).digest()
            original.setdefault(digest,[]).append(float(item['pts_time']))
        differences=[];times=[]
        for item in target['packets']:
            if item['stream_index']!=index:continue
            g.seek(int(item['pos']));digest=hashlib.sha256(g.read(int(item['size']))).digest()
            assert digest in original,'Modified sample payload'
            timestamp=float(item['pts_time']);times.append(timestamp)
            differences.append(min(abs(old-origin-timestamp)*1e6 for old in original[digest]))
        if times:
            assert min(times)>=0,'Negative output PTS'
            assert all(b>a for a,b in zip(times,times[1:])),'Non-increasing output PTS'
            assert max(differences)<=20,'PTS shift differs across tracks'
        results.append({'stream':index,'samples':len(times),'first_pts_s':times[0] if times else None,
            'max_shift_error_us':max(differences) if differences else None})
result={'alignment':metadata,'vendor_descriptions_preserved':True,'retained_payloads_unchanged':True,'tracks':results}
text=json.dumps(result,indent=2)
if a.output:a.output.write_text(text)
print(text)
