package org.ttt.autogenesis.server.config

import org.ttt.autogenesis.config.ConfigSource
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Configuration settings for the main server instance.
 * Contains port, host, gRPC settings, and AccelByte authentication details.
 *
 * Credentials and tenant URLs are loaded at runtime from `accelbyte.local.properties`
 * via `ConfigSource`. The file is synced from `autogenesis-secrets` by
 * `scripts/sync-resources.sh`. Port/host defaults remain inline because they are
 * not secrets.
 */
object ServerConfig
{
    /** Server port number (default 19080 — non-conflicting when operator's main Autogenesis dev stack is bound to 9080/9091/7070/9092/9095) */
    var port: Int = 19080

    /** Server host address */
    var host: String = "0.0.0.0"

    /** gRPC bridge port (default 19091 — see [port] for rationale) */
    var grpcPort: Int = 19091

    /** AccelByte client ID — read from ConfigSource */
    val iamKey: String get() = ConfigSource.property("accelbyte.local.properties", "AB_CLIENT_ID")

    /** AccelByte client secret — read from ConfigSource */
    val serverAccelByteSecret: String get() = ConfigSource.property("accelbyte.local.properties", "AB_CLIENT_SECRET")

    /** AccelByte tenant base URL — read from ConfigSource */
    val baseUrl: String get() = ConfigSource.property("accelbyte.local.properties", "AB_BASE_URL")

    /** Concatenated env-var string for child processes that read AB_* directly. */
    val envVars: String get() = buildString {
        append("AB_BASE_URL=").append(baseUrl).append(";")
        append("AB_CLIENT_ID=").append(iamKey).append(";")
        append("AB_CLIENT_SECRET=").append(serverAccelByteSecret)
    }

    /** Command line arguments passed to server */
    var args: List<String> = listOf()

    /**
     * Parses --rig or --rig-ai argument to get list of AI player names to rig.
     * Looks for --rig=value or --rig-ai=value in args (case-insensitive).
     * Value is comma-separated, trimmed, and lowercased.
     * @return list of AI player names, or empty list if argument absent/malformed
     */
    val rigAiPlayers: List<String> by lazy {
        parseRigAiPlayers()
    }

    private fun parseRigAiPlayers(): List<String>
    {
        val envValue = System.getenv("RIG")?.takeIf { it.isNotBlank() }
        if(envValue != null)
        {
            return envValue.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        }

        val rigArg = args.find { arg ->
            arg.startsWith("--rig-ai=", ignoreCase = true) ||
            arg.startsWith("--rig=", ignoreCase = true) ||
            arg.equals("--rig-ai", ignoreCase = true) ||
            arg.equals("--rig", ignoreCase = true)
        }

        if(rigArg == null)
        {
            return emptyList()
        }

        val equalsIndex = rigArg.indexOf('=')
        if(equalsIndex < 0)
        {
            Logger.warn(LogCategory.SYSTEM, "Found --rig or --rig-ai without =, ignoring: $rigArg")
            return emptyList()
        }

        val value = rigArg.substring(equalsIndex + 1).trim()
        if(value.isEmpty())
        {
            Logger.warn(LogCategory.SYSTEM, "Empty value after --rig=, ignoring")
            return emptyList()
        }

        return value.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    /**
     * Parses --map argument to get the name of the map to rig selection to.
     * Looks for --map=value in args (case-insensitive).
     * @return trimmed map name value after =, or null if argument absent
     */
    val rigMapName: String? by lazy {
        parseRigMapName()
    }

    private fun parseRigMapName(): String?
    {
        val envValue = System.getenv("MAP")?.takeIf { it.isNotBlank() }
        if(envValue != null)
        {
            return envValue.trim()
        }

        val mapArg = args.find { arg ->
            arg.startsWith("--map=", ignoreCase = true)
        }

        if(mapArg == null)
        {
            return null
        }

        val equalsIndex = mapArg.indexOf('=')
        if(equalsIndex < 0)
        {
            return null
        }

        val value = mapArg.substring(equalsIndex + 1).trim()
        if(value.isEmpty())
        {
            Logger.warn(LogCategory.SYSTEM, "Empty value after --map=, ignoring")
            return null
        }

        return value
    }
}
