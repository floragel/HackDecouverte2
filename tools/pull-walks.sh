#!/usr/bin/env bash
# Copies the walks recorded by the debug app (button "Enregistrer la marche") from the phone into
# recordings/ (git-ignored: Wi-Fi BSSIDs and your route stay on this computer), then replays them.
# Phone plugged in with USB debugging on.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p recordings
ADB=$(command -v adb || echo "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb")
"$ADB" pull /sdcard/Android/data/com.lync.app/files/recordings/. recordings/
ls -lh recordings/*.txt.gz
if [[ "${1:-}" != "--no-replay" ]]; then
  JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17} ./gradlew :app:testDebugUnitTest --tests '*RecordingReplayTest*' -q
  echo "Reports: app/build/replay/<walk>/report.txt, walk.svg, steps.csv"
fi
