package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.ContentListResponse
import org.ttt.autogenesis.accelbyte.models.ContentQueryParams
import org.ttt.autogenesis.accelbyte.models.ContentDownloadResponseModel
import org.ttt.autogenesis.accelbyte.models.UserFollowResponseModel
import org.ttt.autogenesis.accelbyte.modules.PublicContentV2Api
import org.ttt.autogenesis.accelbyte.modules.PublicFollowApi
import org.ttt.autogenesis.accelbyte.modules.UgcModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * A thin facade over the AccelByte UGC (User-Generated Content) module.
 * Manages creator content: uploading content to channels, searching by type/tag,
 * tracking like/favorite state, and following content creators.
 */
class UgcFacade(private val sdk : AccelByteSdkInstance)
{
    private val contentApi : PublicContentV2Api
        get() = UgcModulePackage.Ugc.PublicContentV2Api(sdk.rawSdk)

    private val followApi : PublicFollowApi
        get() = UgcModulePackage.Ugc.PublicFollowApi(sdk.rawSdk)

    /** Fetches a single content item by its unique ID. */
    fun fetchContent(contentId : String) : Promise<ContentDownloadResponseModel> =
        contentApi.getContentById(contentId)
            .propagateJsErrors()
            .mapJson(ContentDownloadResponseModel::fromJson)

    /** Lists content items, optionally filtered by type/tag via [params]. */
    fun listContents(params : ContentQueryParams = ContentQueryParams()) : Promise<ContentListResponse> =
        contentApi.getContents(params.toJson())
            .propagateJsErrors()
            .mapJson(ContentListResponse::fromJson)

    /** Follows the specified content creator. */
    fun follow(creatorId : String) : Promise<UserFollowResponseModel> =
        followApi.follow(creatorId)
            .propagateJsErrors()
            .mapJson(UserFollowResponseModel::fromJson)

    /** Unfollows the specified content creator. */
    fun unfollow(creatorId : String) : Promise<UserFollowResponseModel> =
        followApi.unfollow(creatorId)
            .propagateJsErrors()
            .mapJson(UserFollowResponseModel::fromJson)
}