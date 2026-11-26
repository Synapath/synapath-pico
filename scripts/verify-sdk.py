#!/usr/bin/env python3
"""Require the exact SDK used by the validated capture setup."""
import hashlib
from pathlib import Path

root = Path(__file__).resolve().parents[1]
aar = root / 'third_party/pico/capturelib-0.1.1.aar'
expected = '9e5a15f39709b94cb781068167d406da229c4fb37e821606efe802bf5c6cb267'
if not aar.is_file():
    raise SystemExit(f'Copy the supplied capturelib-0.1.1.aar to {aar}')
actual = hashlib.sha256(aar.read_bytes()).hexdigest()
if actual != expected:
    raise SystemExit(f'SDK mismatch: expected {expected}, got {actual}')
print(f'PICO SDK verified: {actual}')
