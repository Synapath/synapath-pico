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

## Dual calibration export: sensors.5

See [calibration-output.md](calibration-output.md). Actual mode A/B testing shows
that this firmware's LENS_RGB calibration callback changes with output mode. The
app now temporarily queries RAW mode at startup, caches its calibration, restores
the requested undistorted output and records that mode's calibration separately.
Both are shown and exported together, including per-record automatic JSON linked
to video SHA-256. The FOV-derived helper is not used for the final output K.
Original-mode values refer to the requested single-eye resolution, not assumed
full sensor dimensions. Missing SDK fields are retained as unavailable.

## Current saving policy: sensors.3

From sensors.4, the default RGB image mode is the official Demo's undistorted
mode (`KEY_OUTPUT_CAMERA_RAW_DATA=VALUE_FALSE`). The checkbox is enabled by
default and both initial startup and permission-grant startup read its state.
The mode is passed explicitly on every configure call; unchecking selects
`VALUE_TRUE`. Saved view state does not restore the old RAW default. No
application-side remapping or MP4 timestamp rewrite is introduced. Intrinsics
and extrinsics must still be interpreted for the selected output mode; switching
mode is not proof that the recorded raw-camera calibration describes the
processed pixels. Earlier recording measurements below retain their original
mode and are not retroactively described as undistorted.

The default save path now uses `PicoTimeline.inspect` and `copyUnchanged`,
preserving the complete SDK MP4 byte-for-byte. After SDK stop returns, the APK
reads the original edit lists, sample timing and RGB/head-pose/depth `tbtm`
fields. Matching positive base fields and nonempty tracks are reported as a
shared file timebase. This does not certify exposure reference or sensor latency.

The video is copied into a pending MediaStore item. A SHA-256 digest is computed
over the source bytes during copying and compared with a digest obtained by
reopening the destination. Only a matching copy is published. Source recordings
remain in `.SynapathPending`; no leading/trailing frames, PTS, edit lists,
calibration or metadata are modified. Inconsistent bases or missing tracks are
reported visibly while preserving the original recording. Structural inspection
or copy failures retain the source and do not publish a partial result.

Audit JSON is stored in the app external-files `timelines/` directory, with
`method=preserve_pxr_timeline`, `pts_shift_us=0`, `trimmed_samples=0`, original
per-track first/last PTS, counts, vendor bases and the verified SHA-256. It also
states `sensor_latency_calibrated=false`. There is no new synchronization UUID
inside the MP4 because its bytes are unchanged. The original vendor timing is
already self-contained in the MP4.

The previous `Mp4TimelineAligner` remains as an unused historical utility. It is
not invoked by APK saving. Neither of the other development branches is merged.

Validation on 2026-11-26:

- Docker build of versionCode 6 / `0.1.1-sensors.3` succeeded and was installed
  on A9210 firmware 5.15.8.
- The same Java inspector/copy implementation ran in Docker against three
  actual SDK recordings (1 Hz and 5 Hz depth). All output files were byte-identical
  to their source. Independently parsed ffprobe sample counts and first/last
  PTS matched the Java reports within 1 us. The 5 Hz retest retained all 891 RGB,
  14586 pose and 72 depth samples, including the original startup intervals.
- On-device Stop published `Synapath-1795689421806.mp4` and automatically
  restored preview. Pulling both source and published files independently
  confirmed identical SHA-256
  `9618b9d223eb1c5ea3f239b4f2327d38cb4d1c6f3d2210df3cc26d7741bd2c3e`.
- That recording contains 1071 RGB frames at approximately 60 FPS, 17629 pose
  packets and 17 depth frames at approximately 1 Hz. The rate observation is
  separate from timing preservation; this change does not force ToF5.
- Shared vendor base is 1795689421909257; original first PTS values are RGB0,
  pose0.5496 s and depth1.0845 s. No first samples were discarded or independently
  zeroed. Source and output have all original audio and vendor metadata as well.
