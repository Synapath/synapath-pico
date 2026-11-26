# Application recording pipeline experiment

## Isolation

Branch: feature/app-recording-preview. Stable snapshot: af94883 on
baseline/sdk-recording-ui3. Main was unborn with untracked source when this
experiment began; its branch/index/worktree were retained. Snapshot creation
used a temporary index. The experimental worktree is a sibling directory.
No GitHub push has been performed.

Experimental package com.synapath.picopipeline installs alongside the stable
com.synapath.capturesdkdemo011 package. Testing temporarily stops the stable
application to release the camera, without uninstalling it or changing its data.

## Implementation

PipelineActivity owns the single PXRCapture preview client on a HandlerThread.
PreviewRecorder receives its frames as an external OES SurfaceTexture, applies
the supplied texture transform and renders the same texture to a GLSurfaceView
and a MediaCodec input surface. Encoding is AVC, 2048×768, 10×1024×1024 bit/s,
no B frames, no audio. The SDK's startRecord is not used. No CPU pixel readback
is needed. EOS draining finalizes the MediaMuxer file; the preview remains active.

The source SDK configuration keeps RAW mode and a requested 60 FPS with MCTF,
EIS and MFNR enabled, matching the existing baseline settings. This does not
establish access to unprocessed sensor exposures. Application frame selection
allows at most 60 Hz and never deliberately repeats a source timestamp.
SurfaceTexture notifications can coalesce; this is not lossless frame capture.

## Device verification, 2026-11-26

PICO A9210, firmware 5.15.8:

- User confirmed live preview continued updating during recording.
- Recording AppPipeline-1795682797492.mp4: 2031 frames, 33.85 s, 2048×768,
  file PTS about 60 FPS, no non-increasing PTS or gaps over 25 ms.
- Timestamp-audited recording AppPipeline-1795683034489.mp4: 725 submitted
  source timestamps and 725 encoded frames. Source rate about 59.997484 Hz;
  file PTS about 60 FPS. Both streams were monotonic.
- Recording and source CSV are stored under artifacts/pipeline-test locally
  and excluded from Git. Preview frame logs remained active while recording.
- Stable source tree was compared against baseline/sdk-recording-ui3 and
  matched exactly after experiment development.

## Timestamp limitation

Encoder presentation timestamps are supplied from source mono time, rebased to
zero. The no-B-frame muxing path also assigns submitted source PTS to each
encoded sample. However, the device MP4 path still regularizes small timestamp
variations. The audited 12-second recording differed from source timing by up
to 505.656 microseconds. The 50-us timestamp audit deliberately reports failure
for this file: perfect source timing preservation in MP4 is not claimed.
The CSV is the source timestamp record and must accompany recordings used for
synchronization. Sensor exposure timing semantics remain a vendor question.

Android's MPEG4Writer source documents rounding and reusing near-identical
sample durations (less than 0.1 ms differences):
https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libstagefright/MPEG4Writer.cpp
This is a plausible explanation of the observed MP4 smoothing; the device's
exact vendor implementation is not available in this repository.

Run the audit with:

```sh
python3 scripts/audit-pipeline.py video.mp4 video.frames.csv
```

The first implementation remains experimental. Audio, production UI, long
recording/thermal testing, encoder back-pressure and app interruption recovery
need further work before replacing the stable SDK recording path.
