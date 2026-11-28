#!/bin/sh
# Host test of Android Auto car compass (AaCompass + NaviBridge): coordinate conversion,
# SensorSource.init advertisement, periodic parked refresh, immediate change wakeup,
# REQUEST_COMPASS startup handshake, and session teardown.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" \
    $(find stubs test -name '*.java') \
    ../../src/hmgapi/mods/wireless/AaCompass.java \
    ../../src/hmgapi/mods/wireless/NaviBridge.java
java -cp "$CP" hmgapi.mods.wireless.AaCompassTest
