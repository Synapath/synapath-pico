# Synapath PICO — RGB / ToF / head-pose probe

Branch: `feature/rgb-tof-headpose`, based on `baseline/sdk-recording-ui3`.
Separate directory: `../synapath-pico-sensors`. The app-recording-preview branch
has not been merged. This branch uses the SDK's original startRecord path.

The supplied capturelib-0.1.1.aar exposes three recording switches:
`KEY_WRITE_DEPTH_DATA`, `KEY_WRITE_POSE_DATA`, and `KEY_WRITE_CAMERA_PARAMS_DATA`.
This probe enables all three while preserving requested RGB 60 FPS, 2048×768,
RAW mode, 10×1024×1024 bit/s and the baseline enhancement settings.

The app is **Synapath PICO Sensors**, package `com.synapath.picosensors`.
It installs alongside both stable and application-encoding experiment apps.
Stop other camera apps before launching this probe.

```sh
PICO_DEV_IMAGE=operator-origin-dev:local make build
make install
adb shell pm grant com.synapath.picosensors android.permission.CAMERA
adb shell pm grant com.synapath.picosensors android.permission.RECORD_AUDIO
adb shell pm grant com.synapath.picosensors com.picovr.permission.SPATIAL_DATA
adb shell am start -n com.synapath.picosensors/com.picoxr.capturesdkdemo.MainActivity
```

Recordings appear in `/sdcard/Movies/DemoPicture-*.mp4`. After pulling a recording:

```sh
python3 scripts/analyze-sensors.py video.mp4 --output artifacts/extracted
```

The script identifies pose MIME from MP4 sample descriptions, summarizes packet
PTS, saves pose payloads as seven raw double columns, and exports depth samples
without modifying their bytes. Check `summary.json` rather than relying solely
on configured switches.

Initial off-head test: RGB 60 FPS; head pose present; 320×240 raw depth present
at about 1 Hz, not the requested target 5 Hz. Wearing-state comparison is needed.
See [SDK capability findings](docs/sensor-capabilities.md).
