package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Response model for a configuration entry.
 *
 * @property key Configuration key identifier.
 * @property value Configuration value.
 * @property namespace Configuration namespace.
 * @property publisherNamespace Publisher namespace if different.
 * @property isPublic Whether this configuration is publicly accessible.
 * @property createdAt Creation timestamp.
 * @property updatedAt Last update timestamp.
 */
data class ConfigEntry(
    val key : String,
    val value : Any?,
    val namespace : String?,
    val publisherNamespace : String?,
    val isPublic : Boolean?,
    val createdAt : String?,
    val updatedAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ConfigEntry = ConfigEntry(
            key = json.requireString("key"),
            value = json["value"],
            namespace = json.optString("namespace"),
            publisherNamespace = json.optString("publisherNamespace"),
            isPublic = json.optBoolean("public"),
            createdAt = json.optString("createdAt"),
            updatedAt = json.optString("updatedAt")
        )
    }
}

/**
 * Paginated response containing configuration entries.
 *
 * @property configs List of configuration entries.
 * @property paging Pagination metadata.
 */
data class ConfigListResponse(
    val configs : List<ConfigEntry>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ConfigListResponse = ConfigListResponse(
            configs = json.optJsonList("configs").map(ConfigEntry::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Response containing a list of public configuration entries.
 *
 * @property configs List of public configuration entries.
 */
data class PublicConfigListResponse(
    val configs : List<ConfigEntry>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : PublicConfigListResponse = PublicConfigListResponse(
            configs = json.optJsonList("configs").map(ConfigEntry::fromJson)
        )
    }
}

/**
 * Response containing profile configuration data.
 *
 * @property profileConfig The profile configuration as a JSON object.
 * @property createdAt Creation timestamp.
 * @property updatedAt Last update timestamp.
 */
data class ProfileConfigResponse(
    val profileConfig : Json,
    val createdAt : String?,
    val updatedAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ProfileConfigResponse = ProfileConfigResponse(
            profileConfig = json.requireJson("profileConfig"),
            createdAt = json.optString("createdAt"),
            updatedAt = json.optString("updatedAt")
        )
    }
}

/**
 * An email template for sending emails.
 *
 * @property name The unique name of this email template.
 * @property subject The email subject line.
 * @property body The email body content.
 * @property type The type of email template.
 */
data class EmailTemplate(
    val name : String,
    val subject : String,
    val body : String,
    val type : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EmailTemplate = EmailTemplate(
            name = json.requireString("name"),
            subject = json.requireString("subject"),
            body = json.requireString("body"),
            type = json.requireString("type")
        )
    }
}

/**
 * A list of email templates.
 *
 * @property templates The list of email templates.
 */
data class EmailTemplateListResponse(
    val templates : List<EmailTemplate>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EmailTemplateListResponse = EmailTemplateListResponse(
            templates = json.optJsonList("templates").map(EmailTemplate::fromJson)
        )
    }
}

/**
 * Response after updating email templates.
 *
 * @property updated Whether any templates were updated.
 * @property templates The list of updated email templates.
 * @property updatedAt Timestamp when the update occurred.
 */
data class EmailTemplatesUpdateResponse(
    val updated : Boolean,
    val templates : List<EmailTemplate>,
    val updatedAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EmailTemplatesUpdateResponse = EmailTemplatesUpdateResponse(
            updated = json.requireBoolean("updated"),
            templates = json.optJsonList("templates").map(EmailTemplate::fromJson),
            updatedAt = json.optString("updatedAt")
        )
    }
}

/**
 * Response containing email sender configuration.
 *
 * @property account The email sender account address.
 * @property verified Whether this sender is verified.
 * @property settings The sender settings as a JSON object.
 * @property templates List of associated email templates.
 * @property createdAt Creation timestamp.
 * @property updatedAt Last update timestamp.
 */
