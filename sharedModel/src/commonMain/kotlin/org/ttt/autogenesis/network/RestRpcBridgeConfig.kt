package org.ttt.autogenesis.network

/**
 * Configuration helper for RestRpcBridge connections.
 * Provides default configurations for different environments.
 */
object RestRpcBridgeConfig
{
    /**
     * Default localhost URL for development.
     */
    const val DEFAULT_LOCALHOST_URL = "http://localhost:7070"

    /**
     * Default production URL placeholder.
     */
    const val DEFAULT_PRODUCTION_URL = "https://api.autogenesis.com"

    /**
     * Player ID prefix for REST connections.
     */
    const val PLAYER_ID_PREFIX = "rest-client"

    /**
     * Creates a development configuration.
     */
    fun development(port : Int = 7070) : String = "http://localhost:$port"

    /**
     * Creates a production configuration.
     */
    fun production(host : String) : String = "https://$host"
}