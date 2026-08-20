package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Represents a single version of a legal policy definition.
 *
 * A policy version tracks its commitment state (draft vs. published) and whether it is
 * currently the active version under which new agreements are accepted.
 *
 * @property id unique identifier for this policy version.
 * @property displayVersion a human-readable version label (e.g., "1.0", "2024.1").
 * @property isCommitted true if the version has been committed/published (cannot be edited).
 * @property isInEffect true if this is the currently active version used for new agreements.
 * @property localizedPolicyVersions per-locale overrides and translations of this version's content.
 */
data class PolicyVersionObject(
    val id: String,
    val displayVersion: String,
    val isCommitted: Boolean,
    val isInEffect: Boolean,
    val localizedPolicyVersions: List<LocalizedPolicyVersionObject>?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [PolicyVersionObject] from the API response JSON.
         *
         * @param json the raw JSON returned by the legal service.
         * @return a fully populated [PolicyVersionObject].
         * @throws IllegalArgumentException if a required field is absent or wrong type.
         */
        fun fromJson(json: Json): PolicyVersionObject {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return PolicyVersionObject(
                id = actualData.unsafeCast<Json>().requireString("id"),
                displayVersion = actualData.unsafeCast<Json>().requireString("displayVersion"),
                isCommitted = actualData.unsafeCast<Json>().requireBoolean("isCommitted"),
                isInEffect = actualData.unsafeCast<Json>().requireBoolean("isInEffect"),
                localizedPolicyVersions = actualData.unsafeCast<Json>()
                    .optJsonList("localizedPolicyVersions")
                    .map { LocalizedPolicyVersionObject.fromJson(it) }
            )
        }
    }
}

/**
 * Represents a locale-specific variation of a policy version.
 *
 * Each instance holds the translated or region-customized content for its locale,
 * along with metadata about whether it is the default selection for that locale.
 *
 * @property id unique identifier for this localized version entry.
 * @property localeCode the ISO 639-1 or locale string identifying the language variant (e.g., "en", "de-DE").
 * @property isDefaultSelection true if this is the default locale version when no explicit locale is requested.
 * @property description optional short description or summary of this policy variant.
 * @property contentType optional content type identifier, such as "TEXT", "HTML", "URL".
 */
data class LocalizedPolicyVersionObject(
    val id: String,
    val localeCode: String,
    val isDefaultSelection: Boolean?,
    val description: String?,
    val contentType: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [LocalizedPolicyVersionObject] from the API response JSON.
         *
         * @param json the raw JSON returned by the legal service.
         * @return a fully populated [LocalizedPolicyVersionObject].
         * @throws IllegalArgumentException if [id][LocalizedPolicyVersionObject.id] or
         *         [localeCode][LocalizedPolicyVersionObject.localeCode] is absent.
         */
        fun fromJson(json: Json): LocalizedPolicyVersionObject {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return LocalizedPolicyVersionObject(
                id = actualData.unsafeCast<Json>().requireString("id"),
                localeCode = actualData.unsafeCast<Json>().requireString("localeCode"),
                isDefaultSelection = actualData.unsafeCast<Json>().optBoolean("isDefaultSelection"),
                description = actualData.unsafeCast<Json>().optString("description"),
                contentType = actualData.unsafeCast<Json>().optString("contentType")
            )
        }
    }
}

/**
 * Represents a top-level legal policy and its version history.
 *
 * A policy is the container for one or more versions. The [policyType] distinguishes
 * the kind of policy (e.g., "TERMS_OF_SERVICE", "PRIVACY_POLICY").
 *
 * @property id unique identifier for this policy.
 * @property policyType the policy category/type string (e.g., "TERMS_OF_SERVICE").
 * @property policyVersions ordered or unordered list of all versions associated with this policy.
 */
data class PolicyObject(
    val id: String,
    val policyType: String,
    val policyVersions: List<PolicyVersionObject>?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [PolicyObject] from the API response JSON.
         *
         * @param json the raw JSON returned by the legal service.
         * @return a fully populated [PolicyObject].
         * @throws IllegalArgumentException if [id][PolicyObject.id] or [policyType][PolicyObject.policyType] is absent.
         */
        fun fromJson(json: Json): PolicyObject {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return PolicyObject(
                id = actualData.unsafeCast<Json>().requireString("id"),
                policyType = actualData.unsafeCast<Json>().requireString("policyType"),
                policyVersions = actualData.unsafeCast<Json>()
                    .optJsonList("policyVersions")
                    .map { PolicyVersionObject.fromJson(it) }
            )
        }
    }
}

