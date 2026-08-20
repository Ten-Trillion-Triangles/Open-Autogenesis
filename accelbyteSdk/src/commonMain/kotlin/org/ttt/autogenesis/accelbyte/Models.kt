package org.ttt.autogenesis.accelbyte

import kotlin.js.js

/**
 * Core configuration settings for AccelByte SDK initialization.
 * 
 * This data class contains the essential configuration parameters required
 * to initialize and connect to AccelByte services. All parameters except
 * useSchemaValidation are mandatory for proper SDK operation.
 * 
 * @param clientId Unique identifier for the client application
 * @param redirectUri URI where authentication responses will be redirected
 * @param baseUrl Base URL for AccelByte service endpoints
 * @param namespace AccelByte namespace for the application
 * @param useSchemaValidation Optional flag to enable/disable schema validation
 */
data class AccelByteCoreConfiguration(
    val clientId : String,
    val redirectUri : String,
    val baseUrl : String,
    val namespace : String,
    val useSchemaValidation : Boolean? = null
) 
{
    /**
     * Converts this configuration to the external JavaScript format.
     * 
     * This internal method transforms the Kotlin data class into the
     * JavaScript object format expected by the underlying AccelByte SDK.
     * 
     * @return [CoreConfig] JavaScript configuration object
     */
    internal fun toExternal() : CoreConfig = buildJs {
        clientId = this@AccelByteCoreConfiguration.clientId
        redirectURI = this@AccelByteCoreConfiguration.redirectUri
        baseURL = this@AccelByteCoreConfiguration.baseUrl
        namespace = this@AccelByteCoreConfiguration.namespace
        useSchemaValidation = this@AccelByteCoreConfiguration.useSchemaValidation
    }
}

/**
 * Configuration for Axios HTTP client used by the AccelByte SDK.
 * 
 * This configuration allows customization of the HTTP client behavior,
 * including request interceptors and default request settings. Both
 * parameters are optional and will use SDK defaults if not specified.
 * 
 * @param interceptors Optional list of request/response interceptors
 * @param request Optional default Axios request configuration
 */
data class AccelByteAxiosConfiguration(
    val interceptors : List<AccelByteInterceptorDefinition>? = null,
    val request : AxiosRequestConfig? = null
) 
{
    /**
     * Converts this configuration to the external JavaScript format.
     * 
     * This internal method transforms the Kotlin data class into the
     * JavaScript object format expected by the underlying AccelByte SDK.
     * 
     * @return [AxiosConfig] JavaScript Axios configuration object
     */
    internal fun toExternal() : AxiosConfig = buildJs {
        interceptors = this@AccelByteAxiosConfiguration.interceptors?.map { it.toExternal() }?.toTypedArray()
        request = this@AccelByteAxiosConfiguration.request
    }
}

data class AccelByteWebSocketConfiguration(
    val allowReconnect: Boolean? = null,
    val maxReconnectAttempts: Int? = null
) {
    internal fun toExternal(): WebSocketConfig = buildJs {
        allowReconnect = this@AccelByteWebSocketConfiguration.allowReconnect
        maxReconnectAttempts = this@AccelByteWebSocketConfiguration.maxReconnectAttempts
    }
}

data class AccelByteSdkSettings(
    val coreConfig: AccelByteCoreConfiguration,
    val axiosConfig: AccelByteAxiosConfiguration? = null,
    val webSocketConfig: AccelByteWebSocketConfiguration? = null
) {
    internal fun toExternal(): SdkConstructorParam = buildJs {
        coreConfig = this@AccelByteSdkSettings.coreConfig.toExternal()
        axiosConfig = this@AccelByteSdkSettings.axiosConfig?.toExternal()
        webSocketConfig = this@AccelByteSdkSettings.webSocketConfig?.toExternal()
    }
}

data class AccelByteCloneOptions(
    val interceptors: Boolean? = null
) {
    internal fun toExternal(): CloneOptions = buildJs {
        this.interceptors = this@AccelByteCloneOptions.interceptors
    }
}

data class AccelByteSdkSetConfig(
    val coreConfig: AccelByteCoreConfiguration? = null,
    val axiosConfig: AccelByteAxiosConfiguration? = null,
    val webSocketConfig: AccelByteWebSocketConfiguration? = null
) {
    internal fun toExternal(): SdkSetConfigParam = buildJs {
        coreConfig = this@AccelByteSdkSetConfig.coreConfig?.toExternal()
        axiosConfig = this@AccelByteSdkSetConfig.axiosConfig?.toExternal()
        webSocketConfig = this@AccelByteSdkSetConfig.webSocketConfig?.toExternal()
    }
}

data class AccelByteTokenConfiguration(
    val accessToken: String? = null,
    val refreshToken: String? = null
) {
    internal fun toExternal(): TokenConfig = buildJs {
        this.accessToken = this@AccelByteTokenConfiguration.accessToken
        this.refreshToken = this@AccelByteTokenConfiguration.refreshToken
    }
}

fun TokenConfig.toModel(): AccelByteTokenConfiguration = AccelByteTokenConfiguration(
    accessToken = accessToken,
    refreshToken = refreshToken
)

enum class AccelByteInterceptorType(internal val identifier: String) {
    REQUEST("request"),
    RESPONSE("response");

    companion object {
        fun from(value: String): AccelByteInterceptorType? =
            values().firstOrNull { it.identifier == value }
    }
}

data class AccelByteInterceptorDefinition(
    val type: AccelByteInterceptorType,
    val name: String,
    val onRequest: ((AxiosRequestConfig) -> dynamic)? = null,
    val onSuccess: ((AxiosResponse) -> dynamic)? = null,
    val onError: ((dynamic) -> dynamic)? = null
) {
    internal fun toExternal(): Interceptor = buildJs {
        this.type = this@AccelByteInterceptorDefinition.type.identifier
        this.name = this@AccelByteInterceptorDefinition.name
        onRequest = this@AccelByteInterceptorDefinition.onRequest
        onSuccess = this@AccelByteInterceptorDefinition.onSuccess
        onError = this@AccelByteInterceptorDefinition.onError
    }
}

typealias AccelByteInterceptorFilter = (AccelByteInterceptorView) -> Boolean

class AccelByteInterceptorView internal constructor(private val delegate: Interceptor) {
    val type: AccelByteInterceptorType?
        get() = AccelByteInterceptorType.Companion.from(delegate.type)

    val name: String
        get() = delegate.name

    val onRequest: ((AxiosRequestConfig) -> dynamic)?
        get() = delegate.onRequest

    val onSuccess: ((AxiosResponse) -> dynamic)?
        get() = delegate.onSuccess

    val onError: ((dynamic) -> dynamic)?
        get() = delegate.onError
}

internal fun <T> buildJs(block: T.() -> Unit): T {
    @Suppress("UNCHECKED_CAST")
    return (js("({})") as T).apply(block)
}