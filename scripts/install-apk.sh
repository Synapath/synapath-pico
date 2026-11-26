#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
adb_cmd=(adb)
if [[ -n "${PICO_SERIAL:-}" ]]; then adb_cmd+=(-s "$PICO_SERIAL"); fi
"${adb_cmd[@]}" install --no-incremental -r "$repo_root/artifacts/SynapathPico-OpenXR-debug.apk"
