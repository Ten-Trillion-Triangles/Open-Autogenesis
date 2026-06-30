# server-extend Dockerfile.
#
# Consumes the beryx-runtime plugin's `:server-extend:runtime` output (a
# self-contained JRE + launcher at `build/server-extend-runtime/server-extend-linux-x64/`)
# and wraps the launcher in a tiny entrypoint so PID 1 == JVM and signal
# handling works under Docker and Extend.
#
# The platform invokes this image via an Extend Custom Service deployment
# whose `commandLine` points at `bin/entrypoint.sh` (which execs the beryx
# launcher). REST 7070 and gRPC/grpc-web 9092 are exposed; Extend only needs
# the ports the deployment profile references in `portConfigurations[]`.

FROM eclipse-temurin:24-jre

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/server-extend

# Copy the beryx-runtime image (JRE + launcher + lib/ + conf/ + legal/).
COPY build/server-extend-runtime/server-extend-linux-x64/ /opt/server-extend/

# Drop the entrypoint wrapper that execs the beryx launcher as PID 1.
COPY docker/entrypoint.sh /opt/server-extend/bin/entrypoint.sh
RUN chmod +x /opt/server-extend/bin/entrypoint.sh /opt/server-extend/bin/autogenesis-server-extend

EXPOSE 7070/tcp 9092/tcp

# Read by `ExtendConfig.resolveGrpcPort`; overridable at deploy time.
ENV SERVER_EXTEND_GRPC_PORT=9092

# `GET /player` already returns 200 from `ServerExtend.kt`'s routing block,
# so it doubles as the Extend HTTP health probe target.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS http://127.0.0.1:7070/player || exit 1

ENTRYPOINT ["/opt/server-extend/bin/entrypoint.sh"]
