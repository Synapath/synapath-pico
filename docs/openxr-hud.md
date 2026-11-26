# OpenXR head-follow HUD experiment

Branch: `feature/openxr-headlocked-hud`, based on `047e212` from
`feature/recording-passthrough-hud`. No merge into main or the 2D HUD branch.

## Scope

Independent APK/package `com.synapath.picopipeline.openxr`, launcher name
**Synapath PICO OpenXR**. The previously installed **Synapath PICO HUD** stays
installed. Only one should use the camera at a time.

`OpenXrActivity` owns one PXRCaptureSDK client on a dedicated camera Looper.
It requests exactly the same SDK configuration as the 2D pipeline: 2048×768
side-by-side RGB, 60 fps, RAW=true, MCTF/EIS/MFNR=true, no SDK startRecord.
`PreviewRecorder` continues to encode fresh SDK SurfaceTexture frames using
MediaCodec, with the existing timestamp CSV and 60 Hz maximum selection rule.
The only shared renderer change is replacing the GLSurfaceView dependency with
a render-request callback, plus an optional observer for source timestamps.

`openxr_hud.cpp` creates an ES3 recordable EGL context with a pbuffer, a primary
OpenXR session, FB passthrough, and an alpha-blended quad in
`XR_REFERENCE_SPACE_TYPE_VIEW`. Its fixed VIEW-space pose makes the HUD follow
head rotation and translation. The XR frame loop pumps the camera texture and
encoder without depending on an Android 2D window swap. Timer and circle are
uploaded to a separate XR swapchain; they never enter the RGB encoder.

The XR session does not request a display refresh rate or change global PICO
settings. This does not guarantee the runtime will leave camera scheduling
unchanged: source timestamps must still be checked on each firmware/device.

## Controls

Aim a controller at the circle. Press the trigger once to start. While recording,
keep the ray on the circle and hold the trigger for 2.5 seconds to stop and save.
Release or move outside the circle to cancel a partial hold. Recording shows
only the timer, circle and small pointer; no explanatory text underneath.

Bindings: OpenXR simple controller select, PICO4/PICO4 Ultra controller trigger and aim.
`XR_BD_controller_interaction` is enabled when advertised by the runtime; both
PICO4 and PICO4s profiles then bind successfully on this device. The Ultra profile
path follows [PICO's published controller implementation](https://github.com/Pico-Developer/PICO-Unity-SDK/blob/main/XR/Runtime/Scripts/OpenXRFeatures/Interactions/PICO4UltraControllerProfile.cs).
Hand tracking/pinch input has not been implemented. This is an experimental XR
front end, not a port of the complete Android settings panel.

On Activity pause or XR shutdown, the SDK preview is closed and an active encoder
is finalized before destroying the EGL context. Opening the PICO system menu can
remove XR focus without pausing the Android Activity; loss of focus cancels holds.

## Build and run

Public OpenXR headers are pinned to Khronos OpenXR-SDK `release-1.1.46`, with its
Apache-2.0 license in `third_party/openxr/LICENSE`.

The local, ignored PICO OpenXR loader is 3.0.0-20250422, copied from
`operator-origin/xr/thirdparty/pico_openxr_loader/3.0.0-20250422/arm64-v8a/libopenxr_loader.so`
to `third_party/pico/openxr/arm64-v8a/libopenxr_loader.so`.
SHA256: `79882748101e096550f9eccac59f4c4ea6eb99c500001975abe7736bb54d4033`.
It is separate from the unmodified capturelib-0.1.1.aar.

```sh
PICO_DEV_IMAGE=operator-origin-dev:local ./scripts/build-apk.sh
./scripts/install-apk.sh
adb shell am start -n com.synapath.picopipeline.openxr/com.picoxr.capturesdkdemo.pipeline.OpenXrActivity
```

The build script compiles the JNI library using NDK 28.1.13356709 inside Docker,
then builds the Android APK. Output: `artifacts/SynapathPico-OpenXR-debug.apk`.

For an explicit automatic 10-second test, start a fresh Activity with
`--el auto_record_ms 10000`. The normal launch never auto-records. An automated
run stays idle after saving; relaunch without the extra for manual controls.

Files go to `/sdcard/Android/data/com.synapath.picopipeline.openxr/files/`:

- `OpenXR-*.mp4`: camera RGB only.
- `OpenXR-*.frames.csv`: selected encoder input timestamps (same format as 2D).
- `OpenXR-*.source.csv`: every fresh SDK texture timestamp observed while recording,
  before the encoder selection rule. This still measures SDK-delivered textures,
  not sensor exposures or every frame internal to the camera service.

Compare both CSVs and decoded MP4 frame counts. MP4 metadata alone cannot establish
camera cadence. Existing Android muxer timestamp rounding remains unchanged.

## Initial hardware observation — 2026-11-26

PICO A9210/A9410 Enterprise, firmware 5.15.8. Docker build and device installation
succeeded. User confirmed the HUD follows the view and real-world passthrough
remains visible.

First automatic run started its clock before camera warm-up; its saved camera
span is 9.567065417 seconds, 575 frames, MP4 duration 9.583333 seconds. Source
timestamps: 59.997499 Hz, median 16.668177 ms, maximum 16.668230 ms, no intervals
over 25 ms. SDK preview logs advanced 60 fresh textures per approximately one
second. This single run showed no observed capture-rate regression relative to
the existing 2D HUD baseline (~59.9975 Hz).

Automatic timing was then moved to the first delivered frame and a pre-selection
source CSV was added. Further test results belong below; initial results alone
do not prove all device/firmware combinations or long recordings behave alike.

Second automatic run: 601 pre-selection source timestamps, 601 encoder-input
timestamps and 601 decoded MP4 frames. Source rate 59.99747854 Hz, maximum
interval 16.668230 ms, no intervals over 25 ms. MP4 duration 10.016667 seconds,
reported 60/1 fps. The pre-selection and encoder timestamp sequences match.
An extracted frame shows only stereo camera RGB, with no HUD composited into it.

Manual run with the final controller bindings: user confirmed trigger start and
2.5-second hold stop, then the saved message. Log records
`XR_HOLD_COMPLETED duration_ms=2500` followed by SAVED, 405 frames. Source count,
encoder count and decoded count all equal 405; 59.99747605 Hz, maximum interval
16.668230 ms, no intervals over 25 ms; MP4 duration 6.75 seconds.

Switching away and returning recreated the XR session and SDK preview in the
same app process, with new rendering thread and `startPreview=1`. The older 2D
HUD was briefly opened during this test. PICO allows a 2D window and an XR app
to coexist on separate displays, so merely launching another app is not a
reliable way to release camera ownership. After the test the old HUD was stopped
while idle, leaving only the XR experiment running and not recording.

Local ignored evidence: `artifacts/openxr-test/{second,manual}.mp4`, corresponding
CSVs, `final.log`, `verification.json`, and `rgb-only.png`.
