# Transparent recording HUD

## Branch isolation

Feature branch: feature/recording-passthrough-hud in synapath-pico-app-pipeline.
It starts from feature/app-recording-preview at 2d06d6d. Neither that branch nor
main is changed. New package com.synapath.picopipeline.hud preserves the earlier
installed Pipeline package as well as its source branch. No automatic merge or
GitHub push is part of this task.

## Rendering

PipelineActivity uses a translucent window with no dim layer, and a recordable
RGBA GLSurfaceView. GL is a sibling of the hideable control panel; hiding controls
never hides/destroys the input or encoder surface. During transparent recording
the window surface clears to alpha zero, while the encoder still draws the
original camera image on its own EGL surface.

The first device attempt showed transparency but the user could not see native
Android HUD controls in the headset. The final implementation additionally
rasterizes the small HUD to a bitmap and draws that texture on the GL display
surface, at the same coordinates as the native input targets. This path was
confirmed visible in the headset by the user. The CPU rasterization applies only
to the small UI overlay, not to camera pixels. Hold-progress texture uploads are
coalesced to at most 20 Hz; the timer texture changes once per displayed second.
The HUD draw function is never called on the encoder EGL surface.

The overlay remains positioned within the PICO 2D app window. It is not an OpenXR
head-locked layer. System-owned window chrome and placement remain controlled by
PICO. Transparency is verified on this device/firmware, not assumed for every
other runtime.

## Stop interaction and state

HoldToStopView uses a monotonic elapsed clock and animation callbacks, requiring
4000 ms continuous press. Touch, held Enter, Space and D-pad center are handled.
A short click has no stop action. Released, canceled or out-of-circle pointers,
multi-touch, detach and window-focus changes cancel an incomplete hold. A
continuous hasWindowFocus check is intentionally avoided for PICO virtual windows.
On completion the control disables and the HUD shows Saving. Explicit renderer
STARTED/SAVING/SAVED/FAILED events restore UI only after encoder finalization.

Camera commands remain on their HandlerThread; all GL and encoding work remains
on the existing GL thread. Leaving the activity retains the original pipeline's
best-effort stop/cleanup behavior; background recording is not introduced.

## Hardware validation — 2026-11-26

Docker build and installation of 0.1.1-pipeline-hud.2 (versionCode 6) succeeded.
The user confirmed timer/circle visibility together with the real environment.

Device interaction checks used the current app's virtual display, refreshing
its ID after relaunches. ADB swipe-based tests were inconclusive and were replaced
by explicit DOWN/MOVE/UP events:

- A short explicit press/release kept the recording HUD active.
- Moving outside after a one-second press, waiting more than four seconds, then
  releasing kept recording active.
- Holding inside for 4.5 seconds saved the file and restored the panel.
- With hidden-panel mode unchecked, the panel and HUD were both present while
  recording; the same long hold saved 545 frames. Hidden-panel mode was restored
  afterward.

Two finalized transparent-HUD recordings were pulled and verified:

- AppPipeline-1795692447364.mp4: 2029 frames, 33.816667 s, 2048×768, file60 FPS.
  Source CSV rate59.997480 Hz; maximum interval16.668255 ms; no gaps over25 ms.
- AppPipeline-1795692754437.mp4: 3012 frames, 50.2 s, 2048×768, file60 FPS.
  Source CSV rate59.997473 Hz; maximum interval16.668230 ms; no gaps over25 ms.

Both CSV counts match encoded sample counts, and source timestamps are strictly
increasing. A decoded frame was visually inspected: the stereo camera image
contains no HUD. These checks establish the tested behavior, not universal
sustained performance or exposure timing. The MP4 timestamp smoothing limitation
from the original experiment remains.

Evidence is local under artifacts/hud-test/, excluded from Git, including videos,
CSV files, verification.json, the decoded frame and Android UI hierarchy captures.
The unused first attempt's test files are not used to claim successful stopping
or HUD visibility.
