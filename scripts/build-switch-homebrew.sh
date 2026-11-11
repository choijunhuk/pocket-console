#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image=devkitpro/devkita64@sha256:82575ea78651b530b2e232bb3799cfd1fe331514e053d5f724bb4b28191fb79d

if [[ -n "${DEVKITPRO:-}" && -n "${DEVKITA64:-}" ]]; then
  make -C "$repo_root/tests/switch2"
else
  docker pull "$image"
  docker image inspect --format 'devkitA64 image {{index .RepoDigests 0}}' "$image"
  docker run --rm -v "$repo_root:/work" -w /work "$image" \
    sh -c 'export DEVKITPRO=/opt/devkitpro DEVKITA64=/opt/devkitpro/devkitA64; scripts/build-switch-homebrew.sh'
fi

python3 "$repo_root/scripts/verify-switch-homebrew.py" "$repo_root/tests/switch2/smoke.nro"