- Local evidence is in `artifacts/timeline-preserved/`, including independent
  verification JSON, real-device source/output, APK audit JSON and device logs.

## Historical common-window cropping: sensors.2 (superseded)

The vendor already maps RGB, pose and depth onto a common MP4 timeline.
The operation below trims to an overlapping interval and rebases that existing
timeline; it does not establish or improve physical sensor synchronization.
See [timestamp-audit.md](timestamp-audit.md) for the firmware-level evidence,
including time-targeted pose queries and ToF timestamp conversion.

The previous sensors.2 APK finalized the MP4 before publishing it to the video
library. Its historical behavior and validation follow; sensors.3 does not use
this rewrite path.

1. SDK records RGB, raw depth, head pose, audio and vendor metadata into
   `/sdcard/Movies/.SynapathPending/raw-<timestamp>.mp4`.
2. On Stop, the SDK closes the recording. Background processing selects a common
   RGB/head-pose/depth time window and advances the start to the next RGB sync
   sample (keyframe) to retain decodability without re-encoding.
3. Mp4TimelineAligner copies retained sample payloads unchanged, rebuilds sample
   tables, and shifts all timed tracks by the same origin. It preserves vendor
   sample descriptions, calibration and coordinate tags; PICO tbtm base fields
   advance by that same origin to remain consistent with the new relative PTS.
4. The completed result is published through MediaStore into
   `/sdcard/Movies/Synapath/Synapath-<timestamp>.mp4`. The original is retained
   separately in the hidden pending directory. Failed processing does not publish
   an incomplete/unverified final file and does not delete the original.

The in-file moov/uuid identifier `SYNAPATHPICOTIME` contains JSON with schema,
origin_us, duration_us, method and sensor_latency_calibrated=false. This allows
an original relative timestamp to be reconstructed as new_pts + origin_us.
Files already carrying that marker are rejected by the aligner to prevent
accidental repeated cropping. Timestamps for different sampling rates remain
different; e.g. the first pose can be a fraction of a millisecond after RGB0,
and the first retained ToF can occur later. They share one axis; they are not
independently reset to zero and no depth/pose samples are synthesized.

Validation:
- JVM runs the same Android Java implementation against the previous 5 Hz file.
  Origin=1 s; 830 RGB, 14105 pose, 70 depth samples retained. All retained
  payload hashes match original samples, and required-track PTS equals original
  PTS minus 1 s (within ffprobe text precision). HEVC fully decodes without errors.
- Device end-to-end recording raw-1795687766686.mp4 produced
  Synapath-1795687766686.mp4 automatically. Origin=2 s; duration=19.993544 s;
  1200 RGB, 20392 pose and 20 depth samples retained. ToF was 1 Hz in this
  device state; alignment does not change the input rate.
- File verification confirms all vendor sample descriptions are unchanged except
  for the intentional tbtm offset, and retained payloads are unchanged. RGB,
  head pose and depth PTS each shift by exactly 2 s at ffprobe precision;
  audio differs by at most 1 us due to timescale rounding. RGB remains 60 FPS.
- Evidence: artifacts/apk-alignment/device-verification.json and device MP4s.

```sh
python3 scripts/verify-aligned-mp4.py raw.mp4 aligned.mp4 --output verification.json
```

This writer targets the observed non-fragmented PXRCapture profile, with
monotonically ordered sample PTS and supported sample tables. Unsupported
fragmentation, encryption/sample-table extensions or complex edit lists are
rejected rather than silently discarded. Short recordings without a common
RGB keyframe interval cannot be published as aligned output. File durations
that overflow the current 32-bit movie duration field are rejected. Keep the
app in the foreground while the save operation completes. Original acquisition
latency, exposure timing semantics and coordinate definitions are not calibrated
by this operation. Long-recording/background interruption robustness remains
additional production validation work.
