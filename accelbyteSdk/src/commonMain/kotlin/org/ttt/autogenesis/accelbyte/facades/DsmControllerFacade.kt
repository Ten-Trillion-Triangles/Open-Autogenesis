package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.DsmDeploymentCreationResponse
import org.ttt.autogenesis.accelbyte.models.DsmDeploymentDeletionResponse
import org.ttt.autogenesis.accelbyte.models.DsmDeploymentDetailResponse
import org.ttt.autogenesis.accelbyte.models.DsmDeploymentListResponse
import org.ttt.autogenesis.accelbyte.models.DsmHeartbeatResponse
import org.ttt.autogenesis.accelbyte.models.DsmMessageListResponse
import org.ttt.autogenesis.accelbyte.models.DsmRegionQueryParams
import org.ttt.autogenesis.accelbyte.models.DsmServerCountResponse
import org.ttt.autogenesis.accelbyte.models.DsmServerListResponse
import org.ttt.autogenesis.accelbyte.models.DsmServerLifecycleResponse
import org.ttt.autogenesis.accelbyte.models.DsmServerSessionResponse
import org.ttt.autogenesis.accelbyte.models.DsmSessionCancellationResponse
import org.ttt.autogenesis.accelbyte.models.DsmSessionClaimResponse
import org.ttt.autogenesis.accelbyte.models.DsmSessionCreationResponse
import org.ttt.autogenesis.accelbyte.models.DsmSessionDetailsResponse
import org.ttt.autogenesis.accelbyte.models.DsmSessionTimeoutResponse
import org.ttt.autogenesis.accelbyte.models.DsmServerQueryParams
import org.ttt.autogenesis.accelbyte.models.DsmDeploymentQueryParams
import org.ttt.autogenesis.accelbyte.models.ServerLifecycleRequest
import org.ttt.autogenesis.accelbyte.modules.DsmControllerModulePackage
import org.ttt.autogenesis.accelbyte.modules.DeploymentConfigApi
import org.ttt.autogenesis.accelbyte.modules.DsmcOperationsApi
import org.ttt.autogenesis.accelbyte.modules.ServerApi
import org.ttt.autogenesis.accelbyte.modules.SessionApi
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Wraps Dedicated Server Manager Controller (DSMC) operations for server lifecycle, deployments, and sessions.
 * Methods return raw JSON containing server lists, deployment configs, session data, or operation results;
 * callers should map these into their domain models.
 *
 * JSON Response Structures:
 * - Server List: `{ "servers": [{ "podName": "string", "status": "string", "region": "string", "createdAt": "ISO8601" }], "paging": {...} }`
 * - Deployment: `{ "name": "string", "image": "string", "replicas": number, "regions": [...], "createdAt": "ISO8601" }`
 * - Session: `{ "sessionId": "string", "podName": "string", "status": "string", "players": [...], "createdAt": "ISO8601" }`
 * - Messages: `{ "messages": [{ "type": "string", "content": "string", "timestamp": "ISO8601" }] }`
 */
class DsmControllerFacade(private val sdk : AccelByteSdkInstance)
{
    private val module = DsmControllerModulePackage.DsmController

    private val operationsApi : DsmcOperationsApi
        get() = module.DsmcOperationsApi(sdk.rawSdk)

    private val serverApi : ServerApi
        get() = module.ServerApi(sdk.rawSdk)

    private val deploymentApi : DeploymentConfigApi
        get() = module.DeploymentConfigApi(sdk.rawSdk)

    private val sessionApi : SessionApi
        get() = module.SessionApi(sdk.rawSdk)

    /**
     * Retrieves operational messages from the DSMC system.
     *
     * @return Promise<DsmMessageListResponse> Array of system messages with timestamps and types
     *
     * Response JSON: `{ "messages": [{ "type": "info|warning|error", "content": "string", "timestamp": "ISO8601", "source": "string" }] }`
     * Error conditions: No messages available (404), service unavailable (503)
     */
    fun fetchMessages() : Promise<DsmMessageListResponse> =
        operationsApi.getMessages()
            .propagateJsErrors()
            .mapJson(DsmMessageListResponse::fromJson)

