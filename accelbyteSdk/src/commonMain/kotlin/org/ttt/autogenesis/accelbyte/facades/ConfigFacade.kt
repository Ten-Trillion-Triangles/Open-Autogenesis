package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import kotlin.js.undefined
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.ConfigEntry
import org.ttt.autogenesis.accelbyte.models.ConfigListParams
import org.ttt.autogenesis.accelbyte.models.ConfigListResponse
import org.ttt.autogenesis.accelbyte.models.DeletionResponse
import org.ttt.autogenesis.accelbyte.models.EmailApiKeyCreationResponse
import org.ttt.autogenesis.accelbyte.models.EmailApiKeyListResponse
import org.ttt.autogenesis.accelbyte.models.EmailConfigParams
import org.ttt.autogenesis.accelbyte.models.EmailSenderConfigResponse
import org.ttt.autogenesis.accelbyte.models.EmailSenderVerificationResponse
import org.ttt.autogenesis.accelbyte.models.EmailTemplateListResponse
import org.ttt.autogenesis.accelbyte.models.EmailTemplatesUpdateResponse
import org.ttt.autogenesis.accelbyte.models.LinkedSendersParams
import org.ttt.autogenesis.accelbyte.models.LinkedSendersResponse
import org.ttt.autogenesis.accelbyte.models.ProfileConfigResponse
import org.ttt.autogenesis.accelbyte.models.PublicConfigListResponse
import org.ttt.autogenesis.accelbyte.modules.ConfigModulePackage
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Wraps Configuration and Email Sender management operations using the AccelByte configuration module.
 * Each method deserializes the underlying JSON payload into a dedicated response model for better type safety.
 *
 * @param sdk AccelByte SDK instance used to resolve configuration APIs.
 */
class ConfigFacade(private val sdk : AccelByteSdkInstance)
{
    private val module get() = ConfigModulePackage.Config

    private val accountProfileApi get() = module.AccountProfileConfigAdminApi(sdk.rawSdk)
    private val commonConfigAdmin get() = module.CommonConfigurationAdminApi(sdk.rawSdk)
    private val commonConfigPublic get() = module.CommonConfigurationApi(sdk.rawSdk)
    private val emailApiKeyAdmin get() = module.EmailSenderApiKeyAdminApi(sdk.rawSdk)
    private val emailConfigAdmin get() = module.EmailSenderConfigurationAdminApi(sdk.rawSdk)
    private val emailTemplatesAdmin get() = module.EmailSenderTemplateAdminApi(sdk.rawSdk)

    /**
     * Lists administrator-managed common configuration entries.
     *
     * @param params optional pagination/filtering parameters.
     * @return `Promise<ConfigListResponse>` containing configuration entries and paging metadata.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot list configuration entries
     * @throws ValidationException if the provided parameters are invalid
     */
    fun listCommonConfigs(params : ConfigListParams = ConfigListParams()) : Promise<ConfigListResponse> =
        commonConfigAdmin.getConfigs(params.toJson())
            .propagateJsErrors()
            .mapJson(ConfigListResponse::fromJson)

    /**
     * Creates a new common configuration entry.
     *
     * @param payload JSON payload defining the configuration entry.
     * @return `Promise<ConfigEntry>` describing the created entry.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if creation is forbidden
     * @throws ValidationException if the payload is invalid
     */
    fun createCommonConfig(payload : Json) : Promise<ConfigEntry> =
        commonConfigAdmin.createConfig(payload)
            .propagateJsErrors()
            .mapJson(ConfigEntry::fromJson)

    /**
     * Retrieves a single common configuration entry by key.
     *
     * @param key configuration key identifier.
     * @return `Promise<ConfigEntry>` containing the matching configuration entry.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if access to the key is forbidden
     * @throws ValidationException if the key is invalid or missing
     */
    fun getCommonConfig(key : String) : Promise<ConfigEntry> =
        commonConfigAdmin.getConfig_ByConfigKey(key)
            .propagateJsErrors()
            .mapJson(ConfigEntry::fromJson)

