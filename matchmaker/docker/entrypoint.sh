#!/usr/bin/env bash
# Matchmaker container entrypoint.
#
# The point of this wrapper is to make PID 1 == the JVM (so SIGTERM from the
# Extend platform reaches the gRPC server and triggers a clean shutdown)
# rather than a shell. We do the minimum: print the invocation and exec.
set -euo pipefail

echo "[matchmaker] entrypoint: launching /opt/matchmaker/bin/matchmaker $*"
exec /opt/matchmaker/bin/matchmaker "$@"