    /**
     * Retrieves a paginated list of dedicated servers in the namespace.
     *
     * @param params Query parameters for filtering and pagination
     * @return Promise<DsmServerListResponse> Paginated list of server instances with status and metadata
     *
     * Response JSON: `{ "servers": [{ "podName": "string", "status": "creating|ready|busy|unreachable", "region": "string", "ip": "string", "port": number, "createdAt": "ISO8601" }], "paging": { "total": number, "offset": number, "limit": number } }`
     * Error conditions: Invalid parameters (400), insufficient permissions (403)
     */
    fun listServers(params : DsmServerQueryParams = DsmServerQueryParams()) : Promise<DsmServerListResponse> =
        serverApi.getServers(params.toJson())
            .propagateJsErrors()
            .mapJson(DsmServerListResponse::fromJson)

    /**
     * Registers a new dedicated server instance with the DSMC system.
     *
     * @param payload Server registration data including pod name, region, and configuration
     * @return Promise<DsmServerLifecycleResponse> Registration confirmation with assigned server details
     *
     * Response JSON: `{ "podName": "string", "status": "registered", "region": "string", "sessionId": "string", "createdAt": "ISO8601" }`
     * Error conditions: Invalid payload (400), server already exists (409), registration failed (500)
     */
    fun registerServer(payload : ServerLifecycleRequest) : Promise<DsmServerLifecycleResponse> =
        serverApi.createServerRegister(payload.toJson())
            .propagateJsErrors()
            .mapJson(DsmServerLifecycleResponse::fromJson)

    /**
     * Signals that a dedicated server is shutting down gracefully.
     *
     * @param payload Shutdown request with server identification and reason
     * @return Promise<DsmServerLifecycleResponse> Shutdown acknowledgment and cleanup status
     *
     * Response JSON: `{ "podName": "string", "status": "shutting_down", "reason": "string", "timestamp": "ISO8601" }`
     * Error conditions: Server not found (404), invalid shutdown request (400)
     */
    fun shutdownServer(payload : ServerLifecycleRequest) : Promise<DsmServerLifecycleResponse> =
        serverApi.createServerShutdown(payload.toJson())
            .propagateJsErrors()
            .mapJson(DsmServerLifecycleResponse::fromJson)

    /**
     * Sends a heartbeat signal to maintain server registration.
     *
     * @param payload Heartbeat data including server status and metrics
     * @return Promise<DsmHeartbeatResponse> Heartbeat acknowledgment with next expected heartbeat time
     *
     * Response JSON: `{ "podName": "string", "acknowledged": true, "nextHeartbeat": "ISO8601", "status": "healthy" }`
     * Error conditions: Server not registered (404), heartbeat timeout (408)
     */
    fun sendHeartbeat(payload : ServerLifecycleRequest) : Promise<DsmHeartbeatResponse> =
        serverApi.updateServerHeartbeat(payload.toJson())
            .propagateJsErrors()
            .mapJson(DsmHeartbeatResponse::fromJson)

    /**
     * Retrieves detailed server count statistics by region and status.
     *
     * @param params Optional region filtering parameters
     * @return Promise<DsmServerCountResponse> Detailed server count breakdown by region and status
     *
     * Response JSON: `{ "regions": [{ "region": "string", "counts": { "total": number, "ready": number, "busy": number, "creating": number } }], "totalServers": number }`
     * Error conditions: Invalid region parameters (400), data unavailable (503)
     */
    fun countServersDetailed(params : DsmRegionQueryParams? = null) : Promise<DsmServerCountResponse> =
        serverApi.getServersCountDetailed(params?.toJson())
            .propagateJsErrors()
            .mapJson(DsmServerCountResponse::fromJson)

