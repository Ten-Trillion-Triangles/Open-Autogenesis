package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * A single rule information entry for a group rule.
 *
 * @property ruleAttribute The attribute this rule evaluates.
 * @property ruleCriteria The criteria for this rule.
 * @property ruleValue The value used in the rule evaluation.
 */
data class RuleInformationResponse(
    val ruleAttribute : String,
    val ruleCriteria : String,
    val ruleValue : Int
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : RuleInformationResponse = RuleInformationResponse(
            ruleAttribute = json.requireString("ruleAttribute"),
            ruleCriteria = json.requireString("ruleCriteria"),
            ruleValue = json.requireInt("ruleValue")
        )
    }
}

/**
 * A group rule with its allowed action and detail.
 *
 * @property allowedAction The action this rule allows.
 * @property ruleDetail The list of rule information entries.
 */
data class RuleResponseModel(
    val allowedAction : String,
    val ruleDetail : List<RuleInformationResponse>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : RuleResponseModel = RuleResponseModel(
            allowedAction = json.requireString("allowedAction"),
            ruleDetail = json.optJsonList("ruleDetail").map(RuleInformationResponse::fromJson)
        )
    }
}

/**
 * A group rule set containing custom and predefined rules.
 *
 * @property groupCustomRule Custom rules for the group.
 * @property groupPredefinedRules Predefined rules for the group.
 */
data class GroupRuleResponseModel(
    val groupCustomRule : Json?,
    val groupPredefinedRules : List<RuleResponseModel>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GroupRuleResponseModel = GroupRuleResponseModel(
            groupCustomRule = json.optJson("groupCustomRule"),
            groupPredefinedRules = json.optJsonList("groupPredefinedRules").map(RuleResponseModel::fromJson)
        )
    }
}

/**
 * A member of a group with their role assignments.
 *
 * @property userId The unique identifier of the member.
 * @property memberRoleId List of role identifiers assigned to this member.
 */
data class GroupMemberResponse(
    val userId : String,
    val memberRoleId : List<String>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GroupMemberResponse = GroupMemberResponse(
            userId = json.requireString("userId"),
            memberRoleId = json.optJsonArray("memberRoleId")?.mapNotNull { it?.toString() } ?: emptyList()
        )
    }
}

/**
 * A complete group response from the platform group service.
 *
 * @property groupId The unique identifier of the group.
 * @property groupName The display name of the group.
 * @property groupDescription The description of the group.
 * @property groupType The type of the group.
 * @property groupRegion The region where the group operates.
 * @property configurationCode The configuration code for this group.
 * @property groupIcon URL to the group icon image.
 * @property groupMaxMember The maximum number of members allowed.
 * @property namespace The namespace where this group exists.
 * @property createdAt Timestamp when the group was created.
 * @property groupMembers List of members in the group.
 * @property groupRules The rules governing this group.
 * @property customAttributes Custom attributes for this group.
 */
data class GroupResponseModel(
    val groupId : String,
    val groupName : String,
    val groupDescription : String,
    val groupType : String,
    val groupRegion : String,
    val configurationCode : String,
    val groupIcon : String,
    val groupMaxMember : Int,
    val namespace : String?,
    val createdAt : String,
    val groupMembers : List<GroupMemberResponse>,
    val groupRules : GroupRuleResponseModel,
    val customAttributes : Json
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GroupResponseModel = GroupResponseModel(
            groupId = json.requireString("groupId"),
            groupName = json.requireString("groupName"),
            groupDescription = json.requireString("groupDescription"),
            groupType = json.requireString("groupType"),
            groupRegion = json.requireString("groupRegion"),
            configurationCode = json.requireString("configurationCode"),
            groupIcon = json.requireString("groupIcon"),
            groupMaxMember = json.requireInt("groupMaxMember"),
            namespace = json.optString("namespace"),
            createdAt = json.requireString("createdAt"),
            groupMembers = json.optJsonList("groupMembers").map(GroupMemberResponse::fromJson),
            groupRules = GroupRuleResponseModel.fromJson(json.requireJson("groupRules")),
            customAttributes = json.requireJson("customAttributes")
        )
    }
}

/**
 * A paginated list of groups.
 *
 * @property data The list of groups on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class GroupListResponseModel(
    val data : List<GroupResponseModel>,
    val paging : CursorPagination?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : GroupListResponseModel = GroupListResponseModel(
            data = json.optJsonList("data").map(GroupResponseModel::fromJson),
            paging = json.optJson("paging")?.let(CursorPagination::fromJson)
        )
    }
}