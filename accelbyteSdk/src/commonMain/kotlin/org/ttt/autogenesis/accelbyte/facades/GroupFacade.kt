package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.GroupCreateRequest
import org.ttt.autogenesis.accelbyte.models.GroupListResponseModel
import org.ttt.autogenesis.accelbyte.models.GroupQueryParams
import org.ttt.autogenesis.accelbyte.models.GroupResponseModel
import org.ttt.autogenesis.accelbyte.modules.GroupApi
import org.ttt.autogenesis.accelbyte.modules.GroupModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * A thin facade over the AccelByte Group module. Provides operations for listing, creating,
 * retrieving, and deleting groups in the platform group service.
 */
class GroupFacade(private val sdk : AccelByteSdkInstance)
{
    private val groupApi : GroupApi
        get() = GroupModulePackage.Group.GroupApi(sdk.rawSdk)

    /**
     * Lists groups matching the supplied filter parameters.
     *
     * @param params query parameters including optional [GroupQueryParams.groupName] filter
     *              and [GroupQueryParams.limit]. Null field values are omitted from the query.
     * @return [GroupListResponseModel] containing the list of groups and any pagination cursors.
     */
    fun listGroups(params : GroupQueryParams = GroupQueryParams()) : Promise<GroupListResponseModel> =
        groupApi.getGroups(params.toJson())
            .propagateJsErrors()
            .mapJson(GroupListResponseModel::fromJson)

    /**
     * Creates a new group in the current namespace.
     *
     * @param request [GroupCreateRequest] carrying the [GroupCreateRequest.groupName] and
     *                [GroupCreateRequest.groupRegion].
     * @return [GroupResponseModel] for the newly created group.
     */
    fun createGroup(request : GroupCreateRequest) : Promise<GroupResponseModel> =
        groupApi.createGroup(request.toJson())
            .propagateJsErrors()
            .mapJson(GroupResponseModel::fromJson)

    /**
     * Permanently deletes an existing group by its identifier.
     *
     * @param groupId the unique identifier of the group to remove.
     * @return Promise<Unit> resolved on successful deletion.
     * @throws propagateJsErrors on network errors or if the group does not exist.
     */
    fun deleteGroup(groupId : String) : Promise<Unit> =
        groupApi.deleteGroup_ByGroupId(groupId)
            .propagateJsErrors()
            .mapJson { }

    /**
     * Retrieves the full details of a single group.
     *
     * @param groupId the unique identifier of the group to retrieve.
     * @return [GroupResponseModel] with the group's current state.
     * @throws propagateJsErrors on network errors or if the group does not exist.
     */
    fun getGroup(groupId : String) : Promise<GroupResponseModel> =
        groupApi.getGroup_ByGroupId(groupId)
            .propagateJsErrors()
            .mapJson(GroupResponseModel::fromJson)
}