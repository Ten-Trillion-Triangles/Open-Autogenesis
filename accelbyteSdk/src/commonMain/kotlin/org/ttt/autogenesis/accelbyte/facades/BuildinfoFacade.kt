package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.BlockDownloadUrlResponse
import org.ttt.autogenesis.accelbyte.models.BlockUrlParams
import org.ttt.autogenesis.accelbyte.models.CacheDiffResponse
import org.ttt.autogenesis.accelbyte.models.DiffStatusResponse
import org.ttt.autogenesis.accelbyte.models.VersionHistoryParams
import org.ttt.autogenesis.accelbyte.models.VersionHistoryResponse
import org.ttt.autogenesis.accelbyte.modules.BuildinfoModulePackage
import org.ttt.autogenesis.accelbyte.modules.CachingApi
import org.ttt.autogenesis.accelbyte.modules.DownloaderApi
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Wraps Build Info operations for version history, diff status, and download URLs. Methods return raw JSON
 * containing build metadata, version arrays, or download URLs; callers should map these into their domain models.
 *
 * JSON Response Structures:
 * - Version History: `{ "versions": [{ "buildId": "string", "version": "string", "createdAt": "ISO8601" }] }`
 * - Diff Status: `{ "status": "string", "progress": number, "diffSize": number }`
 * - Block URL: `{ "url": "string", "expiresAt": "ISO8601" }`
 * - Cache Diff: `{ "files": [{ "path": "string", "action": "add|remove|modify" }] }`
 *
 * @param sdk AccelByte SDK instance used to resolve Buildinfo module APIs.
 */
class BuildinfoFacade(private val sdk : AccelByteSdkInstance)
{
    private val downloaderApi : DownloaderApi
        get() = BuildinfoModulePackage.Buildinfo.DownloaderApi(sdk.rawSdk)

    private val cachingApi : CachingApi
        get() = BuildinfoModulePackage.Buildinfo.CachingApi(sdk.rawSdk)

    /**
     * Retrieves historical build versions for the specified application.
     *
     * @param params Query parameters including appId, platform, and optional pagination
     * @return `Promise<VersionHistoryResponse>` Array of build versions with metadata (buildId, version, createdAt, size)
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if caller lacks access to version history for the app
     * @throws ValidationException if the provided appId or pagination parameters are invalid
     */
    fun getVersionHistory(params : VersionHistoryParams) : Promise<VersionHistoryResponse> =
        downloaderApi.getVersionHistory(params.toJson())
            .propagateJsErrors()
            .mapJson(VersionHistoryResponse::fromJson)

    /**
     * Checks the diff calculation status between two build versions.
     *
     * @param sourceBuildId The source build identifier
     * @param destinationBuildId The target build identifier
     * @return `Promise<DiffStatusResponse>` Diff status with progress and size information
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if access to one of the builds is denied
     * @throws ValidationException if either build identifier is invalid
     */
    fun getDiffStatus(sourceBuildId : String, destinationBuildId : String) : Promise<DiffStatusResponse> =
        downloaderApi.getDiff_BySourceBuildId_ByDestinationBuildId(sourceBuildId, destinationBuildId)
            .propagateJsErrors()
            .mapJson(DiffStatusResponse::fromJson)

    /**
     * Generates a time-limited download URL for build blocks/chunks.
     *
     * @param buildId The build identifier
     * @param params Block parameters including blockType and optional compression settings
     * @return `Promise<BlockDownloadUrlResponse>` Download URL with expiration timestamp
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if download access is forbidden
     * @throws ValidationException if the provided buildId or block parameters are invalid
     */
    fun createBlockDownloadUrl(buildId : String, params : BlockUrlParams) : Promise<BlockDownloadUrlResponse> =
        downloaderApi.createBlockUrl_ByBuildId(buildId, params.toJson())
            .propagateJsErrors()
            .mapJson(BlockDownloadUrlResponse::fromJson)

    /**
     * Retrieves cache diff information between two build versions for optimization.
     *
     * @param sourceBuildId The source build identifier
     * @param destinationBuildId The target build identifier
     * @return `Promise<CacheDiffResponse>` File-level diff information for cache optimization
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if caller cannot view cache diffs for the builds
     * @throws ValidationException if either build identifier is invalid
     */
    fun getCacheDiff(sourceBuildId : String, destinationBuildId : String) : Promise<CacheDiffResponse> =
        cachingApi.getDestCacheDiff_BySourceBuildId_ByDestinationBuildId(sourceBuildId, destinationBuildId)
            .propagateJsErrors()
            .mapJson(CacheDiffResponse::fromJson)
}