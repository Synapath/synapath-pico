# Synapath PICO — OpenXR UI with RGB / ToF / head-pose capture

This worktree is on `feature/openxr-main-ui`, based on main (`7cbbfd6`).
Main and the earlier `feature/openxr-headlocked-hud` experiment are retained.
This branch reuses all four main-branch Android pages inside a native OpenXR
VIEW-space quad. It adds transparent recording HUD, a 2.5-second hold-to-save
circle, and controller-ray-to-Android touch input.

The main branch contains the RGB / ToF / head-pose capture implementation,
developed on `feature/rgb-tof-headpose` from `baseline/sdk-recording-ui3`.
It uses the SDK's original startRecord path. The separate
`feature/app-recording-preview` encoder experiment has not been merged.

The supplied capturelib-0.1.1.aar exposes three recording switches:
`KEY_WRITE_DEPTH_DATA`, `KEY_WRITE_POSE_DATA`, and `KEY_WRITE_CAMERA_PARAMS_DATA`.
This probe enables all three while preserving requested RGB 60 FPS, 2048×768,
SDK undistorted output by default, 10×1024×1024 bit/s and the baseline
enhancement settings. In `0.1.1-sensors.4`, “去畸变图像” is checked on launch;
the app explicitly sets `KEY_OUTPUT_CAMERA_RAW_DATA=VALUE_FALSE`. Unchecking
it requests the SDK camera RAW mode instead. This uses the official Demo's
mode mapping; the app does not add a separate image-remapping pass.

The app is **Synapath PICO Sensors XR**, package `com.synapath.picosensors.openxr`,
version `0.1.1-sensors-xr.2`. The other apps remain independently installable.
It installs alongside both stable and application-encoding experiment apps.
Stop other camera apps before launching this probe.

```sh
PICO_DEV_IMAGE=operator-origin-dev:local make build
make install
adb shell pm grant com.synapath.picosensors.openxr android.permission.CAMERA
adb shell pm grant com.synapath.picosensors.openxr android.permission.RECORD_AUDIO
adb shell pm grant com.synapath.picosensors.openxr com.picovr.permission.SPATIAL_DATA
adb shell am start -n com.synapath.picosensors.openxr/com.picoxr.capturesdkdemo.MainActivity
```

The new APK requests camera, microphone and SPATIAL_DATA permissions itself.
Denying the spatial permission prevents the combined sensor recording from starting.
Build output: `artifacts/SynapathPico-Sensors-OpenXR-debug.apk`.

The Docker build also compiles the JNI renderer with NDK 28.1.13356709.
Place the local PICO loader 3.0.0-20250422 at
`third_party/pico/openxr/arm64-v8a/libopenxr_loader.so` (ignored).
SHA256: `79882748101e096550f9eccac59f4c4ea6eb99c500001975abe7736bb54d4033`.
Public headers are pinned to Khronos OpenXR-SDK release-1.1.46; its license is
included under `third_party/openxr/`.

The main Android layout renders to double-buffered 1600×900 textures at about
10 Hz; the preview uses PixelCopy from the existing SDK SurfaceView. The reticle
is drawn separately on the native XR thread at the runtime's frame cadence
(observed around 90 Hz on this device). During recording the full panel and
preview are hidden, leaving timer and hold circle over real-world passthrough.
These display refresh rates do not set RGB recording cadence. All recorded
tracks still come from SDK startRecord, with no app-side RGB re-encoding.

Android view caches and snapshot bitmaps use the same 240-dpi density to avoid
HUD clipping. Controller aim is located in LOCAL space and transformed into VIEW;
the initial implementation returned fixed center coordinates. Explicit pose-state
queries and the LOCAL-to-VIEW conversion corrected controller movement in tests.
The front end currently supports controller ray/trigger input, not hand pinch.

After Stop, version `0.1.1-sensors.3` preserves PXR's existing common timeline
and publishes `/sdcard/Movies/Synapath/Synapath-*.mp4`. No cropping, timestamp
shift, remuxing or sample synthesis occurs. The published MP4 is a byte-identical
copy of the SDK recording, verified by SHA-256 readback before publication.
Wait for the save confirmation. The SDK file remains in
`/sdcard/Movies/.SynapathPending/` for traceability.

The APK reads original MP4 edit lists and checks RGB/pose/ToF vendor time-base
fields. Different first-sample times are preserved. Missing sensor tracks or
inconsistent base fields produce a visible warning, never an invented alignment.
An audit JSON is saved under the app's external files directory in `timelines/`;
the MP4 already contains its original vendor timing and needs no sidecar to
retain the existing track timing. This inspection verifies file-level metadata,
not physical sensor latency or exposure synchronization.
See [timestamp findings](docs/timestamp-audit.md).

Version `0.1.1-sensors.5` shows and exports both calibration sources:

- `raw_camera_calibration`: SDK RAW-mode RGB intrinsics, extrinsics and distortion
  coefficients for the requested single-eye dimensions. Original packed bytes
  are preserved as Base64 and little-endian float64 values.
- `output_image_parameters`: the selected mode's actual SDK calibration callback,
  including per-camera intrinsics/K and packed extrinsics. It uses no fixed-FOV
  projection helper. Camera packing and independent physical projection validation
  remain explicitly distinguished from vendor-reported values.

