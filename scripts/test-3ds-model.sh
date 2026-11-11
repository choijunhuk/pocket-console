#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .cache/3ds-model-tests
javac -encoding UTF-8 -d .cache/3ds-model-tests \
    app/src/main/java/dev/pocketconsole/ThreeDsRomCache.java \
    tests/ThreeDsRomCacheTest.java
java -cp .cache/3ds-model-tests dev.pocketconsole.ThreeDsRomCacheTest
