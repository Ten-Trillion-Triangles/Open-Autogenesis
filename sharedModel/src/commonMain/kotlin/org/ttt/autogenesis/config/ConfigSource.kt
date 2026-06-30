package org.ttt.autogenesis.config

expect object ConfigSource {
    /**
     * Reads the named `*.properties` file and returns the value for `key`.
     *
     * File resolution order (first match wins):
     *   1. `<cwd>/<base>.local.properties`           — module-local override (synced by autogenesis-secrets/scripts/sync-resources.sh)
     *   2. `~/.autogenesis/config/<base>.local.properties` — global per-machine override
     *   3. `~/.autogenesis/config/<base>.properties`       — global default
     *
     * @param filename properties filename, e.g. `"accelbyte.properties"` or `"accelbyte.local.properties"`
     * @param key property key to read
     * @return the property value (trimmed)
     * @throws IllegalStateException when the file is not found in any candidate location, or the key is missing
     */
    fun property(filename: String, key: String): String
}