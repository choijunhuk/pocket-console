#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

if [ -n "${DEVKITPRO:-}" ] && [ -n "${DEVKITARM:-}" ]; then
    make -C tests/three_ds
else
    image=devkitpro/devkitarm:20260610
    docker pull "$image"
    docker image inspect --format 'devkitPro image {{index .RepoDigests 0}}' "$image"
    docker run --rm -v "$PWD:/work" -w /work "$image" \
        sh -c 'export DEVKITPRO=/opt/devkitpro DEVKITARM=/opt/devkitpro/devkitARM; scripts/build-3ds-homebrew.sh'
fi

test -s tests/three_ds/smoke.3dsx
printf '3DS_HOMEBREW_BUILD_PASS %s\n' "$PWD/tests/three_ds/smoke.3dsx"
