package structs.accelbyte.group

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.CursorPagination

@Serializable
data class RuleInformationResponse(
    val ruleAttribute: String,
    val ruleCriteria: String,
    val ruleValue: Int
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class RuleResponseModel(
    val allowedAction: String,
    val ruleDetail: List<RuleInformationResponse>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GroupRuleResponseModel(
    val groupCustomRule: JsonElement? = null,
    val groupPredefinedRules: List<RuleResponseModel>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GroupMemberResponse(
    val userId: String,
    val memberRoleId: List<String>
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GroupResponseModel(
    val groupId: String,
    val groupName: String,
    val groupDescription: String,
    val groupType: String,
    val groupRegion: String,
    val configurationCode: String,
    val groupIcon: String,
    val groupMaxMember: Int,
    val namespace: String? = null,
    val createdAt: String,
    val groupMembers: List<GroupMemberResponse>,
    val groupRules: GroupRuleResponseModel,
    val customAttributes: JsonElement
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class GroupListResponseModel(
    val data: List<GroupResponseModel>,
    val paging: CursorPagination? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
