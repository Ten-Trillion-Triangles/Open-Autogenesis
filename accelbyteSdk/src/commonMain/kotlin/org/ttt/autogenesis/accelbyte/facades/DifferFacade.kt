package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.DiffRequest
import org.ttt.autogenesis.accelbyte.models.DiffResultResponse
import org.ttt.autogenesis.accelbyte.models.DiffResultV2Response
import org.ttt.autogenesis.accelbyte.models.DifferHealthResponse
import org.ttt.autogenesis.accelbyte.modules.DifferModulePackage
import org.ttt.autogenesis.accelbyte.modules.DiffCalculationApi
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Wraps Differ service operations for calculating file differences between builds. Methods return raw JSON
 * containing diff results, calculation status, or service health information; callers should map these into their domain models.
 *
 * JSON Response Structures:
 * - Diff Result: `{ "diffId": "string", "status": "string", "progress": number, "files": [...] }`
 * - Ping Response: `{ "status": "ok", "timestamp": "ISO8601", "version": "string" }`
 *
 * @param sdk AccelByte SDK instance used to resolve Differ APIs.
 */
class DifferFacade(private val sdk : AccelByteSdkInstance)
{
    private val diffApi : DiffCalculationApi
        get() = DifferModulePackage.Differ.DiffCalculationApi(sdk.rawSdk)

    /**
     * Initiates a diff calculation between two build versions (v1 API).
     *
     * @param request Diff calculation parameters including source and target build IDs
     * @return `Promise<DiffResultResponse>` Diff calculation result with status and file changes
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if access to either build is forbidden
     * @throws ValidationException if the build identifiers are invalid
     *
     * Response JSON: `{ "diffId": "string", "status": "pending|completed|failed", "progress": number, "files": [{ "path": "string", "action": "add|remove|modify", "size": number }], "createdAt": "ISO8601" }`
     * Error conditions: Invalid build IDs (404), calculation failed (500), quota exceeded (429)
     */
    fun createDiff(request : DiffRequest) : Promise<DiffResultResponse> =
        diffApi.createDiff(request.toJson())
            .propagateJsErrors()
            .mapJson(DiffResultResponse::fromJson)

    /**
     * Initiates a diff calculation between two build versions (v2 API with enhanced features).
     *
     * @param request Diff calculation parameters with additional options for v2 API
     * @return `Promise<DiffResultV2Response>` Enhanced diff calculation result with detailed metadata
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if access to either build is forbidden
     * @throws ValidationException if the build identifiers or options are invalid
     *
     * Response JSON: `{ "diffId": "string", "status": "pending|completed|failed", "progress": number, "files": [{ "path": "string", "action": "add|remove|modify", "size": number, "checksum": "string" }], "metadata": {...}, "createdAt": "ISO8601" }`
     * Error conditions: Invalid build IDs (404), calculation failed (500), quota exceeded (429), unsupported features (400)
     */
    fun createDiffV2(request : DiffRequest) : Promise<DiffResultV2Response> =
        diffApi.createDiff_v2(request.toJson())
            .propagateJsErrors()
            .mapJson(DiffResultV2Response::fromJson)

    /**
     * Checks the health and availability of the Differ service.
     *
     * @return `Promise<DifferHealthResponse>` Service health status and version information
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot read the health endpoint
     *
     * Response JSON: `{ "status": "ok|degraded|down", "timestamp": "ISO8601", "version": "string", "uptime": number }`
     * Error conditions: Service unavailable (503), internal error (500)
     */
    fun ping() : Promise<DifferHealthResponse> =
        diffApi.getPing()
            .propagateJsErrors()
            .mapJson(DifferHealthResponse::fromJson)
}