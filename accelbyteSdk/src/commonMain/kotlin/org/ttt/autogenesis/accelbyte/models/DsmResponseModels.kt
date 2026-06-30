package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Single operational message reported by DSM controller with level, content, and origin metadata.
 *
 * @param type Severity/type of the message.
 * @param content Message body.
 * @param timestamp ISO timestamp when the event was emitted.
 * @param source Originating subsystem for the message.
 */
data class DsmMessage(
    val type : String,
    val content : String,
    val timestamp : String,
    val source : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmMessage = DsmMessage(
            type = json.requireString("type"),
            content = json.requireString("content"),
            timestamp = json.requireString("timestamp"),
            source = json.optString("source")
        )
    }
}

/**
 * Wrapper for a paginated list of DSM messages returned by management APIs.
 *
 * @param messages Messages retrieved in the current page.
 */
data class DsmMessageListResponse(
    val messages : List<DsmMessage>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmMessageListResponse = DsmMessageListResponse(
            messages = json.optJsonList("messages").map(DsmMessage::fromJson)
        )
    }
}

/**
 * Details for a DSM-managed server instance, including status, region, and networking metadata.
 *
 * @param podName Server pod identifier.
 * @param status Current server status.
 * @param region AWS region.
 * @param ip IPv4 address (if reported).
 * @param port Listening port (if reported).
 * @param createdAt Server creation timestamp.
 */
data class DsmServerInfo(
    val podName : String,
    val status : String,
    val region : String,
    val ip : String?,
    val port : Int?,
    val createdAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmServerInfo = DsmServerInfo(
            podName = json.requireString("podName"),
            status = json.requireString("status"),
            region = json.requireString("region"),
            ip = json.optString("ip"),
            port = json.optInt("port"),
            createdAt = json.optString("createdAt")
        )
    }
}

/**
 * Result of listing DSM servers with paging metadata for navigation.
 *
 * @param servers Listed server rows.
 * @param paging Pagination metadata for the list.
 */
data class DsmServerListResponse(
    val servers : List<DsmServerInfo>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmServerListResponse = DsmServerListResponse(
            servers = json.optJsonList("servers").map(DsmServerInfo::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Lifecycle acknowledgement payload for registration, shutdown, or local server operations.
 *
 * @param podName Pod identifier.
 * @param status Current lifecycle status.
 * @param region Hosting region.
 * @param sessionId Optional session ID assigned.
 * @param createdAt Timestamp of action completion.
 * @param reason Optional rationale.
 * @param timestamp Response timestamp.
 */
data class DsmServerLifecycleResponse(
    val podName : String,
    val status : String,
    val region : String?,
    val sessionId : String?,
    val createdAt : String?,
    val reason : String?,
    val timestamp : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmServerLifecycleResponse = DsmServerLifecycleResponse(
            podName = json.requireString("podName"),
            status = json.requireString("status"),
            region = json.optString("region"),
            sessionId = json.optString("sessionId"),
            createdAt = json.optString("createdAt"),
            reason = json.optString("reason"),
            timestamp = json.optString("timestamp")
        )
    }
}

/**
 * Heartbeat response describing acknowledgment and next expected update.
 *
 * @param podName Pod identifier.
 * @param acknowledged Heartbeat acknowledgement flag.
 * @param nextHeartbeat ISO timestamp for the next expected beat.
 * @param status Optional health status note.
 */
data class DsmHeartbeatResponse(
    val podName : String,
    val acknowledged : Boolean,
    val nextHeartbeat : String?,
    val status : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmHeartbeatResponse = DsmHeartbeatResponse(
            podName = json.requireString("podName"),
            acknowledged = json.requireBoolean("acknowledged"),
            nextHeartbeat = json.optString("nextHeartbeat"),
            status = json.optString("status")
        )
    }
}

/**
 * Regional summary counters describing server allocation states.
 *
 * @param total Total servers in the region.
 * @param ready Ready servers.
 * @param busy Busy servers.
 * @param creating Servers in creation.
 */
data class DsmRegionCounts(
    val total : Int?,
    val ready : Int?,
    val busy : Int?,
    val creating : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmRegionCounts = DsmRegionCounts(
            total = json.optInt("total"),
            ready = json.optInt("ready"),
            busy = json.optInt("busy"),
            creating = json.optInt("creating")
        )
    }
}