    /**
     * Registers a local development server for testing purposes.
     *
     * @param payload Local server registration data
     * @return Promise<DsmServerLifecycleResponse> Local registration confirmation
     *
     * Response JSON: `{ "podName": "string", "status": "local_registered", "localEndpoint": "string", "createdAt": "ISO8601" }`
     * Error conditions: Invalid local config (400), local registration failed (500)
     */
    fun registerLocalServer(payload : ServerLifecycleRequest) : Promise<DsmServerLifecycleResponse> =
        serverApi.createServerLocalRegister(payload.toJson())
            .propagateJsErrors()
            .mapJson(DsmServerLifecycleResponse::fromJson)

    /**
     * Deregisters a local development server.
     *
     * @param payload Local server deregistration data
     * @return Promise<DsmServerLifecycleResponse> Deregistration confirmation
     *
     * Response JSON: `{ "podName": "string", "status": "local_deregistered", "timestamp": "ISO8601" }`
     * Error conditions: Server not found (404), deregistration failed (500)
     */
    fun deregisterLocalServer(payload : ServerLifecycleRequest) : Promise<DsmServerLifecycleResponse> =
        serverApi.createServerLocalDeregister(payload.toJson())
            .propagateJsErrors()
            .mapJson(DsmServerLifecycleResponse::fromJson)

    /**
     * Retrieves session information for a specific server pod.
     *
     * @param podName The server pod identifier
     * @return Promise<DsmServerSessionResponse> Session details including players and status
     *
     * Response JSON: `{ "sessionId": "string", "podName": "string", "status": "waiting|active|ended", "players": [{ "userId": "string", "joinedAt": "ISO8601" }], "createdAt": "ISO8601" }`
     * Error conditions: Pod not found (404), no active session (404)
     */
    fun fetchServerSession(podName : String) : Promise<DsmServerSessionResponse> =
        serverApi.getSession_ByPodName(podName)
            .propagateJsErrors()
            .mapJson(DsmServerSessionResponse::fromJson)

    /**
     * Retrieves session timeout configuration for a specific server pod.
     *
     * @param podName The server pod identifier
     * @return Promise<DsmSessionTimeoutResponse> Timeout configuration settings
     *
     * Response JSON: `{ "podName": "string", "sessionTimeout": number, "idleTimeout": number, "maxDuration": number }`
     * Error conditions: Pod not found (404), configuration unavailable (503)
     */
    fun fetchSessionTimeout(podName : String) : Promise<DsmSessionTimeoutResponse> =
        serverApi.getConfigSessiontimeout_ByPodName(podName)
            .propagateJsErrors()
            .mapJson(DsmSessionTimeoutResponse::fromJson)

    /**
     * Retrieves a paginated list of deployment configurations.
     *
     * @param params Query parameters for filtering and pagination
     * @return Promise<DsmDeploymentListResponse> Paginated list of deployment configurations
     *
     * Response JSON: `{ "deployments": [{ "name": "string", "image": "string", "replicas": number, "regions": [...], "status": "active|inactive", "createdAt": "ISO8601" }], "paging": {...} }`
     * Error conditions: Invalid parameters (400), insufficient permissions (403)
     */
    fun listDeployments(params : DsmDeploymentQueryParams = DsmDeploymentQueryParams()) : Promise<DsmDeploymentListResponse> =
        deploymentApi.getConfigsDeployments(params.toJson())
            .propagateJsErrors()
            .mapJson(DsmDeploymentListResponse::fromJson)

    /**
     * Removes a deployment configuration.
     *
     * @param deployment The deployment name identifier
     * @return Promise<DsmDeploymentDeletionResponse> Deletion confirmation
     *
     * Response JSON: `{ "deleted": true, "deployment": "string", "timestamp": "ISO8601" }`
     * Error conditions: Deployment not found (404), deployment in use (409), insufficient permissions (403)
     */
    fun deleteDeployment(deployment : String) : Promise<DsmDeploymentDeletionResponse> =
        deploymentApi.deleteConfigDeployment_ByDeployment(deployment)
            .propagateJsErrors()
            .mapJson(DsmDeploymentDeletionResponse::fromJson)

