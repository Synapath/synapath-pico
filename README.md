# Synapath PICO — passthrough recording HUD

Branch: `feature/recording-passthrough-hud`, based on the concurrent preview /
recording experiment at `feature/app-recording-preview` (2d06d6d). The original
branch and main are retained. This feature has not been merged or pushed.

The app is **Synapath PICO HUD**, package `com.synapath.picopipeline.hud`, version
`0.1.1-pipeline-hud.2`. It installs alongside the original Pipeline and Sensors
apps. Close other camera apps before opening this one.

## Recording interaction

- “录制时隐藏面板，保留透视视野” is enabled by default.
- Starting a recording hides the application panel and camera image from the
  display. The recording timer, central circular stop control and short hint
  remain visible over a transparent background.
- Press and hold the circle continuously for four seconds to stop. A ring shows
  progress. Releasing early cancels; a delivered move outside the circle,
  cancellation, multiple pointers or focus loss also cancels the hold.
- Saving drains and closes the encoder, then restores the panel and live preview.
- Unchecking the option retains the preview panel while recording. This preserves
  the earlier simultaneous preview/recording behavior; stopping still uses the
  four-second circle.

The GL input surface stays active when the panel is hidden. The SDK still uses
one preview stream; the app encodes its camera texture to a separate AVC surface.
Only the display receives the HUD. The timer and stop control are not burned into
recorded video. Camera configuration remains the original pipeline experiment's
RAW mode, requested 60 FPS and 2048×768 stereo output; this branch does not merge
main's depth/pose/calibration implementation.

## Build and install

```sh
PICO_DEV_IMAGE=operator-origin-dev:local make build
make install
adb shell pm grant com.synapath.picopipeline.hud android.permission.CAMERA
adb shell am start -n com.synapath.picopipeline.hud/com.picoxr.capturesdkdemo.pipeline.PipelineActivity
```

APK: `artifacts/SynapathPico-RecordingHUD-debug.apk`. The vendor AAR stays local in
`third_party/pico/` and is checked by SHA-256 before building.

Silent AVC recordings and their `.frames.csv` source-timestamp companions are in:

```text
/sdcard/Android/data/com.synapath.picopipeline.hud/files/AppPipeline-*.mp4
```

The existing application pipeline selects fresh source frames at up to 60 Hz;
MP4 duration smoothing still applies. Keep the CSV when original source timing
matters. No physical camera exposure-frequency guarantee is inferred from file FPS.

See [HUD implementation and device tests](docs/recording-hud.md) and the
[original pipeline findings](docs/app-pipeline.md).
