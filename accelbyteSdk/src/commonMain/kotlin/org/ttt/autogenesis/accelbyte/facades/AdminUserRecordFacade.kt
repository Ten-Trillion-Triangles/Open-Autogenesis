package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.modules.Cloudsave
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Helper that exposes the admin-level user record endpoints from [Cloudsave].
 *
 * @param sdk SDK instance used to resolve the CloudSave admin APIs.
 */
class AdminUserRecordFacade(private val sdk : AccelByteSdkInstance)
{
    private val recordApi get() = Cloudsave.RecordAdminApi(sdk.rawSdk, null)
    private val playerRecordApi get() = Cloudsave.PlayerRecordAdminApi(sdk.rawSdk, null)

    /**
     * Fetches a user record using admin credentials.
     */
    fun fetchRecord(userId : String, key : String) : Promise<Json>
    {
        return recordApi.getAdminrecord_ByUserId_ByKey(userId, key)
            .propagateJsErrors()
    }

    /**
     * Creates a new user record under admin permission.
     */
    fun createRecord(userId : String, key : String, data : Json) : Promise<Json>
    {
        return recordApi.createAdminrecord_ByUserId_ByKey(userId, key, data)
            .propagateJsErrors()
    }

    /**
     * Updates an existing user record under admin permission.
     */
    fun updateRecord(userId : String, key : String, data : Json) : Promise<Json>
    {
        return recordApi.updateAdminrecord_ByUserId_ByKey(userId, key, data)
            .propagateJsErrors()
    }

    /**
     * Retrieves a user public record by key while retaining admin context.
     */
    fun fetchPublicRecord(userId : String, key : String) : Promise<Json>
    {
        return playerRecordApi.getPublic_ByUserId_ByKey(userId, key)
            .propagateJsErrors()
    }
}