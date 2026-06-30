package accounting

import java.io.File
import java.util.Properties

/**
 * Loads AccelByte test config from `server/accelbyte.local.properties`.
 * Fails the test with a clear message if the file is missing or has the
 * template placeholder values.
 *
 * The credentials in the local properties file point at whatever namespace
 * the operator has configured, so the test is destructive — always restore
 * starting state in a `finally` block.
 */
data class AccelByteTestConfig(
    val namespace: String,
    val baseUrl: String,
    val testUserId: String = "00000000000000000000000000000000"
)

/**
 * Reads AccelByte credentials from `server/accelbyte.local.properties` for
 * use by the integration test suite.
 */
object TestApiKeys
{
    /**
     * Candidate file names to look for, in priority order. We try the
     * per-environment `accelbyte.local.properties` first, then fall back to
     * the committed `accelbyte.properties` template so a developer who has
     * only cloned the repo (no local override yet) gets a clear "missing
     * credentials" error instead of an unrelated path-resolution crash.
     */
    private val candidateFileNames: List<String> = listOf(
        "accelbyte.local.properties",
        "accelbyte.properties"
    )

    /**
     * Resolves `server/accelbyte.local.properties` from any working directory.
     *
     * The previous implementation used `File("server/accelbyte.local.properties")`,
     * which only resolves correctly when the JVM runs from the repository
     * root. Gradle's `Test` task runs with `workingDir = project.projectDir`
     * (i.e. `…/Autogenesis/server/`), so the relative path resolved to
     * `…/Autogenesis/server/server/accelbyte.local.properties` — missing even
     * though the file exists one directory above.
     *
     * Search strategy (first existing file wins, evaluated in priority order):
     *   1. `<user.dir>/server/<filename>`               — Gradle module dir from server/
     *   2. `<user.dir>/../<filename>`                   — repo root from server/
     *   3. Walk up from `<user.dir>` looking for a `server/` ancestor that
     *      contains `<filename>`                        — multi-project checkouts
     *   4. `<user.home>/.autogenesis/config/<filename>` — operator-installed
     *                                                    override (matches the
     *                                                    staging convention in
     *                                                    `server/build.gradle.kts`)
     *
     * Throws [IllegalArgumentException] when no candidate file is found.
     */
    private fun resolveConfigFile(): File
    {
        val userDir = File(System.getProperty("user.dir"))
        val candidates: List<File> = buildList {
            for (fileName in candidateFileNames)
            {
                add(userDir.resolve("server/$fileName"))
                add(userDir.resolve("..").resolve(fileName))
                var walker: File? = userDir.parentFile
                while (walker != null)
                {
                    val candidate = walker.resolve("server/$fileName")
                    if (candidate.exists()) { add(candidate); break }
                    if (walker.resolve(fileName).exists()) { add(walker.resolve(fileName)); break }
                    walker = walker.parentFile
                }
                val globalOverride = File(System.getProperty("user.home"), ".autogenesis/config/$fileName")
                add(globalOverride)
            }
        }
        val resolved = candidates.firstOrNull { it.exists() }
        require(resolved != null)
        {
            "server/accelbyte.local.properties not found — searched:\n" +
                candidates.distinct().joinToString("\n") { "  ${it.absolutePath}" } +
                "\nCopy server/accelbyte.properties to server/accelbyte.local.properties and fill in real values, " +
                "or place one at ~/.autogenesis/config/accelbyte.local.properties."
        }
        return resolved
    }

    /**
     * Loads the test configuration. Throws [IllegalStateException] when the
     * file is missing or still contains the placeholder values copied from
     * the non-secret `accelbyte.properties` template.
     *
     * @return The resolved [AccelByteTestConfig].
     */
    fun load(): AccelByteTestConfig
    {
        val file = resolveConfigFile()
        val props = Properties().apply { file.inputStream().use { load(it) } }
        val namespace = props.getProperty("AB_NAMESPACE", "")
        val baseUrl = props.getProperty("AB_BASE_URL", "")
        val clientId = props.getProperty("AB_CLIENT_ID", "")
        require(namespace.isNotBlank() && !clientId.startsWith("your_"))
        {
            "server/accelbyte.local.properties has placeholder values. Fill in real AB_NAMESPACE, AB_CLIENT_ID, AB_CLIENT_SECRET, AB_BASE_URL."
        }
        return AccelByteTestConfig(namespace, baseUrl)
    }
}
