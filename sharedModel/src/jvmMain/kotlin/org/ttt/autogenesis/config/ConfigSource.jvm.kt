package org.ttt.autogenesis.config

import java.io.File
import java.util.Properties

actual object ConfigSource {
    actual fun property(filename: String, key: String): String {
        // Two filename conventions exist in this codebase:
        //   1. Bare stem ("accelbyte", "bedrock") — used by unit tests and a
        //      handful of legacy callers. Resolves to accelbyte.local.properties.
        //   2. Already-qualified name ("accelbyte.local.properties",
        //      "bedrock.local.properties") — used by the post-scrub callers
        //      in BedrockConfig.kt / ExtendModelDefaults.kt / GrpcServer.kt /
        //      ServerConfig.kt. We must NOT double-suffix these — strip
        //      `.local.properties` first if present, then re-derive the
        //      candidate list.
        val base = filename
            .removeSuffix(".properties")
            .removeSuffix(".local")
        val home = System.getProperty("user.home")
        val candidates = listOf(
            File("$base.local.properties"),
            File("$base.properties"),
            File(home, ".autogenesis/config/$base.local.properties"),
            File(home, ".autogenesis/config/$base.properties"),
            File(filename),  // fall back to the literal filename the caller asked for
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error(
                "$filename not found; run scripts/sync.sh and scripts/sync-resources.sh " +
                    "from the autogenesis-secrets repo. Looked in: " +
                    candidates.joinToString { it.absolutePath }
            )
        val props = Properties().apply { file.inputStream().use { load(it) } }
        return props.getProperty(key)?.trim()
            ?: error("$filename missing key '$key' (file: ${file.absolutePath})")
    }
}
