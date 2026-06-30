package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * A localized policy version for a specific locale.
 *
 * @property id The unique identifier of this localized version.
 * @property localeCode The ISO locale code for this version.
 * @property isDefaultSelection Whether this is the default selection for its locale.
 * @property attachmentChecksum Checksum of the attached content.
 * @property attachmentLocation URL or location of the attached content.
 * @property attachmentVersionIdentifier Version identifier for the attachment.
 * @property contentType The content type of this version.
 * @property description A description of this localized version.
 * @property status The current status of this version.
 * @property publishedDate Date when this version was published.
 * @property createdAt Timestamp when this version was created.
 * @property updatedAt Timestamp when this version was last updated.
 */
data class LocalizedPolicyVersionResponse(
    val id : String,
    val localeCode : String,
    val isDefaultSelection : Boolean,
    val attachmentChecksum : String?,
    val attachmentLocation : String?,
    val attachmentVersionIdentifier : String?,
    val contentType : String?,
    val description : String?,
    val status : String?,
    val publishedDate : String?,
    val createdAt : String?,
    val updatedAt : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LocalizedPolicyVersionResponse = LocalizedPolicyVersionResponse(
            id = json.requireString("id"),
            localeCode = json.requireString("localeCode"),
            isDefaultSelection = json.requireBoolean("isDefaultSelection"),
            attachmentChecksum = json.optString("attachmentChecksum"),
            attachmentLocation = json.optString("attachmentLocation"),
            attachmentVersionIdentifier = json.optString("attachmentVersionIdentifier"),
            contentType = json.optString("contentType"),
            description = json.optString("description"),
            status = json.optString("status"),
            publishedDate = json.optString("publishedDate"),
            createdAt = json.optString("createdAt"),
            updatedAt = json.optString("updatedAt")
        )
    }
}

/**
 * A policy version with its localized versions.
 *
 * @property id The unique identifier of this policy version.
 * @property displayVersion The display version string.
 * @property isCommitted Whether this version has been committed.
 * @property isInEffect Whether this version is currently in effect.
 * @property localizedPolicyVersions List of localized versions for this policy version.
 * @property createdAt Timestamp when this version was created.
 * @property updatedAt Timestamp when this version was last updated.
 * @property description A description of this policy version.
 * @property publishedDate Date when this version was published.
 * @property status The current status of this policy version.
 */
data class LegalPolicyVersionResponse(
    val id : String,
    val displayVersion : String,
    val isCommitted : Boolean,
    val isInEffect : Boolean,
    val localizedPolicyVersions : List<LocalizedPolicyVersionResponse>?,
    val createdAt : String?,
    val updatedAt : String?,
    val description : String?,
    val publishedDate : String?,
    val status : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LegalPolicyVersionResponse = LegalPolicyVersionResponse(
            id = json.requireString("id"),
            displayVersion = json.requireString("displayVersion"),
            isCommitted = json.requireBoolean("isCommitted"),
            isInEffect = json.requireBoolean("isInEffect"),
            localizedPolicyVersions = json.optJsonList("localizedPolicyVersions").map(LocalizedPolicyVersionResponse::fromJson)
                .takeIf { it.isNotEmpty() },
            createdAt = json.optString("createdAt"),
            updatedAt = json.optString("updatedAt"),
            description = json.optString("description"),
            publishedDate = json.optString("publishedDate"),
            status = json.optString("status")
        )
    }
}

/**
 * A legal policy with its associated metadata.
 *
 * @property id The unique identifier of this policy.
 * @property policyName The display name of the policy.
 * @property policyType The type of policy.
 * @property namespace The namespace where this policy applies.
 * @property basePolicyId The base policy this policy derives from.
 * @property countryCode The country code for this policy.
 * @property countryGroupCode The country group code if using a group.
 * @property description A description of this policy.
 * @property createdAt Timestamp when this policy was created.
 * @property updatedAt Timestamp when this policy was last updated.
 * @property isMandatory Whether acceptance is mandatory.
 * @property isDefaultSelection Whether this is the default selection.
 * @property isDefaultOpted Whether this is opted in by default.
 * @property shouldNotifyOnUpdate Whether to notify on updates.
 * @property baseUrls List of base URLs for this policy.
 * @property policyVersions List of available versions for this policy.
 * @property readableId A human-readable identifier.
 * @property tags List of tags associated with this policy.
 */