/**
 * Region-specific statistics container combining name with counts.
 *
 * @param region Region identifier.
 * @param counts Counts breakdown for the region.
 */
data class DsmRegionStatistics(
    val region : String,
    val counts : DsmRegionCounts
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmRegionStatistics = DsmRegionStatistics(
            region = json.requireString("region"),
            counts = DsmRegionCounts.fromJson(json.requireJson("counts"))
        )
    }
}

/**
 * Aggregate server counts broken down by region and overall total.
 *
 * @param regions Region statistics collection.
 * @param totalServers Optional total count.
 */
data class DsmServerCountResponse(
    val regions : List<DsmRegionStatistics>,
    val totalServers : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmServerCountResponse = DsmServerCountResponse(
            regions = json.optJsonList("regions").map(DsmRegionStatistics::fromJson),
            totalServers = json.optInt("totalServers")
        )
    }
}

/**
 * Player entry inside a DSM-managed session, recording user ID and join timestamp.
 *
 * @param userId Player identifier.
 * @param joinedAt Optional join timestamp.
 */
data class DsmSessionPlayer(
    val userId : String,
    val joinedAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmSessionPlayer = DsmSessionPlayer(
            userId = json.requireString("userId"),
            joinedAt = json.optString("joinedAt")
        )
    }
}

/**
 * Detailed view of a server session with player roster and metadata.
 *
 * @param sessionId Session identifier.
 * @param podName Host pod.
 * @param status Session status.
 * @param players Player roster.
 * @param createdAt Optional creation timestamp.
 */
data class DsmServerSessionResponse(
    val sessionId : String,
    val podName : String,
    val status : String,
    val players : List<DsmSessionPlayer>,
    val createdAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmServerSessionResponse = DsmServerSessionResponse(
            sessionId = json.requireString("sessionId"),
            podName = json.requireString("podName"),
            status = json.requireString("status"),
            players = json.optJsonList("players").map(DsmSessionPlayer::fromJson),
            createdAt = json.optString("createdAt")
        )
    }
}

/**
 * Session timeout contract describing pod and idle limits.
 *
 * @param podName Pod identifier.
 * @param sessionTimeout Maximum session duration.
 * @param idleTimeout Idle timeout.
 * @param maxDuration Maximum allowed duration.
 */
data class DsmSessionTimeoutResponse(
    val podName : String,
    val sessionTimeout : Int?,
    val idleTimeout : Int?,
    val maxDuration : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmSessionTimeoutResponse = DsmSessionTimeoutResponse(
            podName = json.requireString("podName"),
            sessionTimeout = json.optInt("sessionTimeout"),
            idleTimeout = json.optInt("idleTimeout"),
            maxDuration = json.optInt("maxDuration")
        )
    }
}

/**
 * Summary row describing a deployment configuration and its regional footprint.
 *
 * @param name Deployment name.
 * @param image Container image.
 * @param replicas Replica count.
 * @param regions List of regions.
 * @param status Current deployment status.
 * @param createdAt Creation timestamp.
 */
data class DsmDeploymentSummary(
    val name : String,
    val image : String,
    val replicas : Int,
    val regions : List<String>,
    val status : String?,
    val createdAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmDeploymentSummary = DsmDeploymentSummary(
            name = json.requireString("name"),
            image = json.requireString("image"),
            replicas = json.requireInt("replicas"),
            regions = json.optStringList("regions"),
            status = json.optString("status"),
            createdAt = json.optString("createdAt")
        )
    }
}

/**
 * List of deployment summaries including pagination info.
 *
 * @param deployments Deployment entries.
 */
