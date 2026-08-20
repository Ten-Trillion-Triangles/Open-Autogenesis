package utils

import kotlinx.browser.window

enum class BrowserEnvironment
{
    ELECTRON,
    FIREFOX,
    CHROMIUM,
    UNSUPPORTED
}

/**
 * Detects the current runtime environment based on the browser's userAgent string.
 */
fun getBrowserEnvironment(): BrowserEnvironment
{
    val userAgent = window.navigator.userAgent

    return when
    {
        userAgent.contains("Electron", ignoreCase = true) -> BrowserEnvironment.ELECTRON
        userAgent.contains("Firefox", ignoreCase = true) -> BrowserEnvironment.FIREFOX
        userAgent.contains("Chrome", ignoreCase = true) || userAgent.contains("Chromium", ignoreCase = true) -> BrowserEnvironment.CHROMIUM
        else -> BrowserEnvironment.UNSUPPORTED
    }
}