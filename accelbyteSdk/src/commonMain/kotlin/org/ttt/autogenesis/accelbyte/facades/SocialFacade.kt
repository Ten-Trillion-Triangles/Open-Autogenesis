package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.StatItemQueryParams
import org.ttt.autogenesis.accelbyte.models.StatItemsBulkRequest
import org.ttt.autogenesis.accelbyte.models.StatItemsResponse
import org.ttt.autogenesis.accelbyte.modules.SocialModulePackage
import org.ttt.autogenesis.accelbyte.modules.UserStatisticApi
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Exposes social/statistics endpoints.
 */
class SocialFacade(private val sdk : AccelByteSdkInstance)
{
    private val userStatApi : UserStatisticApi
        get() = SocialModulePackage.Social.UserStatisticApi(sdk.rawSdk)

    /**
     * Returns stats for the current user.
     * @param params paging parameters (limit) when retrieving stat items.
     */
    fun getMyStats(params : StatItemQueryParams = StatItemQueryParams()) : Promise<StatItemsResponse> =
        userStatApi.getUsersMeStatitems(params.toJson())
            .propagateJsErrors()
            .mapJson(StatItemsResponse::fromJson)

    /**
     * Updates a batch of stat items.
     * @param items JSON payload describing `statCode`/`value` pairs.
     */
    fun updateStatsBulk(items : StatItemsBulkRequest) : Promise<StatItemsResponse> =
        userStatApi.patchStatitemValueBulk(items.toJson())
            .propagateJsErrors()
            .mapJson(StatItemsResponse::fromJson)

    fun getUserStats(userId : String) : Promise<StatItemsResponse> =
        userStatApi.getStatitems_ByUserId(userId)
            .propagateJsErrors()
            .mapJson(StatItemsResponse::fromJson)
}