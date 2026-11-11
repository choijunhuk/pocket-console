#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

if [ -n "${DEVKITPRO:-}" ] && [ -n "${DEVKITARM:-}" ]; then
    if command -v dkp-pacman >/dev/null 2>&1; then
        dkp-pacman -Q libctru
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Q libctru
    else
        echo 'libctru package version unavailable in this toolchain'
    fi
    make -C tests/three_ds
else
    image=devkitpro/devkitarm@sha256:116afba8df8453961de2936ffab20dd441edf4d682856c1ec8b0e53d7ed0bbf5
    docker pull "$image"
    docker image inspect --format 'devkitPro image {{index .RepoDigests 0}}' "$image"
    docker run --rm -v "$PWD:/work" -w /work "$image" \
        sh -c 'export DEVKITPRO=/opt/devkitpro DEVKITARM=/opt/devkitpro/devkitARM; scripts/build-3ds-homebrew.sh'
fi

test -s tests/three_ds/smoke.3dsx
printf '3DS_HOMEBREW_BUILD_PASS %s\n' "$PWD/tests/three_ds/smoke.3dsx"