When undistorted output is selected, startup briefly configures RAW to read its
calibration, then reopens a fresh capture client in undistorted mode to read the second set.
Recording stays disabled during the RAW calibration pass. SDK callbacks are
mode-dependent on firmware 5.15.8; one callback is not mislabelled as both sets.
The calibration page refreshes after mode changes. Each recording freezes both
sets at Start and exports `Synapath-<timestamp>.calibration.json` to
`/sdcard/Download/Synapath/Calibrations/` at Stop, linked to the MP4 SHA-256 and
vendor timeline base. Manual export writes both sets into one JSON there too.
The MP4 and its original vendor calibration remain byte-identical to SDK output.
See [calibration output details](docs/calibration-output.md).

After pulling a recording:

```sh
python3 scripts/analyze-sensors.py video.mp4 --output artifacts/extracted
```

The script identifies pose MIME from MP4 sample descriptions, summarizes packet
PTS, saves pose payloads as seven raw double columns, and exports depth samples
without modifying their bytes. Check `summary.json` rather than relying solely
on configured switches.

Verified worn-state test: RGB 60 FPS; head pose present; 320×240 raw depth
at about 5 Hz (76 distinct samples). A stationary off-head trial produced depth
at about 1 Hz; a later moving off-head trial also reached 5 Hz. Wearing state
alone does not explain the rate. This branch does not force ToF to stay at 5 Hz.
See [SDK capability findings](docs/sensor-capabilities.md).


## OpenXR validation — 2026-11-26

PICO 4 Ultra Enterprise, firmware 5.15.8. Docker debug build and installation
passed. The user confirmed the pages can be switched, the reticle moves smoothly,
and the timer and entire circle are visible. Continuous 2.5-second controller
hold stopped and saved recordings. Preview resumed after final save.

The reticle follows the last trigger-selected hand (right by default), rather
than jumping to a passive controller between hover and press. In a fresh install,
SPATIAL_DATA is requested together with camera and audio; the Android 14-compatible
permission callback initializes capture after all three grants.

A 144.5-second SDK recording contained 8,671 RGB frames (60.0 fps), 720 depth
frames at 4.99979 Hz and 146,846 finite pose packets. All timestamps increased.
The sensor packet rate is an output rate, not independent pose-measurement rate.

The final saved test contained 678 RGB frames at 60.000002 fps, 54 depth frames
at 4.99977 Hz, and 10,948 pose packets. The app verified the RGB/ToF/Pose common
vendor time base, applied zero timestamp shift and trimming, and verified a
byte-identical MediaStore copy by SHA-256. The separately pulled calibration
passed `scripts/verify-calibration.py`: preserved RAW coefficients, actual
undistorted intrinsics matching the MP4, and matching video SHA-256.

Local ignored evidence is under `artifacts/main-ui-xr-test/`: final video,
calibration, timeline audit, extracted sensor summary and device logs.
This branch is not merged or pushed. The original SDK configuration, MP4
inspection/copy implementation, calibration parser and Android page XML are
retained from main. XR changes affect display, touch routing, permission handling
and preview restoration.


## Dual-controller rays and color check — 2026-11-26

Version .2 renders both controller rays in a separate stereo projection layer.
The lines are thin, faded and softly antialiased; a full line appears when aimed
at the panel, otherwise only a short 28-cm guide is shown. Hollow landing rings
use subtle left/right accents. Both controllers remain visible while a press is
owned by one hand until release, preventing another held trigger from taking over
a stop hold. Reticles and beams update at XR cadence and never enter SDK MP4 data.
The user confirmed the refined appearance and operation with either hand.

A fixed-scene comparison used unmodified main's 2D Sensors APK, then this XR APK,
with the headset held stationary. RGB packet timing was about 60 Hz in both.
All pose packets were finite and distinct (2D: 9537; XR: 9690), with monotonically
increasing timestamps and maximum packet gaps of about 1.1 ms. Depth samples
were 320×240, 153600 bytes each; all 9 XR depth payloads were distinct. Both
versions dropped to about 1 Hz ToF while stationary, consistent with earlier
motion/idle tests; this remains an adaptive SDK rate rather than a forced 5 Hz.
The SDK vendor time base, byte-identical copy and dual calibration checks passed.

Both files reported HEVC Main, yuv420p, limited range, smpte170m matrix, bt709
transfer and bt470bg primaries. At seconds 3/5/7, aligned valid left-eye pixels
had RGB mean absolute difference 2.65–2.69 on an 8-bit 0–255 scale; each channel's
mean offset was below 0.7. This run did not reproduce a large color shift. It is
one scene, not calibrated colorimetry or an explanation of every earlier clip.
Recording color processing and SDK camera parameters have not been changed.
Evidence: `artifacts/rays-color-review/{fixed-2d,fixed-xr}.mp4`, accompanying
calibrations, `verification.json` and `color-comparison.json` (local, ignored).

For repeatable device tests, debug builds accept an explicit shell broadcast:

```sh
adb shell am broadcast -a com.synapath.picosensors.openxr.DEBUG_CAPTURE \
  -p com.synapath.picosensors.openxr --es command record --el duration_ms 10000
```

The receiver requires the sender's Android DUMP permission and is not registered
in release builds. It calls the same recording and save functions as the UI and
stops only its matching recording, with duration constrained to 1–30 seconds.
