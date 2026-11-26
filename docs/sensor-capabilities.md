# Capture SDK sensor capability investigation

## Scope

This branch is independent of feature/app-recording-preview and starts from the
stable SDK recording baseline. It does not merge the application encoder.
The local vendor AAR is the validated capturelib-0.1.1.aar SHA-256
9e5a15f39709b94cb781068167d406da229c4fb37e821606efe802bf5c6cb267.

## AAR interfaces and vendor manual

The actual PXRCapture class exposes KEY_WRITE_DEPTH_DATA,
KEY_WRITE_POSE_DATA and KEY_WRITE_CAMERA_PARAMS_DATA in configure(Map).
The supplied manual describes saving depth, pose, and RGB/ToF calibration
respectively. Pose/depth recording also checks com.picovr.permission.SPATIAL_DATA.
These are recording switches, not public per-frame depth/pose callbacks.
The inspected class has no explicit depth rate setting.

The environment callback captureOnEnviromentInfoChanged(lut,distance) is described
at 5 FPS, but its brightness/distance values are not a depth image stream.
No guarantee of raw ToF 5 Hz is inferred from that callback.

## Actual off-head recording

A9210, firmware 5.15.8; headset not worn, passthrough enabled. The user confirmed
not wearing the headset. File DemoPicture-1795684593233.mp4 (~15 seconds):

- RGB: HEVC 2048×768, 899 frames, PTS cadence about 60 Hz, no gaps >25 ms.
- A head-pose metadata track: application/pose, ppos=head, pcrd=3, dtac=9.
  14705 packets of 56 bytes each. Decoding as 7 little-endian doubles yields
  finite values for every packet, distinct payloads, and last-four-element norm
  approximately 1. Coordinate frame, units and field ordering remain unconfirmed.
  Median packet PTS interval is 1 ms, effective rate over the file PTS span is
  about 1019.945 packets/s. This is an output timing observation, not a guarantee
  of independent tracking measurements at that frequency.
- Depth: raw1 / vendor video/raw, 320×240, dtac=2, dprc=dtmm, dlrg=10000,
  distortion model brown. Fifteen samples of 153600 bytes (16 bits/pixel),
  PTS cadence 0.999956 Hz. System TOFCamera logs also report expected 1 Hz.
  Sample numeric values are non-empty; units and invalid/saturation codes
  require confirmation before treating all values as valid distances.
- A separate application/disparity description exists but ffprobe emitted no
  packets for that track; a track declaration alone is not proof of depth data.

The first recording proves RGB, a head-tagged pose track and depth can coexist
in this SDK's MP4. It does not yet satisfy the 5 Hz depth requirement.
The initial file and decoded evidence are local under artifacts/sensors-test/.

## Independent head-pose route

PICO's public Enterprise/ToBService API provides GetHeadPose(long predictTime).
The inspected tob_api-release.aar exposes pbsGetHeadPose(long) returning pose JSON.
Official C# wrapper documentation describes predictTime in nanoseconds and the
return as predicted HMD pose. This is a candidate for real-time head pose if the
recorded pose track is unsuitable. It is not integrated or device-verified by
this recording experiment; prediction time, coordinate frame and timestamp
alignment need care.

Sources:
- Supplied Enterprise-PXRCaptureSDK integration PDF (updated 2026-07-07), sections 1.3 and characteristics callback.
- Public PICO Enterprise API: https://business.picoxr.com/us/doc/EnterpriseAPI-Intro
- Public PICO wrapper: https://github.com/Pico-Developer/PICO-Unity-Integration-SDK/blob/main/Enterprise/Scripts/PXR_Enterprise.cs

## Worn-state comparison: verified

The user confirmed physically wearing the headset with passthrough enabled.
Second recording: DemoPicture-1795685019340.mp4 (~15.8 seconds):

- RGB: 947 HEVC frames, 2048×768, PTS cadence 59.999999 Hz. Intervals
  16.666–16.667 ms, no non-increasing PTS.
- Depth: 76 original raw1 samples, 320×240, all 153600 bytes. PTS cadence
  4.999807 Hz, median 200.011 ms, range 199.711–200.223 ms, no
  non-increasing PTS. All 76 payloads have distinct SHA-256 hashes.
- Head pose: 15521 packets of 56 bytes; all decode to finite 7-double arrays.
  15519 payloads are distinct, and the last-four-element norms remain near 1.
  Field ordering/coordinate frames and the distinction between output packet
  rate and independent tracking updates still need vendor clarification.
- System logs concurrently reported TOFCamera 5:5 and TOF FRAME RATE CHECK 5 FPS.

This trial meets simultaneous RGB60 + depth5 + head-tagged pose recording on
this device/firmware while worn. The preceding off-head trial had depth1.
No SDK fixed-rate setter was added; do not claim this software locks ToF5
regardless of wearing state. The off-head awake setting does not establish
that the sensor must retain the worn-state cadence.

Evidence is local under artifacts/sensors-worn/, including source MP4, original
raw depth samples, raw pose CSV, summary JSON and device log. Depth unit/invalid
codes and pose coordinate semantics remain separate interpretation work.

## Shared-origin dataset export

`scripts/export-aligned.py` implements local post-recording dataset export.
It preserves the original MP4 byte-for-byte and keeps original per-track PTS,
exports raw depth and pose, and adds aligned timeline CSVs and an RGB association
CSV. Every track subtracts the same origin: maximum of the first three sample
PTS. The common-valid window ends at the earliest last-sample PTS.

For DemoPicture-1795685543469.mp4, the origin is 0.614 s; 852 of 891 RGB
samples fall in the common-valid interval. The original video remains complete;
37 leading and 2 trailing RGB samples are excluded from associations only.
Aligned times for pre-overlap samples are negative and are retained in each
raw timeline CSV rather than silently deleted. No track is independently reset
to zero and no sample PTS is rewritten in the original video.

Head pose is matched to the nearest original sample with a default maximum
5 ms difference; ToF uses the most recent sample not newer than the RGB frame
and defaults to 250 ms maximum age. Matches beyond tolerance are explicitly
flagged invalid. The off-head 1 Hz test correctly produces invalid/stale depth
matches under that threshold. These are time-validity flags, not proof that
pose coordinates or depth pixels are physically valid.

```sh
python3 scripts/export-aligned.py video.mp4 --output artifacts/aligned-session
```

The output is self-contained: capture.mp4, original raw samples, original and
aligned timestamp tables, rgb-associations.csv and manifest.json. This currently
runs on the Mac with ffprobe; it is not yet wired into an Android stop-record /
one-click export action. Pose interpolation is deliberately omitted pending
confirmation of quaternion ordering and coordinate semantics. Temporal
association does not calibrate sensor latency or establish exposure sync.
