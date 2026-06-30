package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Information about a QOS-managed server instance.
 *
 * @property alias The alias/name of the server.
 * @property ip The IP address of the server.
 * @property port The port number of the server.
 * @property region The region where the server is located.
 * @property status The current status of the server.
 * @property lastUpdate Timestamp of the last update to this server.
 */
data class QosmServerResponse(
    val alias : String,
    val ip : String,
    val port : Int,
    val region : String,
    val status : String,
    val lastUpdate : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : QosmServerResponse = QosmServerResponse(
            alias = json.requireString("alias"),
            ip = json.requireString("ip"),
            port = json.requireInt("port"),
            region = json.requireString("region"),
            status = json.requireString("status"),
            lastUpdate = json.requireString("last_update")
        )
    }
}

/**
 * A list of QOS-managed servers returned by server queries.
 *
 * @property servers The list of server information entries.
 */
data class QosmServerListResponse(
    val servers : List<QosmServerResponse>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : QosmServerListResponse = QosmServerListResponse(
            servers = json.optJsonList("servers").map(QosmServerResponse::fromJson)
        )
    }
}
