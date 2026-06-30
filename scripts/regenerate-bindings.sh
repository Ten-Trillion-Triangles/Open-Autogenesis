#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DUKAT_CLI="$ROOT/dukat/dukat/node-package/build/distrib/bin/dukat-cli.js"
SDK_DTS="$ROOT/accelbyte/accelbyte-typescript-sdk/packages/sdk/dist/index.d.ts"
OUT_DIR="/tmp/accelbyte-sdk-bindings"

if [[ ! -f "$DUKAT_CLI" ]]; then
  echo "Dukat CLI not built yet; run './gradlew :node-package:build' inside dukat/dukat first."
  exit 1
fi

mkdir -p "$OUT_DIR"

node "$DUKAT_CLI" \
  --ts-config "$ROOT/accelbyte/accelbyte-typescript-sdk/packages/sdk/tsconfig.dukat.json" \
  -m @accelbyte/sdk \
  -d "$OUT_DIR" \
  "$SDK_DTS"

echo "Dukat output refreshed under $OUT_DIR. Review and apply manual cleanup (tsstdlib helpers, naming overrides) as needed."
