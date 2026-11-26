# Synapath PICO — OpenXR head-follow HUD experiment

Branch: `feature/openxr-headlocked-hud`, based on `047e212` of
`feature/recording-passthrough-hud`. Main and the working 2D HUD branch are retained.
This experiment has not been merged or pushed.

**Synapath PICO OpenXR**, package `com.synapath.picopipeline.openxr`, version
`0.1.1-openxr.1`, installs alongside **Synapath PICO HUD**. Close other camera apps
before starting it.

OpenXR renders a head-follow timer and stop circle over FB passthrough.
PXRCaptureSDK remains the RGB source. The app still requests 2048×768 stereo,
60 FPS, RAW output and encodes fresh SDK textures with the existing MediaCodec
pipeline. The HUD is absent from saved video. Main's depth/pose/calibration
implementation is not part of this experimental pipeline branch.

Aim a controller at the circle and press its trigger to start. While recording,
hold the trigger on the circle continuously for **2.5 seconds** to save; release
or move away to cancel a partial hold. No instruction text appears below the
recording circle. Hand/pinch input is not implemented in this experiment.

```sh
PICO_DEV_IMAGE=operator-origin-dev:local ./scripts/build-apk.sh
./scripts/install-apk.sh
adb shell pm grant com.synapath.picopipeline.openxr android.permission.CAMERA
adb shell am start -n com.synapath.picopipeline.openxr/com.picoxr.capturesdkdemo.pipeline.OpenXrActivity
```

APK: `artifacts/SynapathPico-OpenXR-debug.apk`. The capture AAR and PICO OpenXR
loader remain local, ignored vendor files; see the [setup instructions](docs/openxr-hud.md).

Recordings live under
`/sdcard/Android/data/com.synapath.picopipeline.openxr/files/OpenXR-*`.
Keep the MP4, `.frames.csv` and `.source.csv` together. The source CSV records
all fresh SDK textures observed during recording before encoder selection;
it does not establish physical sensor exposure rate. Existing Android muxer
timestamp rounding remains unchanged.

On PICO Enterprise firmware 5.15.8, the user confirmed head-follow and visible
passthrough. A 10-second recording contained 601 delivered source frames and
601 encoded frames, source cadence **59.99748 Hz**, maximum interval 16.66823 ms,
no intervals over 25 ms. This is a measured device result, not a guarantee for
other firmware or longer sessions.

See [OpenXR implementation and validation](docs/openxr-hud.md),
[previous 2D HUD](docs/recording-hud.md), and
[original pipeline findings](docs/app-pipeline.md).
