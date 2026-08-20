@file:JsModule("@accelbyte/sdk")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte

import kotlin.js.Json

/**
 * External named export that exposes the AccelByte SDK constructor.
 */
external val AccelByte: AccelByteNamespace

external interface AccelByteNamespace
{
    /**
     * Creates a new AccelByte SDK instance.
     * @param param SDK constructor parameters
     * @return Configured AccelByte SDK instance
     */
    fun SDK(param : SdkConstructorParam) : AccelByteSDK
}

/**
 * Core configuration interface for AccelByte SDK.
 * Contains essential connection and authentication parameters.
 */
external interface CoreConfig
{
    var clientId : String
    var redirectURI : String
    var baseURL : String
    var namespace : String
    var useSchemaValidation : Boolean?
}

/**
 * Axios HTTP client configuration interface.
 * Allows customization of request handling and interceptors.
 */
external interface AxiosConfig
{
    var interceptors : Array<Interceptor>?
    var request : AxiosRequestConfig?
}

/**
 * WebSocket configuration interface.
 * Controls reconnection behavior for real-time connections.
 */
external interface WebSocketConfig
{
    var allowReconnect : Boolean?
    var maxReconnectAttempts : Int?
}

/**
 * HTTP request/response interceptor interface.
 * Enables custom handling of requests, responses, and errors.
 */
external interface Interceptor
{
    var type : String
    var name : String
    var onRequest : ((config : AxiosRequestConfig) -> dynamic)?
    var onSuccess : ((response : AxiosResponse) -> dynamic)?
    var onError : ((error : dynamic) -> dynamic)?
}

/**
 * Parameters for SDK constructor.
 * Combines core, axios, and websocket configurations.
 */
external interface SdkConstructorParam
{
    var coreConfig : CoreConfig
    var axiosConfig : AxiosConfig?
    var webSocketConfig : WebSocketConfig?
}

/**
 * Parameters for updating SDK configuration.
 * All fields are optional for partial updates.
 */
external interface SdkSetConfigParam
{
    var coreConfig : CoreConfig?
    var axiosConfig : AxiosConfig?
    var webSocketConfig : WebSocketConfig?
}

/**
 * Token configuration interface.
 * Holds access and refresh tokens for authentication.
 */
external interface TokenConfig
{
    var accessToken : String?
    var refreshToken : String?
}

/**
 * Options for cloning SDK instances.
 * Controls which components are copied to the new instance.
 */
external interface CloneOptions
{
    var interceptors : Boolean?
}

/**
 * Main AccelByte SDK interface.
 * Provides methods for configuration, token management, and instance cloning.
 */
external interface AccelByteSDK
{
    /**
     * Gets the SDK assembly containing internal components.
     * @return SDK assembly with axios, config, and websocket instances
     */
    fun assembly() : AccelByteAssembly
    
    /**
     * Creates a clone of this SDK instance.
     * @param opts Optional cloning configuration
     * @return New SDK instance with copied configuration
     */
    fun clone(opts : CloneOptions? = definedExternally) : AccelByteSDK
    
    /**
     * Adds HTTP interceptors to the SDK.
     * @param interceptors Array of interceptors to add
     * @return Updated SDK instance
     */
    fun addInterceptors(interceptors : Array<Interceptor>) : AccelByteSDK
    
    /**
     * Removes all HTTP interceptors from the SDK.
     * @return Updated SDK instance
     */
    fun removeInterceptors() : AccelByteSDK
    
    /**
     * Removes HTTP interceptors matching the filter callback.
     * @param filterCallback Function to determine which interceptors to remove
     * @return Updated SDK instance
     */
    fun removeInterceptors(filterCallback : (interceptor : Interceptor) -> Boolean) : AccelByteSDK
    
    /**
     * Updates SDK configuration.
     * @param param New configuration parameters
     * @return Updated SDK instance
     */
    fun setConfig(param : SdkSetConfigParam) : AccelByteSDK
    
    /**
     * Sets authentication tokens.
     * @param token Token configuration with access and refresh tokens
     */
    fun setToken(token : TokenConfig)
    
    /**
     * Removes all authentication tokens.
     */
    fun removeToken()
    
    /**
     * Gets current authentication tokens.
     * @return Current token configuration
     */
    fun getToken() : TokenConfig
}

/**
 * SDK assembly interface containing internal components.
 * Provides access to low-level SDK internals.
 */
external interface AccelByteAssembly
{
    val axiosInstance : AxiosInstance
    val coreConfig : CoreConfig
    val axiosConfig : AxiosConfig
    val webSocketConfig : WebSocketConfig
}

/**
 * Axios HTTP client instance interface.
 */
external interface AxiosInstance

/**
 * Axios HTTP request configuration interface.
 */
external interface AxiosRequestConfig

/**
 * Axios HTTP response interface.
 */
external interface AxiosResponse
{
    val data: Json
}