/**
 * Represents a single user's acceptance record for a specific policy version.
 *
 * An agreement ties a user to a localized policy version they have (or have not) accepted,
 * along with the timestamp of acceptance if applicable.
 *
 * @property localizedPolicyVersion the localized version the user agreed to (always present).
 * @property policy the parent policy object, if the API includes it in the response.
 * @property isAccepted true if the user has accepted this version; false if not yet accepted; null if unknown.
 * @property acceptedAt ISO-8601 timestamp of acceptance, or null if not yet accepted.
 */
data class AgreementObject(
    val localizedPolicyVersion: LocalizedPolicyVersionObject,
    val policy: PolicyObject?,
    val isAccepted: Boolean?,
    val acceptedAt: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses an [AgreementObject] from the API response JSON.
         *
         * @param json the raw JSON returned by the legal service.
         * @return a fully populated [AgreementObject].
         * @throws IllegalArgumentException if [localizedPolicyVersion][AgreementObject.localizedPolicyVersion] is absent.
         */
        fun fromJson(json: Json): AgreementObject {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return AgreementObject(
                localizedPolicyVersion = LocalizedPolicyVersionObject.fromJson(
                    actualData.unsafeCast<Json>().requireJson("localizedPolicyVersion")
                ),
                policy = actualData.unsafeCast<Json>().optJson("policy")?.let { PolicyObject.fromJson(it) },
                isAccepted = actualData.unsafeCast<Json>().optBoolean("isAccepted"),
                acceptedAt = actualData.unsafeCast<Json>().optString("acceptedAt")
            )
        }
    }
}

/**
 * Response document for the bulk list of agreements returned for the current user.
 *
 * @property agreements list of [AgreementObject] records, one per applicable policy version.
 */
data class UserAgreementsResponse(
    val agreements: List<AgreementObject>
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [UserAgreementsResponse] from the API response JSON.
         *
         * @param json the raw JSON returned by the legal service.
         * @return a fully populated [UserAgreementsResponse].
         */
        fun fromJson(json: Json): UserAgreementsResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return UserAgreementsResponse(
                agreements = actualData.unsafeCast<Json>()
                    .optJsonList("agreements")
                    .map { AgreementObject.fromJson(it) }
            )
        }
    }
}

/**
 * Represents a policy version that includes its associated localized version object.
 *
 * Unlike [PolicyVersionObject], which holds a list of localizations, this compound object
 * holds a single [localizedVersion] — useful when the API returns the effective
 * localized content alongside the version metadata.
 *
 * @property id unique identifier for the policy version.
 * @property displayVersion a human-readable version label.
 * @property isCommitted true if the version has been committed/published.
 * @property isInEffect true if this is the currently active version.
 * @property localizedVersion the localized content paired with the version metadata.
 */
data class PolicyVersionWithLocalizedVersionObject(
    val id: String,
    val displayVersion: String,
    val isCommitted: Boolean,
    val isInEffect: Boolean,
    val localizedVersion: LocalizedPolicyVersionObject?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [PolicyVersionWithLocalizedVersionObject] from the API response JSON.
         *
         * @param json the raw JSON returned by the legal service.
         * @return a fully populated [PolicyVersionWithLocalizedVersionObject].
         * @throws IllegalArgumentException if [id][PolicyVersionWithLocalizedVersionObject.id],
         *         [displayVersion][PolicyVersionWithLocalizedVersionObject.displayVersion],
         *         [isCommitted][PolicyVersionWithLocalizedVersionObject.isCommitted],
         *         or [isInEffect][PolicyVersionWithLocalizedVersionObject.isInEffect] is absent.
         */
        fun fromJson(json: Json): PolicyVersionWithLocalizedVersionObject {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return PolicyVersionWithLocalizedVersionObject(
                id = actualData.unsafeCast<Json>().requireString("id"),
                displayVersion = actualData.unsafeCast<Json>().requireString("displayVersion"),
                isCommitted = actualData.unsafeCast<Json>().requireBoolean("isCommitted"),
                isInEffect = actualData.unsafeCast<Json>().requireBoolean("isInEffect"),
                localizedVersion = actualData.unsafeCast<Json>()
                    .optJson("localizedVersion")?.let { LocalizedPolicyVersionObject.fromJson(it) }
            )
        }
    }
}

