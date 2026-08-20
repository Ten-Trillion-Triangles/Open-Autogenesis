package org.ttt.autogenesis.serverextend.config

import org.ttt.autogenesis.server.config.env.ProcessEnvironmentCompat
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import java.io.File
import java.util.Properties

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
    
    /**
     * Loads configuration from file or environment variables.
     * This MUST happen before AccelByteSdkProvider or any AccelByte SDK classes are accessed.
     */
    private fun loadConfiguration()
    {
        Logger.debug(LogCategory.SYSTEM, "Initializing AccelByte configuration...")
        val configFile = findConfigFile()
        
        if(configFile != null)
        {
            configFile.inputStream().use { config.load(it) }
            Logger.info(LogCategory.SYSTEM, "Loaded AccelByte config from: ${configFile.absolutePath}")
        }
        else
        {
            Logger.info(LogCategory.SYSTEM, "No AccelByte config file found, using environment variables")
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
            Logger.debug(LogCategory.SYSTEM, "Checking for AccelByte config file at: ${file.absolutePath}")
            if (file.exists()) {
                Logger.info(LogCategory.SYSTEM, "Found AccelByte config file: ${file.absolutePath}")
                return file
            }
        }
        
        // Check resources
        listOf("accelbyte.local.properties", "accelbyte.properties").forEach { resourceName ->
            Logger.debug(LogCategory.SYSTEM, "Checking for AccelByte config in resources: $resourceName")
            val resourceUrl = javaClass.classLoader.getResource(resourceName)
            if (resourceUrl != null) {
                Logger.info(LogCategory.SYSTEM, "Found AccelByte config in resources: $resourceName")
                return File(resourceUrl.toURI())
            }
        }
        
        Logger.warn(LogCategory.SYSTEM, "No AccelByte config file found in filesystem or resources")
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
            Logger.warn(LogCategory.SYSTEM, "WARNING: AB_NAMESPACE not found in config file or environment")
            return
        }
        
        // Set environment variables using the ProcessEnvironment helper
        val envVars = mutableMapOf<String, String>()
        envVars["AB_NAMESPACE"] = namespace
        if(clientId.isNotEmpty()) envVars["AB_CLIENT_ID"] = clientId
        if(clientSecret.isNotEmpty()) envVars["AB_CLIENT_SECRET"] = clientSecret
        if(baseUrl.isNotEmpty()) envVars["AB_BASE_URL"] = baseUrl
        
        Logger.debug(LogCategory.SYSTEM, "Applying AccelByte environment variables: ${envVars.keys.joinToString(", ")}")
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
     *
     * @param key The property key.
     * @return The property value or empty string if not found.
     */
    private fun getProperty(key: String): String
    {
        return config.getProperty(key) ?: System.getenv(key) ?: ""
    }

    /**
     * Gets the AccelByte namespace for API calls.
     *
     * @return The namespace string.
     */
    fun getNamespace(): String
    {
        return getProperty("AB_NAMESPACE")
    }
}