data class EmailSenderConfigResponse(
    val account : String,
    val verified : Boolean,
    val settings : Json,
    val templates : List<EmailTemplate>?,
    val createdAt : String?,
    val updatedAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EmailSenderConfigResponse = EmailSenderConfigResponse(
            account = json.requireString("account"),
            verified = json.requireBoolean("verified"),
            settings = json.requireJson("settings"),
            templates = json.optJsonList("templates").takeIf { it.isNotEmpty() }
                ?.let { it.map(EmailTemplate::fromJson) },
            createdAt = json.optString("createdAt"),
            updatedAt = json.optString("updatedAt")
        )
    }
}

/**
 * Response after requesting email sender verification.
 *
 * @property verificationSent Whether a verification email was sent.
 * @property email The email address the verification was sent to.
 * @property expiresAt Timestamp when the verification expires.
 */
data class EmailSenderVerificationResponse(
    val verificationSent : Boolean,
    val email : String?,
    val expiresAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EmailSenderVerificationResponse = EmailSenderVerificationResponse(
            verificationSent = json.requireBoolean("verificationSent"),
            email = json.optString("email"),
            expiresAt = json.optString("expiresAt")
        )
    }
}

/**
 * An API key account for email sending.
 *
 * @property account The account identifier.
 * @property createdAt Creation timestamp.
 * @property status The current status of the account.
 * @property verified Whether this account is verified.
 */
data class EmailApiKeyAccount(
    val account : String,
    val createdAt : String,
    val status : String,
    val verified : Boolean?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EmailApiKeyAccount = EmailApiKeyAccount(
            account = json.requireString("account"),
            createdAt = json.requireString("createdAt"),
            status = json.requireString("status"),
            verified = json.optBoolean("verified")
        )
    }
}

/**
 * A list of email API key accounts.
 *
 * @property accounts The list of email API key accounts.
 */
data class EmailApiKeyListResponse(
    val accounts : List<EmailApiKeyAccount>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EmailApiKeyListResponse = EmailApiKeyListResponse(
            accounts = json.optJsonList("accounts").map(EmailApiKeyAccount::fromJson)
        )
    }
}

/**
 * Response after creating an email API key.
 *
 * @property account The account identifier.
 * @property apiKey The generated API key.
 * @property createdAt Creation timestamp.
 * @property status The current status of the account.
 */
data class EmailApiKeyCreationResponse(
    val account : String,
    val apiKey : String,
    val createdAt : String,
    val status : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EmailApiKeyCreationResponse = EmailApiKeyCreationResponse(
            account = json.requireString("account"),
            apiKey = json.requireString("apiKey"),
            createdAt = json.requireString("createdAt"),
            status = json.requireString("status")
        )
    }
}

/**
 * A linked email sender identity.
 *
 * @property email The linked email address.
 * @property verified Whether this sender is verified.
 * @property createdAt Creation timestamp.
 */
data class LinkedSender(
    val email : String,
    val verified : Boolean,
    val createdAt : String
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LinkedSender = LinkedSender(
            email = json.requireString("email"),
            verified = json.requireBoolean("verified"),
            createdAt = json.requireString("createdAt")
        )
    }
}

/**
 * A paginated list of linked sender identities.
 *
 * @property senders The list of linked senders.
 * @property paging Pagination metadata.
 */
data class LinkedSendersResponse(
    val senders : List<LinkedSender>,
    val paging : PagingInfo
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LinkedSendersResponse = LinkedSendersResponse(
            senders = json.optJsonList("senders").map(LinkedSender::fromJson),
            paging = PagingInfo.fromJson(json.requireJson("paging"))
        )
    }
}

/**
 * Response model for deletion operations.
 *
 * @property deleted Whether deletion was successful.
 * @property account The account that was deleted.
 * @property key The key that was deleted.
 * @property templatesRemoved The number of templates removed.
 */
data class DeletionResponse(
    val deleted : Boolean,
    val account : String?,
    val key : String?,
    val templatesRemoved : Int?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : DeletionResponse = DeletionResponse(
            deleted = json.requireBoolean("deleted"),
            account = json.optString("account"),
            key = json.optString("key"),
            templatesRemoved = json.optInt("templatesRemoved")
        )
    }
}
