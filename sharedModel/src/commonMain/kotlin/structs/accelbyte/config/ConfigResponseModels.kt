package structs.accelbyte.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.CursorPagination
import structs.accelbyte.common.PagingInfo

@Serializable
data class ConfigEntry(
    val key: String,
    val value: JsonElement? = null,
    val namespace: String? = null,
    val publisherNamespace: String? = null,
    @SerialName("public") val isPublic: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ConfigListResponse(val configs: List<ConfigEntry>, val paging: PagingInfo) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class PublicConfigListResponse(val configs: List<ConfigEntry>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ProfileConfigResponse(
    val profileConfig: JsonElement,
    val createdAt: String? = null,
    val updatedAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EmailTemplate(
    val name: String,
    val subject: String,
    val body: String,
    val type: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EmailTemplateListResponse(val templates: List<EmailTemplate>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EmailTemplatesUpdateResponse(
    val updated: Boolean,
    val templates: List<EmailTemplate>,
    val updatedAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EmailSenderConfigResponse(
    val account: String,
    val verified: Boolean,
    val settings: JsonElement,
    val templates: List<EmailTemplate>? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EmailSenderVerificationResponse(
    val verificationSent: Boolean,
    val email: String? = null,
    val expiresAt: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EmailApiKeyAccount(
    val account: String,
    val createdAt: String,
    val status: String,
    val verified: Boolean? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EmailApiKeyListResponse(val accounts: List<EmailApiKeyAccount>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EmailApiKeyCreationResponse(
    val account: String,
    val apiKey: String,
    val createdAt: String,
    val status: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LinkedSender(
    val email: String,
    val verified: Boolean,
    val createdAt: String
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class LinkedSendersResponse(val senders: List<LinkedSender>, val paging: PagingInfo) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class DeletionResponse(
    val deleted: Boolean,
    val account: String? = null,
    val key: String? = null,
    val templatesRemoved: Int? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