    /**
     * Deletes a common configuration entry by key.
     *
     * @param key configuration key identifier.
     * @return `Promise<DeletionResponse>` confirming the deletion result.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if deletion is forbidden
     * @throws ValidationException if the key is invalid
     */
    fun deleteCommonConfig(key : String) : Promise<DeletionResponse> =
        commonConfigAdmin.deleteConfig_ByConfigKey(key)
            .propagateJsErrors()
            .mapJson(DeletionResponse::fromJson)

    /**
     * Updates an existing common configuration entry.
     *
     * @param key configuration key identifier.
     * @param payload JSON payload describing updates.
     * @return `Promise<ConfigEntry>` representing the updated entry.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if updates are forbidden
     * @throws ValidationException if the payload or key is invalid
     */
    fun updateCommonConfig(key : String, payload : Json) : Promise<ConfigEntry> =
        commonConfigAdmin.patchConfig_ByConfigKey(key, payload)
            .propagateJsErrors()
            .mapJson(ConfigEntry::fromJson)

    /**
     * Retrieves publisher-scoped configuration metadata by key.
     *
     * @param key configuration key identifier.
     * @return `Promise<ConfigEntry>` containing publisher configuration data.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller lacks publisher access
     * @throws ValidationException if the key is invalid
     */
    fun getPublisherConfig(key : String) : Promise<ConfigEntry> =
        commonConfigAdmin.getPublisherConfig_ByConfigKey(key)
            .propagateJsErrors()
            .mapJson(ConfigEntry::fromJson)

    /**
     * Creates or updates account profile configuration data.
     *
     * @param payload JSON payload describing the account profile configuration.
     * @return `Promise<ProfileConfigResponse>` with the stored profile settings.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller lacks permission
     * @throws ValidationException if the payload is invalid
     */
    fun createAccountProfileConfig(payload : Json) : Promise<ProfileConfigResponse> =
        accountProfileApi.createConfigAccount(payload)
            .propagateJsErrors()
            .mapJson(ProfileConfigResponse::fromJson)

    /**
     * Lists public configuration entries accessible to clients.
     *
     * @return `Promise<PublicConfigListResponse>` with public configuration and paging metadata.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller is not allowed to view the configs
     */
    fun listPublicConfigs() : Promise<PublicConfigListResponse> =
        commonConfigPublic.getConfigs()
            .propagateJsErrors()
            .mapJson(PublicConfigListResponse::fromJson)

    /**
     * Lists configured email API keys for the namespace.
     *
     * @return `Promise<EmailApiKeyListResponse>` containing API key metadata.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot view email API keys
     */
    fun listEmailApiKeys() : Promise<EmailApiKeyListResponse> =
        emailApiKeyAdmin.getEmailsenderApikeysAccounts()
            .propagateJsErrors()
            .mapJson(EmailApiKeyListResponse::fromJson)

    /**
     * Adds a new namespace-level email API key.
     *
     * @param payload JSON payload that includes the key name and metadata.
     * @return `Promise<EmailApiKeyCreationResponse>` describing the created API key.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot create API keys
     * @throws ValidationException if the payload is invalid
     */
    fun addEmailApiKey(payload : Json) : Promise<EmailApiKeyCreationResponse> =
        emailApiKeyAdmin.createEmailsenderApikeyAccount(payload)
            .propagateJsErrors()
            .mapJson(EmailApiKeyCreationResponse::fromJson)

    /**
     * Removes an email API key scoped to an account.
     *
     * @param account account identifier owning the API key.
     * @return `Promise<DeletionResponse>` confirming the removal.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if deletion is forbidden
     * @throws ValidationException if the account identifier is invalid
     */
    fun removeEmailApiKey(account : String) : Promise<DeletionResponse> =
        emailApiKeyAdmin.deleteEmailsenderApikeyAccount_ByAccount(account)
            .propagateJsErrors()
            .mapJson(DeletionResponse::fromJson)

    /**
     * Retrieves linked senders for a namespace-scoped API key.
     *
     * @param account account identifier owning the API key.
     * @param params optional pagination/filter parameters.
     * @return `Promise<LinkedSendersResponse>` with the mapped senders list.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot list linked senders
     * @throws ValidationException if the request parameters are invalid
     */
    fun listLinkedSenders(account : String, params : LinkedSendersParams = LinkedSendersParams()) : Promise<LinkedSendersResponse> =
        emailApiKeyAdmin.getLinkedsendersApikeysEmailsender_ByAccount(account, params.toJson())
            .propagateJsErrors()
            .mapJson(LinkedSendersResponse::fromJson)

