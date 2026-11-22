#!/bin/sh
# Host test of AaNavState, the decoder for Android Auto's full navigation messages
# (0x8006/0x8007 as the library's NAVDUMP). Optional arguments: logcat files with
# NAVDUMP lines (e.g. private drive captures); they are parsed and only counts printed.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" $(find ../aaturns/stubs test -name '*.java') \
    ../../src/hmgapi/mods/wireless/AaTurns.java ../../src/hmgapi/mods/wireless/AaNavState.java
java -cp "$CP" hmgapi.mods.wireless.AaNavStateTest "$@"
