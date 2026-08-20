package accelbyte

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import java.net.URL

object UrlValidator {
    fun validateAndLogUrl(description: String, urlString: String): Boolean {
        val sanitizedUrl = sanitizeUrl(urlString)
        Logger.info(LogCategory.NETWORK, "$description URL string: '$sanitizedUrl' (original='$urlString', length=${sanitizedUrl.length})")
        
        if (sanitizedUrl.isBlank()) {
            Logger.error(LogCategory.NETWORK, "$description URL is blank")
            return false
        }
        
        if (!sanitizedUrl.startsWith("http://") && !sanitizedUrl.startsWith("https://")) {
            Logger.error(LogCategory.NETWORK, "$description URL does not start with http:// or https://")
            return false
        }
        
        return try {
            val url = URL(sanitizedUrl)
            Logger.info(LogCategory.NETWORK, "$description URL parsed successfully: protocol=${url.protocol}, host=${url.host}, port=${url.port}, path=${url.path}")
            true
        } catch (e: Exception) {
            Logger.error(LogCategory.NETWORK, "$description URL parsing failed: ${e.message}")
            false
        }
    }
    
    fun sanitizeUrl(urlString: String): String {
        return urlString
            .trim()                    // Remove leading/trailing whitespace
            .removeSurrounding("\"")   // Remove surrounding quotes
            .removeSurrounding("'")    // Remove surrounding single quotes
    }
}