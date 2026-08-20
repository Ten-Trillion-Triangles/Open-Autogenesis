package org.ttt.autogenesis.network

import kotlinx.serialization.Serializable

/**
 * Status record for a single subsystem within a [SystemProbeResponse].
 *
 * @property subsystem Machine-readable subsystem name, e.g. "AccelByteConfig",
 *   "DsHubClient", "GrpcServer". Used as a stable key across probe calls.
 * @property status One of:
 *   - `configured` — subsystem is initialised and healthy
 *   - `error` — subsystem failed to initialise or threw during use
 *   - `skipped` — subsystem was intentionally bypassed (e.g. no AB_DS_ID set)
 *   - `not_initialized` — subsystem has not been bootstrapped yet
 * @property detail Human-readable diagnostic string. May include a host:port
 *   for listeners, the mode (LOCAL/CLOUD) for VFS, or an error message.
 *   Never contains credentials (client_secret, password tokens, private keys).
 */
@Serializable
data class SubsystemStatus(
    val subsystem: String,
    val status: String,
    val detail: String? = null
)

/**
 * Diagnostic payload returned by [server.system.probe][org.ttt.autogenesis.server.GameRpcHandlers]
 * and [serverextend.system.probe][org.ttt.autogenesis.serverextend.SystemProbeHandlers].
 *
 * @property module "server" or "serverextend" — identifies which module responded.
 * @property timestamp [System.currentTimeMillis] at the moment the probe handler ran.
 * @property subsystems Ordered list of subsystem status records. The order is
 *   stable for a given module and matches the order in which subsystems are
 *   checked at probe time.
 */
@Serializable
data class SystemProbeResponse(
    val module: String,
    val timestamp: Long,
    val subsystems: List<SubsystemStatus>
)