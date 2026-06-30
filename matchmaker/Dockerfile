# Matchmaker Dockerfile.
#
# Consumes the `application` plugin's `:matchmaker:installDist` output and wraps
# the gRPC server in a tiny entrypoint so PID 1 == JVM and signal handling works
# under Docker and Extend. The platform invokes this image via an Extend
# Custom Service deployment whose `commandLine` points at `bin/entrypoint.sh`.
#
# gRPC health: `grpc.health.v1.Health` is registered on the same port by
# `MatchmakerMain.kt`, so the `HEALTHCHECK` here and the `grpc_health_probe`
# in `k8s/matchmaker.yaml` both succeed.

FROM eclipse-temurin:24-jre

# `grpc_health_probe` is the same binary used by the k8s probes; baked in
# here so `docker run` and `docker compose` can sanity-check without a shell.
# Pinned to a recent release; bump alongside upgrades.
ARG GRPC_HEALTH_PROBE_VERSION=v0.4.36
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates; \
    curl -fsSL -o /bin/grpc_health_probe \
        "https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/${GRPC_HEALTH_PROBE_VERSION}/grpc_health_probe-linux-amd64"; \
    chmod +x /bin/grpc_health_probe; \
    apt-get purge -y --auto-remove curl; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/matchmaker

# Copy the application distribution produced by `:matchmaker:installDist`.
COPY build/install/matchmaker/ /opt/matchmaker/

# Drop the entrypoint wrapper that execs the gRPC server binary.
COPY docker/entrypoint.sh /opt/matchmaker/bin/entrypoint.sh
RUN chmod +x /opt/matchmaker/bin/entrypoint.sh

EXPOSE 9095/tcp

# Read by `MatchmakerMain.kt`; overridable at deploy time.
ENV MATCHMAKER_GRPC_PORT=9095

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
    CMD ["/bin/grpc_health_probe", "-addr=:9095", "-connect-timeout=2s"]

ENTRYPOINT ["/opt/matchmaker/bin/entrypoint.sh"]
