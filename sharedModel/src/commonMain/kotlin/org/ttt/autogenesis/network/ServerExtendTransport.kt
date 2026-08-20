package org.ttt.autogenesis.network

/**
 * Describes which transport the KVision client should use for the server-extend connection.
 */
enum class ServerExtendTransport
{
    /**
     * Uses the existing REST/SSE bridge.
     */
    REST_SSE,

    /**
     * Uses the native bidirectional gRPC bridge.
     */
    GRPC_BIDI,

    /**
     * Uses the browser-safe grpc-web bridge.
     */
    GRPC_WEB
    ;

    companion object
    {
        /**
         * Parses a transport value from a string and falls back to REST/SSE when the value is blank or unknown.
         *
         * @param value Raw configuration value.
         * @return The matching transport or [REST_SSE] when no match is found.
         */
        fun fromValue(value: String?): ServerExtendTransport
        {
            val normalized = value
                ?.trim()
                ?.replace('-', '_')

            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: REST_SSE
        }
    }
}