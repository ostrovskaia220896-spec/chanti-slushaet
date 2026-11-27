#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/libs/sherpa-onnx-2.0.3.aar"
mkdir -p "$(dirname "$DEST")"
curl --fail --location --retry 3 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-2.0.3.aar \
  --output "$DEST"
echo "4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50  $DEST" | sha256sum --check
echo "Sherpa-ONNX 2.0.3 AAR installed: $DEST"
