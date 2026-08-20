#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROTO_ROOT="$REPO_ROOT/grpcBridgeProto"
PROTO_DIR="$PROTO_ROOT/src/main/proto"
OUTPUT_DIR="$REPO_ROOT/kvisionApp/src/jsMain/resources/grpc"
PROTOC_BIN="${PROTOC_BIN:-protoc}"
CONNECT_PLUGIN="$PROTO_ROOT/node_modules/.bin/protoc-gen-connect-es"
ES_PLUGIN="$PROTO_ROOT/node_modules/.bin/protoc-gen-es"

if [ ! -x "$CONNECT_PLUGIN" ]; then
  echo "error: protoc-gen-connect-es plugin not found at $CONNECT_PLUGIN"
  echo "→ run 'npm install' inside $PROTO_ROOT" >&2
  exit 1
fi

if [ ! -x "$ES_PLUGIN" ]; then
  echo "error: protoc-gen-es plugin not found at $ES_PLUGIN"
  echo "→ run 'npm install' inside $PROTO_ROOT" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

"$PROTOC_BIN" \
  --plugin=protoc-gen-es="$ES_PLUGIN" \
  --plugin=protoc-gen-connect-es="$CONNECT_PLUGIN" \
  --es_out=target=js:"$OUTPUT_DIR" \
  --connect-es_out=target=js:"$OUTPUT_DIR" \
  -I "$PROTO_DIR" \
  "$PROTO_DIR/rpc_bridge.proto"