package globals

/**
 * Global flag that mirrors the server-extend `ExtendConfig.debugMode` default,
 * but flipped: on the client, `debugMode = true` means "use the local
 * server-extend / game-server URLs and skip the match2 / AMS path", and
 * `debugMode = false` means "use the live AccelByte URLs and rely on the
 * matchmaker + AMS-provisioned DS". This is the inverse of the server-side
 * `ExtendConfig.debugMode` because the server's `debugMode = true` is the
 * "local only" branch.
 *
 * Set at build time by the `kvision.liveMode` Gradle property, which the
 * kvisionApp build script materializes as a generated `LiveMode.kt` file
 * under `build/generated/liveMode/`. Default is `false` (debug / local-mode
 * bundle), which matches the previous hardcoded `false` literal.
 *
 * To build a live-mode bundle: `./gradlew :kvisionApp:jsBrowserDevelopmentRun -Pkvision.liveMode=true`
 * or `./gradlew :kvisionApp:browserProductionWebpack -Pkvision.liveMode=true`.
 *
 * The corresponding server-side env var is `SERVER_EXTEND_LIVE_MODE`; the two
 * must agree at runtime. See `docs/LIVE_MODE.md` for the full operator runbook.
 */
object ClientDebug
{
    var debugMode: Boolean = !LiveMode.liveMode
}
