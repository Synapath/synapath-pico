# Synapath PICO — application recording experiment

Branch: `feature/app-recording-preview`. Separate worktree:
`../synapath-pico-app-pipeline`. The stable `../synapath-pico` directory and its
installed application are retained. The stable source snapshot is
`baseline/sdk-recording-ui3` (af94883).

This branch tests a different recording path:
PXRCapture.startPreview → SurfaceTexture → OpenGL → screen + MediaCodec → MP4.
It never calls the SDK's startRecord. A single GL thread distributes each SDK
image to the view and encoder, with no CPU pixel readback. Camera service calls
use a dedicated HandlerThread.

The experimental app is **Synapath PICO Pipeline**, package
`com.synapath.picopipeline`. It installs alongside stable package
`com.synapath.capturesdkdemo011`. Only one camera application should run at a time.

```sh
PICO_DEV_IMAGE=operator-origin-dev:local make build
make install
adb shell am start -n com.synapath.picopipeline/com.picoxr.capturesdkdemo.pipeline.PipelineActivity
```

APK: `artifacts/SynapathPico-AppPipeline-debug.apk`. Vendor AAR remains local in
`third_party/pico/`, checked against the validated SHA-256 by the build script.
Build caches and debug signing volume are shared with the stable development
setup; application source, artifacts, package and Compose project are separate.

Recordings are silent AVC MP4 files in
`/sdcard/Android/data/com.synapath.picopipeline/files/AppPipeline-*.mp4`.
Each recording also writes `.frames.csv` containing the SDK SurfaceTexture source
mono timestamp and encoder presentation time. Submitted presentation time is source time
minus the first selected frame timestamp. The Android MP4 path can smooth small
interval differences; the CSV remains authoritative for source timing. The recorder selects fresh source
frames at up to 60 Hz; it does not repeat a texture to fill missing samples.
A 72 Hz source therefore can yield irregular 60 FPS selection intervals rather
than artificial constant 16.667 ms exposure timestamps. Frame availability
notifications may coalesce; no guarantee of receiving every SDK image is made.

This initial branch validates concurrent preview and video recording. It does
not yet include audio, session management, production UI, or reliable sustained
performance guarantees. Hardware validation and PTS inspection are required.
No result here establishes the physical sensor's exposure frequency.

Implementation, measured results, and timestamp limitations: [app pipeline](docs/app-pipeline.md).
