#!/bin/sh
# Host regression: actual Wireless.appLoaded, with recording HMG-API stand-ins.
# No manufacturer files needed. An optional argument checks the private CEEV AA library.
set -e
cd "$(dirname "$0")"
if [ -z "${ANDROID_JAR:-}" ]; then
    ANDROID_JAR=$(find "${ANDROID_HOME:?set ANDROID_HOME or ANDROID_JAR}/platforms" -name android.jar | sort | tail -1)
fi
[ -f "$ANDROID_JAR" ] || { echo "no Android platform android.jar"; exit 1; }
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR" -d "$CP" \
    $(find stubs test -name '*.java') \
    ../../src/hmgapi/mods/wireless/AaTbtSupport.java \
    ../../src/hmgapi/mods/wireless/NaviBridge.java \
    ../../src/hmgapi/mods/wireless/Wireless.java
java -cp "$CP:$ANDROID_JAR" hmgapi.mods.wireless.AaSupportTest "$@"