    /**
     * Retrieves current email sender configuration, optionally including templates.
     *
     * @param includeTemplates true to include template data, false to retrieve configuration only.
     * @return `Promise<EmailSenderConfigResponse>` with the sender configuration.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if access is forbidden
     */
    fun getEmailSender(includeTemplates : Boolean = false) : Promise<EmailSenderConfigResponse> =
        emailConfigAdmin
            .getEmailsender(
                if (includeTemplates) EmailConfigParams(includeTemplates).toJson() else undefined
            )
            .propagateJsErrors()
            .mapJson(EmailSenderConfigResponse::fromJson)

    /**
     * Updates the existing email sender configuration.
     *
     * @param payload JSON payload describing the configuration updates.
     * @return `Promise<EmailSenderConfigResponse>` with the updated configuration.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot modify the sender
     * @throws ValidationException if the payload is invalid
     */
    fun configureEmailSender(payload : Json) : Promise<EmailSenderConfigResponse> =
        emailConfigAdmin.patchEmailsender(payload)
            .propagateJsErrors()
            .mapJson(EmailSenderConfigResponse::fromJson)

    /**
     * Creates a new email sender configuration.
     *
     * @param payload JSON payload describing the email sender.
     * @return `Promise<EmailSenderConfigResponse>` with the created configuration.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if creation is forbidden
     * @throws ValidationException if the payload is invalid
     */
    fun createEmailSender(payload : Json) : Promise<EmailSenderConfigResponse> =
        emailConfigAdmin.createEmailsender(payload)
            .propagateJsErrors()
            .mapJson(EmailSenderConfigResponse::fromJson)

    /**
     * Deletes the current email sender configuration.
     *
     * @return `Promise<DeletionResponse>` confirming the deletion.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if deletion is forbidden
     */
    fun deleteEmailSender() : Promise<DeletionResponse> =
        emailConfigAdmin.deleteEmailsender()
            .propagateJsErrors()
            .mapJson(DeletionResponse::fromJson)

    /**
     * Verifies the configured email sender credentials.
     *
     * @return `Promise<EmailSenderVerificationResponse>` with verification metadata.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if verification is forbidden
     */
    fun verifyEmailSender() : Promise<EmailSenderVerificationResponse> =
        emailConfigAdmin.createEmailsenderAuthenticationVerify()
            .propagateJsErrors()
            .mapJson(EmailSenderVerificationResponse::fromJson)

    /**
     * Lists email templates assigned to the provided account.
     *
     * @param account account identifier owning the templates.
     * @return `Promise<EmailTemplateListResponse>` containing the template metadata.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if access to the account templates is forbidden
     */
    fun listEmailTemplates(account : String) : Promise<EmailTemplateListResponse> =
        emailTemplatesAdmin.getEmailtemplatesEmailsender_ByAccount(account)
            .propagateJsErrors()
            .mapJson(EmailTemplateListResponse::fromJson)

    /**
     * Updates the list of email templates for an account.
     *
     * @param account account identifier owning the templates.
     * @param templates array of template JSON objects.
     * @return `Promise<EmailTemplatesUpdateResponse>` with the updated templates metadata.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot modify the templates
     * @throws ValidationException if the template payload is invalid
     */
    fun updateEmailTemplates(account : String, templates : List<Json>) : Promise<EmailTemplatesUpdateResponse> =
        emailTemplatesAdmin.updateEmailtemplateEmailsender_ByAccount(account, templates.toTypedArray())
            .propagateJsErrors()
            .mapJson(EmailTemplatesUpdateResponse::fromJson)

    /**
     * Deletes all email templates for an account.
     *
     * @param account account identifier owning the templates.
     * @return `Promise<DeletionResponse>` confirming the removal.
     * @throws NetworkException on connection failures
     * @throws AuthenticationException if session token is invalid
     * @throws AuthorizationException if the caller cannot delete the templates
     */
    fun deleteEmailTemplates(account : String) : Promise<DeletionResponse> =
        emailTemplatesAdmin.deleteEmailtemplateEmailsender_ByAccount(account)
            .propagateJsErrors()
            .mapJson(DeletionResponse::fromJson)
}