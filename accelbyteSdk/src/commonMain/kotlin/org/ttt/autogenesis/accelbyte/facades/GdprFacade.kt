package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.GdprDataRetrievalResponse
import org.ttt.autogenesis.accelbyte.models.GdprFinishedDataDeletionListResponse
import org.ttt.autogenesis.accelbyte.models.GdprFinishedDataDeletion
import org.ttt.autogenesis.accelbyte.models.GdprPasswordRequest
import org.ttt.autogenesis.accelbyte.models.GdprRequestListParams
import org.ttt.autogenesis.accelbyte.modules.GdprModulePackage
import org.ttt.autogenesis.accelbyte.modules.DataDeletionApi
import org.ttt.autogenesis.accelbyte.modules.DataRetrievalApi
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * GDPR data retrieval/deletion helpers. Returned JSON contains the user request entries or status acknowledgements.
 */
class GdprFacade(private val sdk : AccelByteSdkInstance)
{
    private val retrievalApi : DataRetrievalApi
        get() = GdprModulePackage.Gdpr.DataRetrievalApi(sdk.rawSdk)

    private val deletionApi : DataDeletionApi
        get() = GdprModulePackage.Gdpr.DataDeletionApi(sdk.rawSdk)

    /**
     * Requests a data download bundle for the provided user.
     * @param request password confirmation payload required by the API.
     */
    fun requestDataDownload(userId : String, request : GdprPasswordRequest) : Promise<GdprDataRetrievalResponse> =
        retrievalApi.postRequest_ByUserId(userId, request.toJson())
            .propagateJsErrors()
            .mapJson(GdprDataRetrievalResponse::fromJson)

    /**
     * Lists existing data retrieval requests for the user.
     */
    fun listRequests(userId : String, params : GdprRequestListParams = GdprRequestListParams()) : Promise<GdprFinishedDataDeletionListResponse> =
        retrievalApi.getRequests_ByUserId(userId, params.toJson())
            .propagateJsErrors()
            .mapJson(GdprFinishedDataDeletionListResponse::fromJson)

    /**
     * Submits a deletion request for the user.
     */
    fun createDeletionRequest(userId : String, request : GdprPasswordRequest) : Promise<GdprFinishedDataDeletion> =
        deletionApi.postRequest_ByUserId(userId, request.toJson())
            .propagateJsErrors()
            .mapJson(GdprFinishedDataDeletion::fromJson)

    fun getRequest(userId : String, requestDate : String) : Promise<GdprFinishedDataDeletion> =
        deletionApi.getRequest_ByUserId_ByRequestDate(userId, requestDate)
            .propagateJsErrors()
            .mapJson(GdprFinishedDataDeletion::fromJson)
}
