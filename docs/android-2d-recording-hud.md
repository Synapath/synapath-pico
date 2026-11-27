# Android 2D recording HUD

Synapath PICO 0.1.1-2d.2 retains the recording controls from
`feature/recording-passthrough-hud` without adopting its application encoder.

- SDK VIDEO_CAPTURING hides the main panel and shows only elapsed time and a stop circle.
- Hold the circle continuously for 2500 ms to save. Releasing early, moving outside,
  losing focus or detaching cancels the hold. Saving disables the circle.
- Saving or an error restores the panel. Successful finalization restores the SDK
  preview after the hidden SurfaceView is recreated.
- The window is translucent. A small RGBA GL surface mirrors the Android controls
  so the PICO compositor displays them. It has no connection to the camera or encoder.
- The window remains fixed in space. This version does not use OpenXR or head-follow APIs.
- RGB, ToF, head pose, two calibration exports and the original PXR timeline are
  still handled by the existing SDK direct-recording backend. No HUD is burned into video.

## Device verification — 2026-11-27

Docker build and update-in-place installation succeeded. Actual task snapshots
show the main panel hidden, the timer advancing, and the complete stop circle.
A one-second press and release retained recording; a subsequent three-second
press saved and restored both the main panel and the live camera preview.

The test file `Synapath-1795742996621.mp4` contains 1149 RGB frames at approximately
60 fps, 18 depth samples and 18972 pose samples. The headset was stationary;
this run is not a validation of 5 Hz depth. All tracks share the vendor timebase.
The final MP4 is byte-identical to the SDK source (SHA-256 checked in-app), with
zero timestamp shift and no trimmed samples. Decoded video and snapshots remain
local under ignored `artifacts/recording-hud*` files.

Task snapshots do not show the physical passthrough behind the transparent window;
headset visual confirmation is still needed for the real-environment appearance.
