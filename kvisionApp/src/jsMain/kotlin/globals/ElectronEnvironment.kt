package globals

@Suppress("UnsafeCastFromDynamic")
object ElectronEnvironment {
    private val bridge: dynamic?
        get() = js("typeof window !== \"undefined\" ? window.AutogenesisElectron : undefined")

    val isElectron: Boolean
        get() {
            // Check for bridge presence first
            if (bridge != undefined && bridge != null) return true
            
            // Fallback to userAgent check (works in renderer even with contextIsolation)
            val userAgent = kotlinx.browser.window.navigator.userAgent
            if (userAgent.contains("Electron", ignoreCase = true)) return true

            // Legacy check for nodeIntegration: true
            val hasProcess = js("typeof process !== \"undefined\" && process.versions") != undefined
            val version = if (hasProcess) js("process.versions.electron") else undefined
            return version != undefined && version != null
        }

    val localServerUrl: String?
        get() = bridge?.localServerUrl as? String

    val mainServerUrl: String?
        get() = bridge?.mainServerUrl as? String

    fun requestShutdown() {
        bridge?.shutdown?.invoke()
    }
}
