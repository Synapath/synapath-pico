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

## Pending verification

Repeat while physically wearing the headset with passthrough enabled. Measure
actual depth packet intervals and RGB cadence together. Do not synthesize five
copies of a 1 Hz depth image to claim 5 Hz. A fixed rate setting or firmware
requirement must be confirmed if wearing alone does not produce 5 Hz.
