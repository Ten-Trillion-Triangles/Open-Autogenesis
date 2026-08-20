package org.ttt.autogenesis.accelbyte

/**
 * Factory for creating AccelByte SDK instances with specified configuration settings.
 * 
 * This factory provides a centralized way to instantiate the AccelByte SDK with
 * proper configuration and initialization. The created instances are fully
 * configured and ready for use with AccelByte services.
 */
object AccelByteSdkFactory 
{
    /**
     * Creates a new AccelByte SDK instance with the provided settings.
     * 
     * The SDK instance will be initialized with the specified configuration
     * and can be used to interact with AccelByte services. Each instance
     * maintains its own state and configuration.
     * 
     * @param settings Configuration settings for the SDK instance
     * @return [AccelByteSdkInstance] Fully configured SDK instance ready for use
     */
    fun create(settings : AccelByteSdkSettings) : AccelByteSdkInstance 
    {
        val sdk = AccelByte.SDK(settings.toExternal())
        return AccelByteSdkInstance(sdk)
    }
}

/**
 * Wrapper class for AccelByte SDK functionality providing Kotlin-friendly interface.
 * 
 * This class encapsulates the underlying AccelByte SDK and provides a clean
 * Kotlin interface for interacting with AccelByte services. It handles type
 * conversions and provides additional functionality on top of the base SDK.
 * 
 * @param delegate The underlying AccelByte SDK instance
 */
class AccelByteSdkInstance internal constructor(private val delegate : AccelByteSDK) 
{
    /**
     * Provides direct access to the underlying AccelByte SDK instance.
     * 
     * Use this property when you need to access functionality not exposed
     * through the Kotlin wrapper or when integrating with code that expects
     * the raw SDK interface.
     * 
     * @return [AccelByteSDK] The underlying SDK instance
     */
    val rawSdk : AccelByteSDK
        get() = delegate

    /**
     * Gets the assembly configuration for this SDK instance.
     * 
     * The assembly contains configuration and metadata about the SDK
     * instance, including service endpoints and authentication settings.
     * 
     * @return [AccelByteAssembly] Assembly configuration for this instance
     */
    fun assembly() : AccelByteAssembly = delegate.assembly()

    /**
     * Creates a clone of this SDK instance with optional configuration overrides.
     * 
     * Cloning allows you to create multiple SDK instances with different
     * configurations while sharing common settings. This is useful for
     * multi-tenant applications or when different parts of your application
     * need different SDK configurations.
     * 
     * @param options Optional configuration overrides for the cloned instance
     * @return [AccelByteSdkInstance] New SDK instance with cloned configuration
     */
    fun clone(options : AccelByteCloneOptions? = null) : AccelByteSdkInstance =
        AccelByteSdkInstance(delegate.clone(options?.toExternal()))

    /**
     * Adds interceptors to this SDK instance for request/response processing.
     * 
     * Interceptors allow you to modify requests before they are sent and
     * responses before they are processed. This is useful for adding
     * authentication, logging, or custom headers to all SDK requests.
     * 
     * @param interceptors Variable number of interceptor definitions to add
     * @return [AccelByteSdkInstance] New SDK instance with added interceptors
     */
    fun addInterceptors(vararg interceptors : AccelByteInterceptorDefinition) : AccelByteSdkInstance 
    {
        val converted = interceptors.map { it.toExternal() }.toTypedArray()
        return AccelByteSdkInstance(delegate.addInterceptors(converted))
    }

    /**
     * Removes interceptors from this SDK instance based on optional filter criteria.
     * 
     * This method allows you to remove previously added interceptors either
     * all at once or selectively based on a filter function. Useful for
     * cleaning up interceptors that are no longer needed.
     * 
     * @param filter Optional filter function to select which interceptors to remove
     * @return [AccelByteSdkInstance] New SDK instance with interceptors removed
     */
    fun removeInterceptors(filter : AccelByteInterceptorFilter? = null) : AccelByteSdkInstance 
    {
        val sdkInstance = if (filter == null) {
            delegate.removeInterceptors()
        } else {
            delegate.removeInterceptors { interceptor ->
                filter(AccelByteInterceptorView(interceptor))
            }
        }
        return AccelByteSdkInstance(sdkInstance)
    }

    /**
     * Updates the configuration for this SDK instance.
     * 
     * This method allows you to modify the SDK configuration after
     * initialization. Changes will affect all subsequent API calls
     * made through this SDK instance.
     * 
     * @param config New configuration settings to apply
     * @return [AccelByteSdkInstance] New SDK instance with updated configuration
     */
    fun setConfig(config : AccelByteSdkSetConfig) : AccelByteSdkInstance =
        AccelByteSdkInstance(delegate.setConfig(config.toExternal()))

    /**
     * Sets the authentication token for this SDK instance.
     * 
     * The token will be used for authenticating API requests made through
     * this SDK instance. This method modifies the current instance rather
     * than creating a new one.
     * 
     * @param token Token configuration containing authentication credentials
     * @return [AccelByteSdkInstance] This SDK instance with token configured
     */
    fun setToken(token : AccelByteTokenConfiguration) : AccelByteSdkInstance 
    {
        delegate.setToken(token.toExternal())
        return this
    }

    /**
     * Removes the authentication token from this SDK instance.
     * 
     * After calling this method, API requests will be made without
     * authentication. This is useful for accessing public endpoints
     * or when switching between different authentication contexts.
     * 
     * @return [AccelByteSdkInstance] This SDK instance with token removed
     */
    fun removeToken() : AccelByteSdkInstance 
    {
        delegate.removeToken()
        return this
    }

    /**
     * Retrieves the current authentication token configuration.
     * 
     * Returns the token configuration currently associated with this
     * SDK instance. This includes token type, value, and any additional
     * authentication metadata.
     * 
     * @return [AccelByteTokenConfiguration] Current token configuration
     */
    fun getToken() : AccelByteTokenConfiguration = delegate.getToken().toModel()
}