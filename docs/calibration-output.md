# Original-mode and undistorted-output calibration

From versionCode 8 / 0.1.1-sensors.5, the calibration page and JSON export contain
two independently acquired, mode-specific SDK snapshots.

## Firmware finding

On firmware 5.15.8, the characteristics callback changes with the selected mode.
Its LENS_RGB_*_CALIBRATION key names do not mean it always returns physical RAW
calibration. A/B testing on the same headset at 2048x768 stereo output produced:

- RAW camera intrinsics, packed camera 0: fx468.3843176746994,
  fy463.0847816066107, cx504.4656073268551, cy383.32621652670883.
- Undistorted output, packed camera 0: fx651.2204511158952,
  fy651.2526837407746, cx511.5, cy383.5.
- RAW callback provides 16 packed distortion doubles. Undistorted callback
  does not provide a distortion coefficient blob; missing data is not replaced
  by invented zero coefficients.
- Both modes return 8 intrinsic doubles (4 per camera) and 24 extrinsic doubles.
  These arrays are retained in full, without guessing the extrinsic convention.
- This firmware does not return the optional spatial-video default dimensions or
  FOV keys. Assuming those fields are available would leave output K unavailable.

Native code independently explains this: PXRCapture::writeCameraParamsToCharacteristics
(0x41ac0 in libpxrcaptureservice.so) branches on RAW mode, invoking
parseOriginParamsToCharacteristics (0x42f1c) with requested per-eye width and height
or parseSpatialParamsToCharacteristics (0x450c4). Thus RAW-mode intrinsics are
scaled for the requested output resolution, not necessarily full sensor resolution.

The separate getCameraIntrinsics helper forwards to PieGetCameraIntrinsics in
libpico_imaging_engine.so. That helper constructs a pinhole model from caller
width/height/FOV, rather than reading physical camera calibration. Its four-value
order is cx,cy,fx,fy, different from the callback's fx,fy,cx,cy packing. The final
implementation uses the actual mode-specific callback for both sets, never the
FOV helper. Earlier candidate-based implementation was superseded before final
validation; neither missing FOV nor a fixed default is used to synthesize K.

## Acquisition and UI

For undistorted recording, startup temporarily configures RAW mode, captures its
callback snapshot, fully closes the capture client on the UI Looper (outside
SDK callbacks), then opens a fresh client in the requested undistorted mode and
captures its callback separately. Reset alone retains RAW-only distortion/model
keys on this firmware; reopening the client prevents those stale fields from
being mislabelled as undistorted-output distortion.
Recording controls remain disabled during the RAW calibration pass. The previous
session's cache is cleared when reopening the camera. Old-object reset/close
callbacks are ignored. A callback timeout restores the requested output but marks
missing RAW calibration unavailable instead of mislabelling processed values.

`raw_camera_calibration` contains RAW-mode parameters at the current per-eye
resolution. `output_image_parameters` contains the current output mode's SDK
callback parameters. Both include packed original bytes in Base64, byte counts,
float64 little-endian decoded values, validity, mode, resolution and acquisition
time. Intrinsic arrays of 8 finite doubles are additionally exposed as two
camera-indexed fx/fy/cx/cy sets and 3x3 K matrices. Physical left/right mapping and
extrinsic transform conventions are not silently assumed. Values are reported by
the vendor; independent physical projection validation is explicitly false.

## Export and recording association

The button exports both sections into one full-precision JSON under
`/sdcard/Download/Synapath/Calibrations/`. Each recording freezes a snapshot at Start
and automatically writes `Synapath-<timestamp>.calibration.json` at Stop. It contains
video filename, verified MP4 SHA-256 and vendor timeline base, associating the
snapshot with that recording rather than a later camera configuration.

The SDK MP4, sample times and embedded vendor calibration remain untouched. The
JSON is an additional artifact; no MP4 remux or timestamp rewrite is introduced.

```sh
python3 scripts/verify-calibration.py capture.calibration.json --video capture.mp4
```

The verifier checks full-precision decoded calibration against preserved SDK
bytes, per-eye/canvas dimensions, K packing and video hash association. It does
not constitute a physical projection or sensor-latency calibration.

## Device verification

The Docker-built sensors.5 APK was installed and recorded approximately 10 s on
2026-11-26. Final video `Synapath-1795691082984.mp4` has 595 RGB frames at 60 FPS,
9537 pose packets and 10 depth frames (about 1 Hz in that device state).
Its automatically exported 8931-byte calibration JSON preserves 8 RAW intrinsic,
24 RAW extrinsic and 16 RAW distortion doubles, plus the distinct output-mode
intrinsics and extrinsics. Output distortion is explicitly absent, not a stale
copy of the RAW coefficients. The copied MP4 matches the original SDK file
byte-for-byte, the JSON's video hash matches, and reconstructed RGB `icam` values
match the JSON's output K exactly. Evidence and verification results are local
under `artifacts/calibration-dual/`.

The verification script also reads the actual RGB track's PXR `icam` entries.
For the observed profile each intrinsic is represented as a signed exponent and
little-endian float64 mantissa. Reconstructing those values matches the exported
output-mode fx/fy/cx/cy exactly; it does not match the RAW-mode K. This tests that
the JSON's output K agrees with the SDK's own MP4 calibration for the video.
