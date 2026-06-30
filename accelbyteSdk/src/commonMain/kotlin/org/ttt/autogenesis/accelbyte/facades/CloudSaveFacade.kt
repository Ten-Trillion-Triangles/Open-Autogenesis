package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.BulkGameRecordRequest
import org.ttt.autogenesis.accelbyte.models.GameRecordRequest
import org.ttt.autogenesis.accelbyte.models.GameRecordResponse
import org.ttt.autogenesis.accelbyte.models.BulkGameRecordResponse
import org.ttt.autogenesis.accelbyte.modules.Cloudsave
import org.ttt.autogenesis.accelbyte.modules.PublicGameRecordApi
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Cloud save helpers for reading and writing namespace-scoped data.
 *
 * Each method deserializes backend JSON into structured `GameRecordResponse`/`BulkGameRecordResponse`
 * instances so callers can work with typed metadata instead of raw `Json`.
 *
 * @param sdk AccelByte SDK instance used to resolve Cloud Save APIs.
 */
class CloudSaveFacade(private val sdk : AccelByteSdkInstance)
{
    private val recordApi : PublicGameRecordApi
        get() = Cloudsave.PublicGameRecordApi(sdk.rawSdk, null)

    /**
     * Fetches an existing game record by key.
     *
     * @param key record key (namespace/job-specific string).
     * @return `Promise<GameRecordResponse>` containing record metadata and payload.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot read the key
     */
    fun fetchRecord(key : String) : Promise<GameRecordResponse> =
        recordApi.getRecord_ByKey(key)
            .propagateJsErrors()
            .mapJson(GameRecordResponse::fromJson)

    /**
     * Creates a new record identified by `key` when it does not already exist.
     *
     * @param key record key (namespace/job-specific string).
     * @param request typed payload describing the new record data.
     * @return `Promise<GameRecordResponse>` with the created record metadata.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot create this key
     * @throws ValidationException if the request payload is invalid
     */
    fun createRecord(key : String, request : GameRecordRequest) : Promise<GameRecordResponse> =
        recordApi.createRecord_ByKey(key, request.toJson())
            .propagateJsErrors()
            .mapJson(GameRecordResponse::fromJson)

    /**
     * Creates or updates a dedicated key in the cloud save storage.
     *
     * @param key record key (namespace/job-specific string).
     * @param request typed payload including `data` and `updateIfExists`.
     * @return the backend JSON response; typically includes metadata such as version/timestamps.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot write to the key
     * @throws ValidationException if the request payload is invalid
     */
    fun updateRecord(key : String, request : GameRecordRequest) : Promise<GameRecordResponse> =
        recordApi.updateRecord_ByKey(key, request.toJson())
            .propagateJsErrors()
            .mapJson(GameRecordResponse::fromJson)

    /**
     * Fetches records for multiple keys simultaneously; useful for batching.
     *
     * @param request request containing the list of keys to retrieve.
     * @return `Promise<BulkGameRecordResponse>` with the retrieved records.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot read the requested keys
     * @throws ValidationException if the request is malformed or empty
     */
    fun bulkFetch(request : BulkGameRecordRequest) : Promise<BulkGameRecordResponse> =
        recordApi.fetchRecordBulk(request.toJson())
            .propagateJsErrors()
            .mapJson(BulkGameRecordResponse::fromJson)
}
