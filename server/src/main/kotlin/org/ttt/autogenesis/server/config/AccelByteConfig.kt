package org.ttt.autogenesis.server.config

import org.ttt.autogenesis.server.config.env.ProcessEnvironmentCompat
import java.io.File
import java.util.Properties
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Configuration loader for AccelByte credentials and settings.
 * MUST be initialized before any AccelByte SDK classes are accessed.
 */
object AccelByteConfig
{
    private val config = Properties()

    init
    {
        loadConfiguration()
    }

    // AMS-specific environment variable accessors
    // These are loaded lazily since they may be set after initial configuration

    /**
     * Gets the dedicated server ID assigned by AMS.
     * Environment variable: AB_DS_ID
     *
     * @return The server ID or empty string if not configured
     */
    fun getDsId(): String
    {
        return getProperty("AB_DS_ID")
    }

    /**
     * Gets the WebSocket URL for the DS Hub.
     * Environment variable: AB_DS_HUB_URL
     *
     * @return The DS Hub WebSocket URL or empty string if not configured
     */
    fun getDsHubUrl(): String
    {
        return getProperty("AB_DS_HUB_URL")
    }

    /**
     * Gets the URL for the AMS watchdog.
     * Environment variable: AB_WATCHDOG_URL
     *
     * @return The watchdog URL or empty string if not configured
     */
    fun getWatchdogUrl(): String
    {
        return getProperty("AB_WATCHDOG_URL")
    }

    /**
     * Gets the region identifier for this dedicated server.
     * Environment variable: AB_REGION
     *
     * @return The region string or empty string if not configured
     */
    fun getRegion(): String
    {
        return getProperty("AB_REGION")
    }
    
    /**
     * Loads configuration from file or environment variables.
     * This MUST happen before AccelByteSdkProvider or any AccelByte SDK classes are accessed.
     */
    private fun loadConfiguration()
    {
        val configFile = findConfigFile()
        Logger.debug(LogCategory.SYSTEM, "AccelByteConfig loadConfiguration entry (args=${System.getProperty("user.dir")})")
        
        if(configFile != null)
        {
            configFile.inputStream().use { config.load(it) }
            Logger.info(LogCategory.SYSTEM, "Loaded AccelByte config from: ${configFile.absolutePath}")
        }
        else
        {
            Logger.warn(LogCategory.SYSTEM, "No AccelByte config file found, relying on environment variables")
        }
        
        // Apply configuration to environment variables for AccelByte SDK
        applyToEnvironment()
    }
    
    /**
     * Finds AccelByte config file in priority order:
     * 1. ./accelbyte.local.properties
     * 2. ./accelbyte.properties  
     * 3. /home/.autogenesis/config/accelbyte.local.properties
     * 4. /home/.autogenesis/config/accelbyte.properties
     * 5. Resources: accelbyte.local.properties
     * 6. Resources: accelbyte.properties
     */
    private fun findConfigFile(): File?
    {
        val candidates = listOf(
            File("accelbyte.local.properties"),
            File("accelbyte.properties"),
            File(System.getProperty("user.home"), ".autogenesis/config/accelbyte.local.properties"),
            File(System.getProperty("user.home"), ".autogenesis/config/accelbyte.properties")
        )
        
        // Check filesystem locations first
        candidates.forEach { file ->
            if(file.exists())
            {
                 return file
            }
        }
        
        // Check resources
        listOf("accelbyte.local.properties", "accelbyte.properties").forEach { resourceName ->
            val resourceUrl = javaClass.classLoader.getResource(resourceName)
            if(resourceUrl != null)
            {
                return File(resourceUrl.toURI())
            }
        }
        
        return null
    }
    
    /**
     * Applies configuration values to environment variables that AccelByte SDK reads.
     * Uses reflection to modify the environment map since JVM doesn't allow runtime env var changes.
     * This must happen BEFORE any AccelByte SDK lazy properties are accessed.
     */
    private fun applyToEnvironment()
    {
        val namespace = getProperty("AB_NAMESPACE")
        val clientId = getProperty("AB_CLIENT_ID") 
        val clientSecret = getProperty("AB_CLIENT_SECRET")
        val baseUrl = getProperty("AB_BASE_URL")
        
        if(namespace.isEmpty())
        {
            Logger.error(LogCategory.AUTH, "AB_NAMESPACE not found in config file or environment, cannot configure AccelByte SDK")
            return
        }
        
        // Set environment variables using the ProcessEnvironment helper so we always insert the private types
        val envVars = mutableMapOf<String, String>()
        envVars["AB_NAMESPACE"] = namespace
        if(clientId.isNotEmpty())
        {
             envVars["AB_CLIENT_ID"] = clientId
        }
        if(clientSecret.isNotEmpty())
        {
             envVars["AB_CLIENT_SECRET"] = clientSecret
        }
        if(baseUrl.isNotEmpty())
        {
             envVars["AB_BASE_URL"] = baseUrl
        }
        ProcessEnvironmentCompat.patchEnvironment(envVars)
        
        // Also set as system properties as fallback
        envVars.forEach { (key, value) ->
            System.setProperty(key, value)
        }
        
        Logger.info(LogCategory.SYSTEM, "AccelByte config applied - Namespace: $namespace, Base URL: $baseUrl")
        
        // Verify the environment variable was set correctly
        val verifyNamespace = System.getenv("AB_NAMESPACE") ?: System.getProperty("AB_NAMESPACE")
        Logger.debug(LogCategory.SYSTEM, "Verification - AB_NAMESPACE accessible: $verifyNamespace")
    }
    
    /**
     * Gets a property value from config file or environment variable.
     * Applies special sanitization for URL properties.
     *
     * @param key The property key.
     * @return The property value or empty string if not found.
     */
    private fun getProperty(key: String): String
    {
        val rawValue = System.getProperty(key) ?: System.getenv(key) ?: config.getProperty(key) ?: ""
        
        // Apply URL sanitization for base URL properties
        return if(key.contains("BASE_URL") && rawValue.isNotEmpty())
        {
            rawValue.trim().removeSurrounding("\"").removeSurrounding("'")
        }
        else
        {
            rawValue
        }
    }
}