/**
 * Response document describing a single user's eligibility and acceptance status for a policy.
 *
 * @property namespace the namespace the eligibility check was evaluated in.
 * @property countryCode the country code used for policy resolution.
 * @property policyId unique identifier for the policy.
 * @property policyName human-readable policy name.
 * @property policyType the policy type string (e.g., "TERMS_OF_SERVICE", "PRIVACY_POLICY").
 * @property isAccepted true if the user has accepted this policy.
 * @property isMandatory true if acceptance is required for the user to proceed.
 * @property policyVersions available versions for this policy.
 */
data class RetrieveUserEligibilitiesResponse(
    val namespace: String,
    val countryCode: String,
    val policyId: String,
    val policyName: String,
    val policyType: String,
    val isAccepted: Boolean,
    val isMandatory: Boolean,
    val policyVersions: List<PolicyVersionWithLocalizedVersionObject>?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [RetrieveUserEligibilitiesResponse] from the API response JSON.
         *
         * @param json the raw JSON returned by the legal service.
         * @return a fully populated [RetrieveUserEligibilitiesResponse].
         * @throws IllegalArgumentException if any required field is absent.
         */
        fun fromJson(json: Json): RetrieveUserEligibilitiesResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return RetrieveUserEligibilitiesResponse(
                namespace = actualData.unsafeCast<Json>().requireString("namespace"),
                countryCode = actualData.unsafeCast<Json>().requireString("countryCode"),
                policyId = actualData.unsafeCast<Json>().requireString("policyId"),
                policyName = actualData.unsafeCast<Json>().requireString("policyName"),
                policyType = actualData.unsafeCast<Json>().requireString("policyType"),
                isAccepted = actualData.unsafeCast<Json>().requireBoolean("isAccepted"),
                isMandatory = actualData.unsafeCast<Json>().requireBoolean("isMandatory"),
                policyVersions = actualData.unsafeCast<Json>()
                    .optJsonList("policyVersions")
                    .map { PolicyVersionWithLocalizedVersionObject.fromJson(it) }
            )
        }
    }
}

/**
 * Response document describing the user's accepted state for a specific localized policy version.
 *
 * @property id unique identifier for this acceptance record.
 * @property countryCode the country code used for policy resolution; null if a country group was used.
 * @property countryGroupName the country group name used for resolution; null if individual country was used.
 * @property isAccepted true if the user has accepted this version; null if state is unknown.
 * @property localizedPolicyVersion the localized version this record relates to (always present).
 */
data class RetrieveAcceptedAgreementResponse(
    val id: String,
    val countryCode: String?,
    val countryGroupName: String?,
    val isAccepted: Boolean?,
    val localizedPolicyVersion: LocalizedPolicyVersionObject
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [RetrieveAcceptedAgreementResponse] from the API response JSON.
         *
         * @param json the raw JSON returned by the legal service.
         * @return a fully populated [RetrieveAcceptedAgreementResponse].
         * @throws IllegalArgumentException if [id][RetrieveAcceptedAgreementResponse.id] or
         *         [localizedPolicyVersion][RetrieveAcceptedAgreementResponse.localizedPolicyVersion] is absent.
         */
        fun fromJson(json: Json): RetrieveAcceptedAgreementResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return RetrieveAcceptedAgreementResponse(
                id = actualData.unsafeCast<Json>().requireString("id"),
                countryCode = actualData.unsafeCast<Json>().optString("countryCode"),
                countryGroupName = actualData.unsafeCast<Json>().optString("countryGroupName"),
                isAccepted = actualData.unsafeCast<Json>().optBoolean("isAccepted"),
                localizedPolicyVersion = LocalizedPolicyVersionObject.fromJson(
                    actualData.unsafeCast<Json>().requireJson("localizedPolicyVersion")
                )
            )
        }
    }
}

/**
 * Simplified policy version with localized version inline — used in display contexts.
 *
 * Unlike [PolicyVersionWithLocalizedVersionObject], this variant is used in list/display
 * responses where the full nested version hierarchy is collapsed.
 *
 * @property id unique identifier for the policy version.
 * @property displayVersion a human-readable version label.
 * @property isCommitted true if the version has been committed/published.
 * @property isInEffect true if this is the currently active version.
 * @property localizedVersion the linked localized content, or null if absent.
 */