data class LegalPolicyResponse(
    val id : String,
    val policyName : String,
    val policyType : String,
    val namespace : String,
    val basePolicyId : String,
    val countryCode : String,
    val countryGroupCode : String?,
    val description : String?,
    val createdAt : String?,
    val updatedAt : String?,
    val isMandatory : Boolean,
    val isDefaultSelection : Boolean,
    val isDefaultOpted : Boolean,
    val shouldNotifyOnUpdate : Boolean,
    val baseUrls : List<String>?,
    val policyVersions : List<LegalPolicyVersionResponse>?,
    val readableId : String?,
    val tags : List<String>?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LegalPolicyResponse = LegalPolicyResponse(
            id = json.requireString("id"),
            policyName = json.requireString("policyName"),
            policyType = json.requireString("policyType"),
            namespace = json.requireString("namespace"),
            basePolicyId = json.requireString("basePolicyId"),
            countryCode = json.requireString("countryCode"),
            countryGroupCode = json.optString("countryGroupCode"),
            description = json.optString("description"),
            createdAt = json.optString("createdAt"),
            updatedAt = json.optString("updatedAt"),
            isMandatory = json.requireBoolean("isMandatory"),
            isDefaultSelection = json.requireBoolean("isDefaultSelection"),
            isDefaultOpted = json.requireBoolean("isDefaultOpted"),
            shouldNotifyOnUpdate = json.requireBoolean("shouldNotifyOnUpdate"),
            baseUrls = json.optStringList("baseUrls"),
            policyVersions = json.optJsonList("policyVersions").map(LegalPolicyVersionResponse::fromJson)
                .takeIf { it.isNotEmpty() },
            readableId = json.optString("readableId"),
            tags = json.optStringList("tags").takeIf { it.isNotEmpty() }
        )
    }
}

/**
 * A list of legal policies.
 *
 * @property policies The list of policies.
 */
data class LegalPolicyListResponse(
    val policies : List<LegalPolicyResponse>
) : AccelByteResponse {
    companion object
    {
        fun fromJsonArray(jsonArray : Array<Json>) : LegalPolicyListResponse =
            LegalPolicyListResponse(jsonArray.map(LegalPolicyResponse::fromJson))
    }
}

/**
 * Response for an agreement confirmation request.
 *
 * @property comply Whether the user complied with the policy.
 * @property proceed Whether to proceed with the operation.
 */
data class AgreementConfirmationResponse(
    val comply : Boolean,
    val proceed : Boolean
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : AgreementConfirmationResponse = AgreementConfirmationResponse(
            comply = json.requireBoolean("comply"),
            proceed = json.requireBoolean("proceed")
        )
    }
}

/**
 * An accepted agreement record for a user.
 *
 * @property id The unique identifier of this acceptance record.
 * @property policyId The policy identifier.
 * @property policyName The policy name.
 * @property policyType The policy type.
 * @property namespace The namespace where this was accepted.
 * @property countryCode The country code when accepted.
 * @property countryGroupName The country group name when accepted.
 * @property userId The user who accepted.
 * @property signingDate The date of acceptance.
 * @property displayVersion The display version accepted.
 * @property tags Tags associated with this acceptance.
 * @property localizedPolicyVersion The localized version that was accepted.
 */
data class AcceptedAgreementResponse(
    val id : String,
    val policyId : String?,
    val policyName : String?,
    val policyType : String?,
    val namespace : String?,
    val countryCode : String?,
    val countryGroupName : String?,
    val userId : String?,
    val signingDate : String?,
    val displayVersion : String?,
    val tags : List<String>?,
    val localizedPolicyVersion : Json?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : AcceptedAgreementResponse = AcceptedAgreementResponse(
            id = json.requireString("id"),
            policyId = json.optString("policyId"),
            policyName = json.optString("policyName"),
            policyType = json.optString("policyType"),
            namespace = json.optString("namespace"),
            countryCode = json.optString("countryCode"),
            countryGroupName = json.optString("countryGroupName"),
            userId = json.optString("userId"),
            signingDate = json.optString("signingDate"),
            displayVersion = json.optString("displayVersion"),
            tags = json.optStringList("tags").takeIf { it.isNotEmpty() },
            localizedPolicyVersion = json.optJson("localizedPolicyVersion")
        )
    }
}

/**
 * A list of country codes for legal purposes.
 *
 * @property countries The list of country codes.
 */
data class LegalCountryListResponse(
    val countries : List<String>
) : AccelByteResponse {
    companion object
    {
        fun fromJsonArray(jsonArray : Array<Json>) : LegalCountryListResponse =
            LegalCountryListResponse(jsonArray.mapNotNull { it?.toString() })
    }
}

/**
 * Response indicating whether the legal service is ready.
 *
 * @property isReady Whether the service is ready.
 */
data class LegalReadinessResponse(
    val isReady : Boolean?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : LegalReadinessResponse = LegalReadinessResponse(
            isReady = json.optBoolean("isReady")
        )
    }
}
