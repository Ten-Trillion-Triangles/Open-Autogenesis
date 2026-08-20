@file:JsModule("@accelbyte/sdk-legal")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object LegalModulePackage {
    val Legal: LegalNamespace
}

external interface LegalNamespace {
    val AgreementApi: AgreementApiFactory
    val PoliciesApi: PoliciesApiFactory
    val UtilityApi: UtilityApiFactory
}

external interface AgreementApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): AgreementApi
}

external interface AgreementApi {
    fun getAgreementsPolicies(): Promise<Json>
    fun createAgreementPolicy(data: Array<Json>): Promise<Json>
    fun createAgreementPolicyUser_ByUserId(userId: String, data: Array<Json>): Promise<Json>
    fun patchAgreementLocalizedPolicyVersionPreference(data: Array<Json>): Promise<Json>
    fun createAgreementLocalizedPolicyVersion_ByLocalizedPolicyVersionId(localizedPolicyVersionId: String): Promise<Json>
    fun createUserPolicyAgreement_ByCountryCode_ByClientId_ByUserId(
        countryCode: String,
        clientId: String,
        userId: String,
        data: Array<Json>
    ): Promise<Json>
}

external interface PoliciesApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): PoliciesApi
}

external interface PoliciesApi {
    fun getPoliciesCountriesList(): Promise<Json>
    fun getPolicy_ByNamespace(queryParams: Json? = definedExternally): Promise<Json>
    fun getPolicyCountry_ByCountryCode(countryCode: String, queryParams: Json? = definedExternally): Promise<Json>
    fun getPolicyCountry_ByCountryCode_ByNS(countryCode: String, queryParams: Json? = definedExternally): Promise<Json>
}

external interface UtilityApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): UtilityApi
}

external interface UtilityApi {
    fun getReadiness(): Promise<Json>
}