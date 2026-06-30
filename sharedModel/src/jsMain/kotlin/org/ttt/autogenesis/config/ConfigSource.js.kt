package org.ttt.autogenesis.config

import org.w3c.xhr.XMLHttpRequest

actual object ConfigSource {
    actual fun property(filename: String, key: String): String {
        // Sync XHR — only safe at app startup, NOT inside a Promise chain.
        // gradle-wired copy task in kvisionApp/build.gradle.kts places the
        // *.local.properties files into src/jsMain/resources/ before bundling.
        val req = XMLHttpRequest()
        req.open("GET", filename, false)
        req.send()
        if (req.status != 200.toShort()) {
            error(
                "$filename not found in JS resources (status=${req.status}). " +
                    "Run scripts/sync.sh and scripts/sync-resources.sh from the autogenesis-secrets repo."
            )
        }
        val text = req.responseText
        val line = text.lineSequence().firstOrNull { it.startsWith("$key=") }
            ?: error("$filename missing key '$key'")
        return line.substringAfter("=").trim()
    }
}