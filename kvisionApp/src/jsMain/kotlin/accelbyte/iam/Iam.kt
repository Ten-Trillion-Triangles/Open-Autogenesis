package accelbyte.iam

import kotlinx.browser.window
import kotlin.js.js
import org.ttt.autogenesis.accelbyte.AccelByteAxiosConfiguration
import org.ttt.autogenesis.accelbyte.AccelByteCoreConfiguration
import org.ttt.autogenesis.accelbyte.AccelByteInterceptorDefinition
import org.ttt.autogenesis.accelbyte.AccelByteInterceptorType
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.AccelByteSdkFactory
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.AccelByteSdkSettings
import org.ttt.autogenesis.accelbyte.AxiosRequestConfig
import org.ttt.autogenesis.accelbyte.AxiosResponse
import org.ttt.autogenesis.config.ConfigSource
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Builds and configures the AccelByte SDK instance with authentication interceptors.
 *
 * @return Configured AccelByteSdkInstance.
 */
fun buildSdk() : AccelByteSdkInstance
{
    val clientId: String = ConfigSource.property("kvision-iam.local.properties", "kvision.clientId")
    val baseUrl: String = ConfigSource.property("kvision-iam.local.properties", "kvision.baseUrl")

    // Create interceptor to add Basic Auth header for OAuth token requests
    val authInterceptor = AccelByteInterceptorDefinition(
        type = AccelByteInterceptorType.REQUEST,
        name = "oauth-basic-auth",
        onRequest = { config: AxiosRequestConfig ->
            val dynamicConfig = config.unsafeCast<dynamic>()
            
            Logger.debug(LogCategory.AUTH, "DEBUG: [Publisher] Interceptor called for URL: ${dynamicConfig.url}")
            
            // Add Basic Auth header for OAuth token endpoint
            if(dynamicConfig.url.toString().contains("/oauth/token"))
            {
                // Create Base64-encoded "clientId:" (no secret for public client)
                val credentials = "$clientId:"
                val base64 = window.btoa(credentials)
                
                // Add Authorization header
                val headers = dynamicConfig.headers ?: js("{}")
                headers["Authorization"] = "Basic $base64"
                dynamicConfig.headers = headers
                
                // Convert URLSearchParams to readable string
                val dataString = if(dynamicConfig.data != null)
                {
                    val params = dynamicConfig.data.unsafeCast<dynamic>()
                    params.toString() as String // URLSearchParams.toString() gives "key=value&key2=value2"
                }
                else
                {
                    "null"
                }
                
                Logger.debug(LogCategory.AUTH, "DEBUG: [Publisher] Added Basic Auth Header")
                Logger.debug(LogCategory.AUTH, "DEBUG: [Publisher] OAuth Request Headers: ${JSON.stringify(headers)}")
                Logger.debug(LogCategory.AUTH, "DEBUG: [Publisher] OAuth Request Data: $dataString")
                Logger.debug(LogCategory.AUTH, "DEBUG: [Publisher] OAuth Request URL: ${dynamicConfig.url}")
            }
            config
        }
    )
    
    // Create response interceptor to log all responses
    val responseInterceptor = AccelByteInterceptorDefinition(
        type = AccelByteInterceptorType.RESPONSE,
        name = "response-logger",
        onSuccess = { response: AxiosResponse ->
            val dynamicResponse = response.unsafeCast<dynamic>()
            val url = dynamicResponse.config?.url?.toString() ?: "unknown"
            Logger.debug(LogCategory.AUTH, "DEBUG: [Publisher] Request success - URL: $url Status: ${dynamicResponse.status}")
            if(url.contains("/oauth/token") || url.contains("/code/request") || url.contains("/users"))
            {
                Logger.debug(LogCategory.AUTH, "DEBUG: [Publisher] Response Data: ${JSON.stringify(dynamicResponse.data)}")
            }
            response
        },
        onError = { error: dynamic ->
            val response = error.response
            val url = error.config?.url?.toString() ?: "unknown"
            Logger.error(LogCategory.AUTH, "DEBUG: [Publisher] Request failed - URL: $url")
            if(response != null)
            {
                Logger.error(LogCategory.AUTH, "DEBUG: [Publisher] Error Status: ${response.status}")
                Logger.error(LogCategory.AUTH, "DEBUG: [Publisher] Error Data: ${JSON.stringify(response.data)}")
                Logger.error(LogCategory.AUTH, "DEBUG: [Publisher] Error Message: ${error.message}")
            }
            else
            {
                Logger.error(LogCategory.AUTH, "DEBUG: [Publisher] Network error: ${error.message}")
            }
            throw error
        }
    )
    
    Logger.debug(LogCategory.AUTH, "DEBUG: [buildSdk] Creating original SDK instance")
    val sdk = AccelByteSdkFactory.create(AccelByteSdkSettings(
        coreConfig = AccelByteCoreConfiguration(
            clientId = clientId,
            redirectUri = "http://127.0.0.1",
            baseUrl =  baseUrl,
            namespace = ConfigSource.property("kvision-iam.local.properties", "kvision.namespace"),
            useSchemaValidation = true
        ),
        axiosConfig = AccelByteAxiosConfiguration(
            interceptors = listOf(authInterceptor, responseInterceptor)
        )
    ))

    // Create CloudSave interceptor that captures the SDK instance in closure
    val cloudSaveBearerInterceptor = AccelByteInterceptorDefinition(
        type = AccelByteInterceptorType.REQUEST,
        name = "cloudsave-bearer",
        onRequest = fun(config: AxiosRequestConfig): dynamic {
            val dynamicConfig = config.unsafeCast<dynamic>()
            val requestUrl = dynamicConfig.url?.toString()
            
            Logger.debug(LogCategory.AUTH, "DEBUG: [CloudSave] Interceptor called for URL: $requestUrl")
            
            if(requestUrl == null || !requestUrl.contains("/cloudsave/"))
            {
                Logger.debug(LogCategory.AUTH, "DEBUG: [CloudSave] Skipping non-cloudsave URL")
                return config
            }

            // Get token from the captured SDK instance
            Logger.debug(LogCategory.AUTH, "DEBUG: [CloudSave] About to call getToken() on captured SDK")
            val tokenConfig = sdk.getToken()
            Logger.debug(LogCategory.AUTH, "DEBUG: [CloudSave] Got tokenConfig: $tokenConfig")
            Logger.debug(LogCategory.AUTH, "DEBUG: [CloudSave] TokenConfig accessToken: ${tokenConfig.accessToken}")
            
            val token = tokenConfig.accessToken
            Logger.debug(LogCategory.AUTH, "DEBUG: [CloudSave] Final token value: $token")
            Logger.debug(LogCategory.AUTH, "DEBUG: [CloudSave] Token isNullOrBlank: ${token.isNullOrBlank()}")
            
            if(token.isNullOrBlank())
            {
                Logger.debug(LogCategory.AUTH, "DEBUG: [CloudSave] No token available, skipping Bearer auth")
                return config
            }

            val headers = dynamicConfig.headers ?: js("{}")
            Logger.debug(LogCategory.AUTH, "DEBUG: [CloudSave] Current headers: ${JSON.stringify(headers)}")
            
            if(headers["Authorization"] == null)
            {
                headers["Authorization"] = "Bearer $token"
                dynamicConfig.headers = headers
                Logger.debug(LogCategory.AUTH, "DEBUG: [CloudSave] Added Bearer token for URL: $requestUrl")
                Logger.debug(LogCategory.AUTH, "DEBUG: [CloudSave] Updated headers: ${JSON.stringify(headers)}")
            }
            else
            {
                Logger.debug(LogCategory.AUTH, "DEBUG: [CloudSave] Authorization header already present: ${headers["Authorization"]}")
            }
            return config
        }
    )

    // Create the final SDK with the CloudSave interceptor
    Logger.debug(LogCategory.AUTH, "DEBUG: [buildSdk] Original SDK instance: $sdk")
    val finalSdk = sdk.addInterceptors(cloudSaveBearerInterceptor)
    Logger.debug(LogCategory.AUTH, "DEBUG: [buildSdk] Final SDK instance with CloudSave interceptor: $finalSdk")
    
    return finalSdk
}



