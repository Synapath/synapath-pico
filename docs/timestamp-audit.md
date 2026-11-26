# PXRCapture timestamp audit

Inspected 2026-11-26 on A9210 firmware 5.15.8. This report concerns the
vendor's original SDK recordings, before our common-window trimming.

## Result

The installed PXR capture service already converts RGB, ToF and head-pose
timestamps into a common monotonic domain. Its pose production path requests
tracking estimates for specified times derived from camera-frame timing.
Additional per-track zeroing would destroy the preserved relative timing.
Our MP4 postprocessing is optional overlap trimming/rebasing, not sensor
synchronization or latency calibration.

This is an interpretation of this firmware's native instructions and recorded
files, not a published vendor guarantee applicable to all firmware versions.
Exposure start/midpoint/end semantics and residual cross-sensor latency remain
unverified.

## Evidence and scope

- The supplied capturelib-0.1.1.aar exposes recording switches for depth and
  pose, but no public per-sample depth/pose callback or timestamp semantics.
  The supplied manual calls preview timestamps mono time without specifying
  exposure reference. Its absence of detail is not evidence of no synchronization.
- AAR libpvrcapturelib.so loads /system_ext/lib64/libpxrcapturendk.so.
  The installed NDK shim forwards into libpxrcaptureclient.so. Consequently,
  the AAR is not the complete recording implementation; firmware matters.
- Read-only examination of /system_ext/lib64/libpxrcaptureservice.so recovered
  native function symbols from its embedded .gnu_debugdata and inspected the
  corresponding AArch64 instructions. No service was patched or restarted.
- Local evidence, disassembly and library SHA-256 values are under
  artifacts/timestamp-audit/. Vendor binaries remain outside version control.

## RGB clock path

PXRVideoProcessor::onProcess at 0x2e600 reads frame timestamp field +0x100,
calls TrackingUtils::qTimerToBoottime (0x2f2a8), then subtracts
TrackingUtils::getMonoToBoottimeOffset (0x2f410). The resulting timestamp is
passed to the surface presentation path. The camera-selection path logs the
same frame field as currentCameraFrame->getQTimerStamp().

This supports a camera-frame-derived presentation timestamp, rather than a
fresh timestamp assigned when disk I/O happens. It does not establish which
point within exposure that frame timestamp denotes.

## Pose generation path

PXRCapture::producePoseDatatoRecorder(long,long), at 0x42c8c:

- The caller at 0x40684 supplies the previously stored frame timestamp and
  the current camera-frame timestamp; after the call it saves the current
  timestamp for the next interval.
- The normal path computes floor((current-previous)/1,000,000) + 1 queries.
  There is a separate cap/fallback for exceptionally large intervals.
- For each query it converts the preceding frame timestamp through the same
  QTimer-to-boottime helper and subtracts the boottime/monotonic offset,
  adding successive 1,000,000 ns steps.
- At 0x42dd8 it calls TrackingSDK::getHeadData(long,algo_result_t*,algo_result_t*)
  with that requested time. It retains the timestamp returned in the result,
  converts it to microseconds, and forwards the batch to
  PxrMediaRecorder::addPoseData at 0x42e34.

Thus PXR queries tracking estimates across camera-frame time intervals; the
pose output is not simply a wall-clock timestamp applied at recorder callback
arrival. The public interface and this caller do not establish whether the
tracking algorithm interpolates history, predicts, or blends the two for any
particular query. They do not establish independent measurement frequency.

At RGB60, an interval of approximately 16.667 ms normally yields 17 queries;
17 x 60 explains approximately 1020 pose packets/s. In the original
DemoPicture-1795685543469.mp4, 13,712 adjacent pose intervals are 1000 us,
with 484 of 667 us and 235 of 666 us, consistent with per-frame query batches.
This explains output cadence, not 1020 independent sensor updates/s.

## ToF clock path

TofFrameListener::onFrameAvailable (0x2b120) forwards a timestamp from frame
metadata into its downstream callback. PXRCapture::onTofFrameAvaliable at
0x40260 retains that timestamp. At 0x4039c it converts the supplied timestamp
with qTimerToBoottime, subtracts getMonoToBoottimeOffset, converts ns to us,
and calls PxrMediaRecorder::addDepthData at 0x403dc. That method forwards the
provided time in an AMessage timeStampUs field rather than replacing it with
the time of the file-write operation.

This supports preservation of a ToF-frame-derived timestamp. It does not
determine whether that metadata denotes integration start/end, measurement
midpoint, or another vendor-defined event. RGB60 and ToF5 remain asynchronous
streams even with a common clock.

## Original MP4 evidence

artifacts/sensors-retest/DemoPicture-1795685543469.mp4 has identical tbtm fields
1795685543551059 on RGB, pose and depth. Its movie timescale is 10000.
The SDK's edit lists preserve leading offsets: pose 0.5287 s and depth
0.6140 s, while RGB begins at file PTS 0. These are preserved startup offsets,
not evidence that each track uses an unrelated zero.

## Development consequence

Preserve SDK timestamps and calibration. Associate RGB with the surrounding
pose estimates and nearby ToF samples using original file PTS; record matching
error and depth age. Any pose interpolation requires confirmed pose schema.
Do not independently reset tracks, fabricate depth60, or describe file
trimming as exposure synchronization. Actual RGB/ToF exposure reference,
pose prediction semantics and residual latency need vendor clarification or
a separate physical validation experiment.
