#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
python3 scripts/verify-sdk.py
image="${PICO_DEV_IMAGE:-synapath-pico-dev:local}"
if ! docker image inspect "$image" >/dev/null 2>&1; then
  docker compose build dev
fi
docker volume create synapath-pico_gradle-cache >/dev/null
docker volume create synapath-pico_debug-signing >/dev/null
docker compose run --rm dev bash -c './scripts/build-openxr-native.sh && cd xr && ./gradlew :app:assembleDebug --no-daemon --console=plain'
mkdir -p artifacts
cp xr/app/build/outputs/apk/debug/app-debug.apk artifacts/SynapathPico-Sensors-OpenXR-debug.apk
printf '%s\n' "$repo_root/artifacts/SynapathPico-Sensors-OpenXR-debug.apk"