data class DsmDeploymentListResponse(
    val deployments : List<DsmDeploymentSummary>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmDeploymentListResponse = DsmDeploymentListResponse(
            deployments = json.optJsonList("deployments").map(DsmDeploymentSummary::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Detailed deployment configuration with status and replication metadata.
 *
 * @param name Deployment name.
 * @param image Container image.
 * @param replicas Replica count.
 * @param regions Regions served.
 * @param status Current status.
 * @param createdAt Creation timestamp.
 * @param metadata Optional metadata.
 */
data class DsmDeploymentDetailResponse(
    val name : String,
    val image : String,
    val replicas : Int,
    val regions : List<String>,
    val resources : Json?,
    val createdAt : String?,
    val updatedAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmDeploymentDetailResponse = DsmDeploymentDetailResponse(
            name = json.requireString("name"),
            image = json.requireString("image"),
            replicas = json.requireInt("replicas"),
            regions = json.optStringList("regions"),
            resources = json.optJson("resources"),
            createdAt = json.optString("createdAt"),
            updatedAt = json.optString("updatedAt")
        )
    }
}

/**
 * Acknowledgement payload returned after a deployment deletion request.
 *
 * @param message Optional confirmation message.
 */
data class DsmDeploymentDeletionResponse(
    val deleted : Boolean,
    val deployment : String,
    val timestamp : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmDeploymentDeletionResponse = DsmDeploymentDeletionResponse(
            deleted = json.requireBoolean("deleted"),
            deployment = json.requireString("deployment"),
            timestamp = json.optString("timestamp")
        )
    }
}

/**
 * Result envelope after creating a deployment, echoing metadata.
 *
 * @param creationTime Creation timestamp.
 * @param deploymentName Name of the deployment.
 */
data class DsmDeploymentCreationResponse(
    val name : String,
    val image : String,
    val replicas : Int,
    val regions : List<String>,
    val status : String?,
    val createdAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmDeploymentCreationResponse = DsmDeploymentCreationResponse(
            name = json.requireString("name"),
            image = json.requireString("image"),
            replicas = json.requireInt("replicas"),
            regions = json.optStringList("regions"),
            status = json.optString("status"),
            createdAt = json.optString("createdAt")
        )
    }
}

/**
 * Response produced when a DSM session is created.
 *
 * @param sessionId Created session identifier.
 * @param status Session status.
 * @param podName Hosting pod.
 */
data class DsmSessionCreationResponse(
    val sessionId : String,
    val status : String,
    val gameMode : String?,
    val region : String?,
    val createdAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmSessionCreationResponse = DsmSessionCreationResponse(
            sessionId = json.requireString("sessionId"),
            status = json.requireString("status"),
            gameMode = json.optString("gameMode"),
            region = json.optString("region"),
            createdAt = json.optString("createdAt")
        )
    }
}

/**
 * Outcome of claiming a session for a server assignment.
 *
 * @param sessionId Claimed session ID.
 * @param assigned Whether claim succeeded.
 * @param podName Assigned pod.
 * @param claimedAt Claim timestamp.
 */
data class DsmSessionClaimResponse(
    val sessionId : String,
    val claimed : Boolean,
    val podName : String?,
    val claimedAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmSessionClaimResponse = DsmSessionClaimResponse(
            sessionId = json.requireString("sessionId"),
            claimed = json.requireBoolean("claimed"),
            podName = json.optString("podName"),
            claimedAt = json.optString("claimedAt")
        )
    }
}

/**
 * Detailed session state including players and status updates.
 *
 * @param sessionId Session identifier.
 * @param status Current status.
 * @param matchPool Match pool name.
 * @param players Player list.
 * @param createdAt Creation timestamp.
 */
data class DsmSessionDetailsResponse(
    val sessionId : String,
    val status : String,
    val podName : String?,
    val players : List<DsmSessionPlayer>,
    val gameMode : String?,
    val createdAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmSessionDetailsResponse = DsmSessionDetailsResponse(
            sessionId = json.requireString("sessionId"),
            status = json.requireString("status"),
            podName = json.optString("podName"),
            players = json.optJsonList("players").map(DsmSessionPlayer::fromJson),
            gameMode = json.optString("gameMode"),
            createdAt = json.optString("createdAt")
        )
    }
}

/**
 * Confirmation of a session cancellation operation.
 *
 * @param sessionId Identifier of the cancelled session.
 * @param cancelled Whether the session was cancelled successfully.
 * @param reason Cancellation reason.
 * @param cancelledAt Timestamp of cancellation.
 */
data class DsmSessionCancellationResponse(
    val sessionId : String,
    val cancelled : Boolean,
    val reason : String?,
    val cancelledAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DsmSessionCancellationResponse = DsmSessionCancellationResponse(
            sessionId = json.requireString("sessionId"),
            cancelled = json.requireBoolean("cancelled"),
            reason = json.optString("reason"),
            cancelledAt = json.optString("cancelledAt")
        )
    }
}