    /**
     * Retrieves a specific deployment configuration.
     *
     * @param deployment The deployment name identifier
     * @return Promise<DsmDeploymentDetailResponse> Deployment configuration details
     *
     * Response JSON: `{ "name": "string", "image": "string", "replicas": number, "regions": [...], "resources": {...}, "createdAt": "ISO8601", "updatedAt": "ISO8601" }`
     * Error conditions: Deployment not found (404), insufficient permissions (403)
     */
    fun getDeployment(deployment : String) : Promise<DsmDeploymentDetailResponse> =
        deploymentApi.getConfigDeployment_ByDeployment(deployment)
            .propagateJsErrors()
            .mapJson(DsmDeploymentDetailResponse::fromJson)

    /**
     * Creates a new deployment configuration.
     *
     * @param deployment The deployment name identifier
     * @param payload Deployment configuration data
     * @return Promise<DsmDeploymentCreationResponse> Created deployment configuration
     *
     * Response JSON: `{ "name": "string", "image": "string", "replicas": number, "regions": [...], "status": "created", "createdAt": "ISO8601" }`
     * Error conditions: Deployment exists (409), invalid configuration (400), insufficient permissions (403)
     */
    fun createDeployment(deployment : String, payload : Json) : Promise<DsmDeploymentCreationResponse> =
        deploymentApi.createConfigDeployment_ByDeployment(deployment, payload)
            .propagateJsErrors()
            .mapJson(DsmDeploymentCreationResponse::fromJson)

    /**
     * Creates a new game session request.
     *
     * @param payload Session creation data including game mode and player requirements
     * @return Promise<DsmSessionCreationResponse> Created session details with assignment information
     *
     * Response JSON: `{ "sessionId": "string", "status": "queued", "gameMode": "string", "region": "string", "createdAt": "ISO8601" }`
     * Error conditions: Invalid session data (400), no available servers (503), quota exceeded (429)
     */
    fun createSession(payload : Json) : Promise<DsmSessionCreationResponse> =
        sessionApi.createSession(payload)
            .propagateJsErrors()
            .mapJson(DsmSessionCreationResponse::fromJson)

    /**
     * Claims an existing session for server assignment.
     *
     * @param payload Session claim data including server capabilities
     * @return Promise<DsmSessionClaimResponse> Session claim result with server assignment
     *
     * Response JSON: `{ "sessionId": "string", "claimed": true, "podName": "string", "claimedAt": "ISO8601" }`
     * Error conditions: Session not available (404), claim conflict (409), server mismatch (400)
     */
    fun claimSession(payload : Json) : Promise<DsmSessionClaimResponse> =
        sessionApi.createSessionClaim(payload)
            .propagateJsErrors()
            .mapJson(DsmSessionClaimResponse::fromJson)

    /**
     * Retrieves details for a specific session.
     *
     * @param sessionId The session identifier
     * @return Promise<DsmSessionDetailsResponse> Session details including status and participants
     *
     * Response JSON: `{ "sessionId": "string", "status": "waiting|active|ended", "podName": "string", "players": [...], "gameMode": "string", "createdAt": "ISO8601" }`
     * Error conditions: Session not found (404), insufficient permissions (403)
     */
    fun fetchSession(sessionId : String) : Promise<DsmSessionDetailsResponse> =
        sessionApi.getSession_BySessionId(sessionId)
            .propagateJsErrors()
            .mapJson(DsmSessionDetailsResponse::fromJson)

    /**
     * Cancels an active or queued session.
     *
     * @param sessionId The session identifier to cancel
     * @return Promise<DsmSessionCancellationResponse> Cancellation confirmation
     *
     * Response JSON: `{ "sessionId": "string", "cancelled": true, "reason": "string", "cancelledAt": "ISO8601" }`
     * Error conditions: Session not found (404), session already ended (409), cancellation failed (500)
     */
    fun cancelSession(sessionId : String) : Promise<DsmSessionCancellationResponse> =
        sessionApi.deleteCancel_BySessionId(sessionId)
            .propagateJsErrors()
            .mapJson(DsmSessionCancellationResponse::fromJson)
}
