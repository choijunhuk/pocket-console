#!/usr/bin/env bash
# Merge gate: deterministic local checks plus a green Android CI run for this exact commit.
# The Android workflow builds and lints the APK with the pinned DS/3DS/Switch cores and runs
# the DS/UI emulator checks; local hosts usually lack those pinned artifacts.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export PYTHONDONTWRITEBYTECODE=1

python3 -m unittest discover -s tests -p 'test_*.py'
python3 -m unittest discover -s tests/android-switch-sdk -p 'test_*.py'
./scripts/test-model.sh
./scripts/test-3ds-model.sh
./scripts/test-switch-java.sh
git diff --check HEAD

sha="$(git rev-parse HEAD)"
state="$(gh run list --workflow android.yml --commit "$sha" --limit 1 --json conclusion -q '.[0].conclusion' 2>/dev/null || true)"
if [ "$state" != success ]; then
    echo "GATE_FAIL: android.yml is not green for $sha (got '${state:-no run}')"
    exit 1
fi
echo "GATE_PASS $sha"
