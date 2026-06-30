@file:JsModule("@accelbyte/sdk-config")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object ConfigModulePackage {
    val Config: ConfigNamespace
}

external interface ConfigNamespace {
    val AccountProfileConfigAdminApi: AccountProfileConfigAdminApiFactory
    val CommonConfigurationAdminApi: CommonConfigurationAdminApiFactory
    val CommonConfigurationApi: CommonConfigurationApiFactory
    val EmailSenderApiKeyAdminApi: EmailSenderApiKeyAdminApiFactory
    val EmailSenderConfigurationAdminApi: EmailSenderConfigurationAdminApiFactory
    val EmailSenderTemplateAdminApi: EmailSenderTemplateAdminApiFactory
}

external interface AccountProfileConfigAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): AccountProfileConfigAdminApi
}

external interface AccountProfileConfigAdminApi {
    fun createConfigAccount(data: Json): Promise<Json>
    fun getConfigAccount_ByConfigKey(configKey: String): Promise<Json>
    fun updateConfigAccount_ByConfigKey(configKey: String, data: Json): Promise<Json>
}

external interface CommonConfigurationAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): CommonConfigurationAdminApi
}

external interface CommonConfigurationAdminApi {
    fun getConfigs(queryParams: Json? = definedExternally): Promise<Json>
    fun createConfig(data: Json): Promise<Json>
    fun getConfig_ByConfigKey(configKey: String): Promise<Json>
    fun deleteConfig_ByConfigKey(configKey: String): Promise<Json>
    fun patchConfig_ByConfigKey(configKey: String, data: Json): Promise<Json>
    fun getPublisherConfig_ByConfigKey(configKey: String): Promise<Json>
}

external interface CommonConfigurationApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): CommonConfigurationApi
}

external interface CommonConfigurationApi {
    fun getConfigs(): Promise<Json>
    fun getConfig_ByConfigKey(configKey: String): Promise<Json>
}

external interface EmailSenderApiKeyAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): EmailSenderApiKeyAdminApi
}

external interface EmailSenderApiKeyAdminApi {
    fun getEmailsenderApikeysAccounts(): Promise<Json>
    fun createEmailsenderApikeyAccount(data: Json): Promise<Json>
    fun deleteEmailsenderApikeyAccount_ByAccount(account: String): Promise<Json>
    fun getLinkedsendersApikeysEmailsender_ByAccount(account: String, queryParams: Json? = definedExternally): Promise<Json>
}

external interface EmailSenderConfigurationAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): EmailSenderConfigurationAdminApi
}

external interface EmailSenderConfigurationAdminApi {
    fun getEmailsender(queryParams: Json? = definedExternally): Promise<Json>
    fun createEmailsender(data: Json): Promise<Json>
    fun deleteEmailsender(): Promise<Json>
    fun patchEmailsender(data: Json): Promise<Json>
    fun getEmailsenderAuthentication(): Promise<Json>
    fun createEmailsenderAuthenticationVerify(): Promise<Json>
}

external interface EmailSenderTemplateAdminApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): EmailSenderTemplateAdminApi
}

external interface EmailSenderTemplateAdminApi {
    fun getEmailtemplatesEmailsender_ByAccount(account: String): Promise<Json>
    fun updateEmailtemplateEmailsender_ByAccount(account: String, data: Array<Json>): Promise<Json>
    fun deleteEmailtemplateEmailsender_ByAccount(account: String): Promise<Json>
}