fun buildGlobalSdk() : AccelByteSdkInstance
{
    val clientId: String = ConfigSource.property("kvision-global.local.properties", "kvision.clientId")
    
    // Create interceptor to add Basic Auth header for OAuth token requests
    val authInterceptor = AccelByteInterceptorDefinition(
        type = AccelByteInterceptorType.REQUEST,
        name = "oauth-basic-auth",
        onRequest = { config: AxiosRequestConfig ->
            val dynamicConfig = config.unsafeCast<dynamic>()
            
            Logger.debug(LogCategory.AUTH, "DEBUG: [Global] Interceptor called for URL: ${dynamicConfig.url}")
            
            // Add Basic Auth header for OAuth token endpoint
            if (dynamicConfig.url.toString().contains("/oauth/token")) {
                // Create Base64-encoded "clientId:" (no secret for public client)
                val credentials = "$clientId:"
                val base64 = window.btoa(credentials)
                
                // Add Authorization header
                val headers = dynamicConfig.headers ?: js("{}")
                headers["Authorization"] = "Basic $base64"
                dynamicConfig.headers = headers
                
                Logger.debug(LogCategory.AUTH, "DEBUG: [Global] Added Basic Auth Header")
                Logger.debug(LogCategory.AUTH, "DEBUG: [Global] OAuth Request Headers: ${JSON.stringify(headers)}")
                Logger.debug(LogCategory.AUTH, "DEBUG: [Global] OAuth Request Data: ${dynamicConfig.data}")
            }
            config
        },
        onSuccess = { response: AxiosResponse ->
            val dynamicResponse = response.unsafeCast<dynamic>()
            if (dynamicResponse.config?.url?.toString()?.contains("/oauth/token") == true) {
                Logger.debug(LogCategory.AUTH, "DEBUG: [Global] OAuth Success - Status: ${dynamicResponse.status}")
                Logger.debug(LogCategory.AUTH, "DEBUG: [Global] OAuth Success - Data: ${JSON.stringify(dynamicResponse.data)}")
            }
            response
        },
        onError = { error: dynamic ->
            val response = error.response
            if (response != null) {
                Logger.error(LogCategory.AUTH, "DEBUG: [Global] OAuth Error - Status: ${response.status}")
                Logger.error(LogCategory.AUTH, "DEBUG: [Global] OAuth Error - Data: ${JSON.stringify(response.data)}")
                Logger.error(LogCategory.AUTH, "DEBUG: [Global] OAuth Error - Message: ${error.message}")
            } else {
                Logger.error(LogCategory.AUTH, "DEBUG: [Global] Network error: ${error.message}")
            }
            throw error
        }
    )
    
    return AccelByteSdkFactory.create(AccelByteSdkSettings(
        coreConfig = AccelByteCoreConfiguration(
            clientId = clientId,
            redirectUri = "http://127.0.0.1",
            baseUrl =  ConfigSource.property("kvision-global.local.properties", "kvision.baseUrl"),
            namespace = ConfigSource.property("kvision-global.local.properties", "kvision.namespace"),
            useSchemaValidation = true
        ),
        axiosConfig = AccelByteAxiosConfiguration(
            interceptors = listOf(authInterceptor)
        )
    ))
}