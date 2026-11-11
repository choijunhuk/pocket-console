#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/build-switch-probe.sh" arm64-v8a pocket_switch2
