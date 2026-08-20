package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.modules.Cloudsave
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * A client-side facade for the public player record endpoints of the CloudSave module.
 * Allows the currently authenticated user to read, create, and update their own cloud-saved records by key.
 * Records are namespaced to the user identified by the stored token.
 */
class PlayerRecordFacade(private val sdk : AccelByteSdkInstance)
{
    private val api get() = Cloudsave.PublicPlayerRecordApi(sdk.rawSdk, null)

    /**
     * @param userId target user whose record to retrieve (must be the token-holder for public access)
     * @param key the record key identifier
     * @return the stored JSON record via propagateJsErrors
     */
    fun fetchRecord(userId : String, key : String) : Promise<Json>
    {
        return api.getRecord_ByUserId_ByKey(userId, key)
            .propagateJsErrors()
    }

    /**
     * @param userId target user
     * @param key record key to create
     * @param data the JSON body to store
     * @return the created record JSON
     */
    fun createRecord(userId : String, key : String, data : Json) : Promise<Json>
    {
        return api.createRecord_ByUserId_ByKey(userId, key, data)
            .propagateJsErrors()
    }

    /**
     * @param userId target user
     * @param key record key
     * @param data JSON patch body
     * @return the updated record JSON
     */
    fun updateRecord(userId : String, key : String, data : Json) : Promise<Json>
    {
        return api.updateRecord_ByUserId_ByKey(userId, key, data)
            .propagateJsErrors()
    }
}