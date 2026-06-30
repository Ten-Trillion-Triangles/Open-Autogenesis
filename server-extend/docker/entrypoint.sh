#!/usr/bin/env bash
# server-extend container entrypoint.
#
# PID 1 == JVM so SIGTERM from the Extend platform reaches the Ktor/Netty
# server and triggers a clean shutdown. We do the minimum: print the
# invocation and exec the beryx launcher.
set -euo pipefail

echo "[server-extend] entrypoint: launching /opt/server-extend/bin/autogenesis-server-extend $*"
exec /opt/server-extend/bin/autogenesis-server-extend "$@"
