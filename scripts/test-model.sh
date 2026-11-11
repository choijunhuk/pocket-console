#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .cache/model-tests
javac -encoding UTF-8 -d .cache/model-tests \
    app/src/main/java/dev/pocketconsole/ConsoleType.java \
    app/src/main/java/dev/pocketconsole/DeviceCapabilities.java \
    app/src/main/java/dev/pocketconsole/DsRomCache.java \
    tests/ConsoleTypeTest.java \
    tests/DeviceCapabilitiesTest.java \
    tests/DsRomCacheTest.java
java -cp .cache/model-tests dev.pocketconsole.ConsoleTypeTest
java -cp .cache/model-tests dev.pocketconsole.DeviceCapabilitiesTest
java -cp .cache/model-tests dev.pocketconsole.DsRomCacheTest
