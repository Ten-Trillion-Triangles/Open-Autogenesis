package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.AcceptedAgreementResponse
import org.ttt.autogenesis.accelbyte.models.AgreementConfirmationResponse
import org.ttt.autogenesis.accelbyte.models.LegalCountryListResponse
import org.ttt.autogenesis.accelbyte.models.LegalPolicyListResponse
import org.ttt.autogenesis.accelbyte.models.LegalReadinessResponse
import org.ttt.autogenesis.accelbyte.modules.LegalModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.mapJsonList
import org.ttt.autogenesis.accelbyte.util.mapToUnit
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * A thin facade over the AccelByte Legal module. Provides operations for retrieving, accepting,
 * and managing legal policy agreements that govern user consent. All operations are admin-gated
 * or user-scoped depending on the endpoint.
 */
class LegalFacade(private val sdk : AccelByteSdkInstance)
{
    private val module get() = LegalModulePackage.Legal

    private val agreementApi get() = module.AgreementApi(sdk.rawSdk)
    private val policiesApi get() = module.PoliciesApi(sdk.rawSdk)
    private val utilityApi get() = module.UtilityApi(sdk.rawSdk)

    /**
     * Returns the full list of policy agreements applicable to the current user, including whether
     * each has been accepted. Call this before showing any consent UI to determine which policies
     * still require acceptance.
     *
     * @return list of [AcceptedAgreementResponse] records
     */
    fun fetchAcceptedAgreements() : Promise<List<AcceptedAgreementResponse>> =
        agreementApi.getAgreementsPolicies()
            .propagateJsErrors()
            .mapJsonList(AcceptedAgreementResponse::fromJson)

    /**
     * Accepts or declines a batch of policy agreements on behalf of the current user. Typically
     * used to record consent after a user has read and agreed to one or more policies.
     *
     * @param agreements list of JSON policy document objects forwarded from the SDK
     * @return the backend confirmation response
     */
    fun acceptAgreements(agreements : List<Json>) : Promise<AgreementConfirmationResponse> =
        agreementApi.createAgreementPolicy(agreements.toTypedArray())
            .propagateJsErrors()
            .mapJson(AgreementConfirmationResponse::fromJson)

    /**
     * Admin-scoped version of [acceptAgreements]: accepts or declines agreement(s) for a
     * specific user by ID.
     *
     * @param userId target user
     * @param agreements list of policy agreement JSON objects
     * @return confirmation
     */
    fun acceptAgreementsForUser(userId : String, agreements : List<Json>) : Promise<AgreementConfirmationResponse> =
        agreementApi.createAgreementPolicyUser_ByUserId(userId, agreements.toTypedArray())
            .propagateJsErrors()
            .mapJson(AgreementConfirmationResponse::fromJson)

    /**
     * Updates the user's localized preference for marketing communication policies. Only affects
     * policies tagged as marketing-related.
     *
     * @param payload list of JSON preference objects
     * @return Promise<Unit>
     */
    fun updateMarketingPreferences(payload : List<Json>) : Promise<Unit> =
        agreementApi.patchAgreementLocalizedPolicyVersionPreference(payload.toTypedArray())
            .propagateJsErrors()
            .mapToUnit()

    /**
     * Accepts a single localized policy version by its ID. Use after retrieving available
     * policies to get the appropriate localizedVersionId.
     *
     * @param localizedPolicyVersionId the ID of the specific translated policy version to accept
     * @return Promise<Unit>
     * @throws on network or validation errors
     */
    fun acceptLocalizedVersion(localizedPolicyVersionId : String) : Promise<Unit> =
        agreementApi.createAgreementLocalizedPolicyVersion_ByLocalizedPolicyVersionId(localizedPolicyVersionId)
            .propagateJsErrors()
            .mapToUnit()

    /**
     * Full admin accept flow: accepts a specific policy for a user identified by country code
     * and client ID.
     *
     * @param countryCode ISO country
     * @param clientId the OAuth client ID
     * @param userId target user
     * @param payload policy acceptance list
     * @return confirmation
     */
    fun acceptUserPolicy(
        countryCode : String,
        clientId : String,
        userId : String,
        payload : List<Json>
    ) : Promise<AgreementConfirmationResponse> =
        agreementApi.createUserPolicyAgreement_ByCountryCode_ByClientId_ByUserId(
            countryCode,
            clientId,
            userId,
            payload.toTypedArray()
        ).propagateJsErrors()
            .mapJson(AgreementConfirmationResponse::fromJson)

    /**
     * Returns the list of countries covered by the platform's policy configuration.
     *
     * @return [LegalCountryListResponse]
     */
    fun listPolicyCountries() : Promise<LegalCountryListResponse> =
        policiesApi.getPoliciesCountriesList()
            .propagateJsErrors()
            .mapJson { json ->
            LegalCountryListResponse.fromJsonArray((json as? Array<Json>) ?: emptyArray())
        }

    /**
     * Lists all published policies in the current namespace, optionally filtered.
     *
     * @param filter raw JSON filter object passed to the API
     * @return [LegalPolicyListResponse]
     */
    fun listNamespacePolicies(filter : Json? = null) : Promise<LegalPolicyListResponse> =
        policiesApi.getPolicy_ByNamespace(filter)
            .propagateJsErrors()
            .mapJson { json ->
            LegalPolicyListResponse.fromJsonArray((json as? Array<Json>) ?: emptyArray())
        }

    /**
     * Lists all policies that apply to a given country code.
     *
     * @param countryCode ISO country code
     * @param filter optional JSON filter
     * @return [LegalPolicyListResponse]
     */
    fun listCountryPolicies(countryCode : String, filter : Json? = null) : Promise<LegalPolicyListResponse> =
        policiesApi.getPolicyCountry_ByCountryCode(countryCode, filter)
            .propagateJsErrors()
            .mapJson { json ->
            LegalPolicyListResponse.fromJsonArray((json as? Array<Json>) ?: emptyArray())
        }

    /**
     * Lists all policies scoped to both country and namespace.
     *
     * @param countryCode ISO country
     * @param filter optional JSON filter
     * @return [LegalPolicyListResponse]
     */
    fun listCountryNamespacePolicies(countryCode : String, filter : Json? = null) : Promise<LegalPolicyListResponse> =
        policiesApi.getPolicyCountry_ByCountryCode_ByNS(countryCode, filter)
            .propagateJsErrors()
            .mapJson { json ->
            LegalPolicyListResponse.fromJsonArray((json as? Array<Json>) ?: emptyArray())
        }

    /**
     * Pings the legal service to verify readiness. Returns a [LegalReadinessResponse] indicating
     * whether legal services are online and fully configured.
     *
     * @return the readiness status
     */
    fun checkReadiness() : Promise<LegalReadinessResponse> =
        utilityApi.getReadiness()
            .propagateJsErrors()
            .mapJson(LegalReadinessResponse::fromJson)


}
