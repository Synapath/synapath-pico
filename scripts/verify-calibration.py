#!/usr/bin/env python3
"""Check an exported dual-calibration JSON against preserved bytes and its video."""
import argparse
import base64
import hashlib
import json
import math
import struct
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('calibration', type=Path)
parser.add_argument('--video', type=Path)
args = parser.parse_args()
data = json.loads(args.calibration.read_text())
raw = data['raw_camera_calibration']
if not raw['available']:
    raise SystemExit('Original calibration unavailable; inspect SDK callback status')
counts = {}
for field in ('intrinsics', 'extrinsics', 'distortion_coefficients'):
    packed = raw[field]
    original = base64.b64decode(packed['base64'], validate=True)
    values = packed['packed_values']
    assert packed['decode_valid'] and original and len(original) == packed['byte_count']
    assert len(original) == len(values) * 8
    assert all(math.isfinite(v) for v in values)
    assert struct.pack('<' + 'd' * len(values), *values) == original, field
    counts[field] = len(values)
output = data['output_image_parameters']
assert raw['image_mode'] == 'sdk_camera_raw'
assert output['available'], 'Output mode callback unavailable'
assert output['canvas_width'] == 2 * output['per_eye_width']
assert output['canvas_height'] == output['per_eye_height']
assert raw['per_eye_width'] == output['per_eye_width']
assert raw['per_eye_height'] == output['per_eye_height']
assert output['projection_match_verified'] is False
assert output['parameter_source'] == 'mode_specific_SDK_callback; not FOV helper'
for field in ('intrinsics', 'extrinsics'):
    packed = output[field]
    values = packed['packed_values']
    assert struct.pack('<' + 'd' * len(values), *values) == base64.b64decode(packed['base64'], validate=True)
for camera in output.get('cameras', []):
    i = camera['packed_camera_index']
    values = output['intrinsics']['packed_values'][i * 4:i * 4 + 4]
    assert values == [camera['fx'], camera['fy'], camera['cx'], camera['cy']]
    assert camera['K'] == [[camera['fx'], 0, camera['cx']],
                           [0, camera['fy'], camera['cy']], [0, 0, 1]]
if args.video:
    digest = hashlib.sha256()
    with args.video.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    assert digest.hexdigest() == data['source_mp4_sha256'], 'Calibration/video association mismatch'
    with args.video.open('rb') as f:
        def boxes(start, end):
            while start + 8 <= end:
                f.seek(start); size, kind = struct.unpack('>I4s', f.read(8)); header = 8
                if size == 1: size = struct.unpack('>Q', f.read(8))[0]; header = 16
                if size == 0: size = end - start
                assert size >= header and start + size <= end, 'Invalid MP4 box'
                yield kind, start + header, start + size
                start += size
        def child(start, end, kind):
            return next(item[1:] for item in boxes(start, end) if item[0] == kind)
        movie = child(0, args.video.stat().st_size, b'moov')
        embedded = []
        for kind, start, end in boxes(*movie):
            if kind != b'trak': continue
            extent = (start, end)
            for name in (b'mdia', b'minf', b'stbl', b'stsd'): extent = child(*extent, name)
            f.seek(extent[0] + 12)
            if f.read(4) != b'hvc1': continue
            # Observed PXR HEVC visual sample entry: stsd prefix 8 + entry header 86.
            for kind, start, end in boxes(extent[0] + 94, extent[1]):
                if kind != b'icam': continue
                f.seek(start); payload = f.read(end - start)
                assert len(payload) == 54 and payload[:4] == bytes(4), 'Unsupported PXR icam profile'
                # PXR stores each intrinsic as signed exponent + little-endian double mantissa.
                vals = [math.ldexp(struct.unpack_from('<d', payload, 19 + 9 * i)[0],
                                  struct.unpack_from('b', payload, 18 + 9 * i)[0]) for i in range(4)]
                embedded.append(vals)
        expected = [[camera[k] for k in ('fx', 'fy', 'cx', 'cy')] for camera in output['cameras']]
        assert embedded == expected, ('MP4 output intrinsics differ from JSON', embedded, expected)
print(json.dumps({'original_packed_values': counts,
                  'output_mode': output['image_mode'],
                  'video_sha256_matched': bool(args.video),
                  'mp4_output_intrinsics_matched': bool(args.video)}, indent=2))
