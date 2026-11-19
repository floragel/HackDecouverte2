#!/usr/bin/env bash
# RoNIN (research only, non-commercial weights) on every walk with a traced route, compared with the PDR.
# Setup (once): ~/Documents/Hackdecouverte-marches/ronin-work/{.venv,code} — see docs/research/ronin.md.
#   tools/research/ronin.sh <checkpoint.pt> [resnet18|resnet50]
set -euo pipefail
cd "$(dirname "$0")/../.."
W="$HOME/Documents/Hackdecouverte-marches/ronin-work"
OUT="$W/out"; mkdir -p "$OUT"
MODEL="${1:?checkpoint .pt}"; ARCH="${2:-resnet18}"
for truth in recordings/*.truth.json; do
  walk="${truth%.truth.json}"
  RONIN_CODE="$W/code/source" "$W/.venv/bin/python" tools/research/ronin_walk.py "$walk.txt.gz" "$OUT/$(basename "$walk").ronin.csv" --model "$MODEL" --arch "$ARCH"
done
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17} ./gradlew :app:testDebugUnitTest --tests '*ExternalTrajectoryTest*' --rerun -q -Dtraj="$OUT"
cat app/build/replay/external-trajectories.txt
