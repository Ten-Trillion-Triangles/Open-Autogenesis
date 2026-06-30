package accelbyte

import net.accelbyte.sdk.core.AccelByteConfig
import net.accelbyte.sdk.core.AccelByteSDK
import net.accelbyte.sdk.core.repository.OnDemandTokenRefreshOptions
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.storage.MasterRecordStorage

/**
 * Provides singleton access to AccelByte SDK instance and namespace configuration.
 */
object AccelByteSdkProvider
{
    /** AccelByte SDK instance with on-demand token refresh enabled */
    val sdk : AccelByteSDK by lazy {
        Logger.info(LogCategory.SYSTEM, "Initializing AccelByte SDK instance...")
        val config = AccelByteConfig.getDefault().useOnDemandTokenRefresh()
        val rawBaseUrl = config.configRepository.getBaseURL()
        val sanitizedBaseUrl = UrlValidator.sanitizeUrl(rawBaseUrl)
        Logger.debug(LogCategory.SYSTEM, "AccelByte SDK config base URL: '$sanitizedBaseUrl' (raw='$rawBaseUrl')")
        
        // Validate the base URL before creating the SDK
        if (!UrlValidator.validateAndLogUrl("AccelByte SDK", sanitizedBaseUrl)) {
            error("Invalid AccelByte SDK base URL configuration: '$sanitizedBaseUrl'")
        }
        
        Logger.info(LogCategory.SYSTEM, "Creating AccelByteSDK with validated URL: $sanitizedBaseUrl")
        AccelByteSDK(config).also { sdkInstance ->
            // Authenticate with client credentials for admin operations
            Logger.info(LogCategory.SYSTEM, "Attempting AccelByte client login (client_id=${System.getenv("AB_CLIENT_ID") ?: "not set"})...")
            val loginResult = runCatching { sdkInstance.loginClient() }
            
            if (loginResult.isFailure) {
                Logger.error(LogCategory.SYSTEM, "AccelByte client login exception: ${loginResult.exceptionOrNull()?.message}")
            }
            
            val loginSuccess = loginResult.getOrDefault(false)
            if (!loginSuccess) {
                Logger.warn(LogCategory.SYSTEM, "Failed to authenticate AccelByte SDK with client credentials (login returned false)")
            } else {
                Logger.info(LogCategory.SYSTEM, "AccelByte SDK authenticated successfully with client credentials")
                
                // Configure MasterRecordStorage with SDK details
                configureMasterRecordStorage(sdkInstance, sanitizedBaseUrl)
            }
        }
    }

    /** AccelByte namespace from AB_NAMESPACE environment variable or system property */
    val namespace : String by lazy {
        val ns = System.getenv("AB_NAMESPACE")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("AB_NAMESPACE")?.takeIf { it.isNotBlank() }
            ?: error("Environment variable AB_NAMESPACE must be defined before calling AccelByte IAM APIs.")
        Logger.info(LogCategory.SYSTEM, "AccelByte namespace resolved: '$ns'")
        ns
    }
    
    private fun configureMasterRecordStorage(sdk: AccelByteSDK, baseUrl: String) {
        try {
            Logger.debug(LogCategory.SYSTEM, "Configuring MasterRecordStorage for namespace: $namespace")
            val token = sdk.sdkConfiguration.tokenRepository.token as String
            Logger.debug(LogCategory.SYSTEM, "Retrieved access token for MasterRecordStorage (length=${token.length})")
            
            // Configure MasterRecordStorage with connection details
            MasterRecordStorage.configure(
                baseUrl = baseUrl,
                namespace = namespace,
                accessToken = token
            )
            
            Logger.info(LogCategory.SYSTEM, "MasterRecordStorage configured successfully with AccelByte credentials")
        } catch (e: Exception) {
            Logger.error(LogCategory.SYSTEM, "Failed to configure MasterRecordStorage: ${e.message}")
        }
    }
}