data class SimplePolicyVersionWithLocalizedVersionObject(
    val id: String,
    val displayVersion: String,
    val isCommitted: Boolean,
    val isInEffect: Boolean,
    val localizedVersion: LocalizedPolicyVersionObject?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [SimplePolicyVersionWithLocalizedVersionObject] from the API response JSON.
         *
         * @param json the raw JSON returned by the legal service.
         * @return a fully populated [SimplePolicyVersionWithLocalizedVersionObject].
         * @throws IllegalArgumentException if [id], [displayVersion], [isCommitted], or [isInEffect] is absent.
         */
        fun fromJson(json: Json): SimplePolicyVersionWithLocalizedVersionObject {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return SimplePolicyVersionWithLocalizedVersionObject(
                id = actualData.unsafeCast<Json>().requireString("id"),
                displayVersion = actualData.unsafeCast<Json>().requireString("displayVersion"),
                isCommitted = actualData.unsafeCast<Json>().requireBoolean("isCommitted"),
                isInEffect = actualData.unsafeCast<Json>().requireBoolean("isInEffect"),
                localizedVersion = actualData.unsafeCast<Json>()
                    .optJson("localizedVersion")?.let { LocalizedPolicyVersionObject.fromJson(it) }
            )
        }
    }
}

/**
 * A policy formatted for client-side display (simplified version list).
 *
 * @property id unique identifier for this policy.
 * @property policyType the policy category/type string.
 * @property displayName optional human-readable display name.
 * @property policyVersions the list of available versions for this policy.
 */
data class DisplayedPolicy(
    val id: String,
    val policyType: String,
    val displayName: String?,
    val policyVersions: List<SimplePolicyVersionWithLocalizedVersionObject>?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [DisplayedPolicy] from the API response JSON.
         *
         * @param json the raw JSON returned by the legal service.
         * @return a fully populated [DisplayedPolicy].
         * @throws IllegalArgumentException if [id][DisplayedPolicy.id] or
         *         [policyType][DisplayedPolicy.policyType] is absent.
         */
        fun fromJson(json: Json): DisplayedPolicy {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return DisplayedPolicy(
                id = actualData.unsafeCast<Json>().requireString("id"),
                policyType = actualData.unsafeCast<Json>().requireString("policyType"),
                displayName = actualData.unsafeCast<Json>().optString("displayName"),
                policyVersions = actualData.unsafeCast<Json>()
                    .optJsonList("policyVersions")
                    .map { SimplePolicyVersionWithLocalizedVersionObject.fromJson(it) }
            )
        }
    }
}

/**
 * Response document for listing all policies derived from a base policy.
 *
 * @property policies the list of resolved [DisplayedPolicy] instances.
 */
data class RetrievePoliciesFromBasePolicyResponse(
    val policies: List<DisplayedPolicy>
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [RetrievePoliciesFromBasePolicyResponse] from the API response JSON.
         *
         * @param json the raw JSON returned by the legal service.
         * @return a fully populated [RetrievePoliciesFromBasePolicyResponse].
         */
        fun fromJson(json: Json): RetrievePoliciesFromBasePolicyResponse {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return RetrievePoliciesFromBasePolicyResponse(
                policies = actualData.unsafeCast<Json>()
                    .optJsonList("policies")
                    .map { DisplayedPolicy.fromJson(it) }
            )
        }
    }
}

/**
 * Pagination cursor structure for paginated legal service responses.
 *
 * @property next a URL string for the next page, or null if already on the last page.
 * @property previous a URL string for the previous page, or null if already on the first page.
 */
data class LegalPaging(
    val next: String?,
    val previous: String?
) {
    companion object {
        /**
         * Parses a [LegalPaging] from the raw pagination object returned by the API.
         *
         * @param json the JSON object carrying the pagination cursor URLs.
         * @return an [LegalPaging]. Both cursors may be null on single-page responses.
         */
        fun fromJson(json: Json): LegalPaging = LegalPaging(
            next = json.optString("next"),
            previous = json.optString("previous")
        )
    }
}

/**
 * Request payload for a user to accept or decline a specific legal policy version.
 *
 * @property isAccepted must be true to record acceptance; false records explicit declination.
 * @property localizedPolicyVersionId the ID of the localized version being accepted.
 * @property policyId the ID of the parent policy this localized version belongs to.
 * @property policyVersionId the ID of the specific policy version.
 */
data class CreateUserPolicyAgreementRequest(
    val isAccepted: Boolean,
    val localizedPolicyVersionId: String,
    val policyId: String,
    val policyVersionId: String
) : AccelByteRequest {
    override fun toJson(): Json = jsonOf(
        "isAccepted" to isAccepted,
        "localizedPolicyVersionId" to localizedPolicyVersionId,
        "policyId" to policyId,
        "policyVersionId" to policyVersionId
    )
}