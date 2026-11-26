#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
python3 scripts/verify-sdk.py
image="${PICO_DEV_IMAGE:-synapath-pico-dev:local}"
if ! docker image inspect "$image" >/dev/null 2>&1; then
  docker compose build dev
fi
docker compose run --rm dev bash -c 'cd xr && ./gradlew :app:assembleDebug --no-daemon --console=plain'
mkdir -p artifacts
cp xr/app/build/outputs/apk/debug/app-debug.apk artifacts/SynapathPico-CaptureSDK-0.1.1-debug.apk
printf '%s\n' "$repo_root/artifacts/SynapathPico-CaptureSDK-0.1.1-debug.apk"
