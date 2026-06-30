package structs.accelbyte.legal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable

@Serializable
data class LocalizedPolicyVersionResponse(
    val id: String,
    val localeCode: String,
    val isDefaultSelection: Boolean,
    val attachmentChecksum: String? = null,
    val attachmentLocation: String? = null,
    val attachmentVersionIdentifier: String? = null,
    val contentType: String? = null,
    val description: String? = null,
    val status: String? = null,
    val publishedDate: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LegalPolicyVersionResponse(
    val id: String,
    val displayVersion: String,
    val isCommitted: Boolean,
    val isInEffect: Boolean,
    val localizedPolicyVersions: List<LocalizedPolicyVersionResponse>? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val description: String? = null,
    val publishedDate: String? = null,
    val status: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LegalPolicyResponse(
    val id: String,
    val policyName: String,
    val policyType: String,
    val namespace: String,
    val basePolicyId: String,
    val countryCode: String,
    val countryGroupCode: String? = null,
    val description: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val isMandatory: Boolean,
    val isDefaultSelection: Boolean,
    val isDefaultOpted: Boolean,
    val shouldNotifyOnUpdate: Boolean,
    val baseUrls: List<String>? = null,
    val policyVersions: List<LegalPolicyVersionResponse>? = null,
    val readableId: String? = null,
    val tags: List<String>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LegalPolicyListResponse(val policies: List<LegalPolicyResponse>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AgreementConfirmationResponse(val comply: Boolean, val proceed: Boolean) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AcceptedAgreementResponse(
    val id: String,
    val policyId: String? = null,
    val policyName: String? = null,
    val policyType: String? = null,
    val namespace: String? = null,
    val countryCode: String? = null,
    val countryGroupName: String? = null,
    val userId: String? = null,
    val signingDate: String? = null,
    val displayVersion: String? = null,
    val tags: List<String>? = null,
    val localizedPolicyVersion: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LegalCountryListResponse(val countries: List<String>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LegalReadinessResponse(val isReady: Boolean? = null) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
