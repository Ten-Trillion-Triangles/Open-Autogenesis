import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.GradleException
import java.io.File
import java.util.Properties
import org.beryx.runtime.JreTask
import org.beryx.runtime.RuntimeTask
import org.beryx.runtime.RuntimeZipTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.beryxRuntime)
}

ksp {
    // Use unique cache directory per module to avoid concurrent access conflicts
    // when running server and server-extend simultaneously
    arg("ksp.cache.dir", "build/ksp-cache")
}

val runtimeJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(24))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

kotlin {
    jvmToolchain(24)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)
    }
}

application {
    applicationName = "autogenesis-server"
    mainClass.set("org.ttt.autogenesis.server.ServerKt")
    applicationDefaultJvmArgs = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED", "-XX:-EnableJVMCI")
    // Forward dev-mode overrides to the :server:run JVM.
    //   AUTOGENESIS_SHUTDOWN_DELAY_MS — keeps the DS alive long enough for
    //     the user to switch tabs and resume (default 15s, dev 600s).
    //   AUTOGENESIS_DISABLE_AUTO_RESTORE — suppresses the WS-rebind
    //     auto-restore path so server-extend's push wins the race and the
    //     ResumeOrNewDialog modal can render. Production (live mode with
    //     AMS-provisioned fresh DS) leaves this OFF because the new DS
    //     doesn't share state with the old one — auto-restore IS the
    //     resume mechanism there.
    val shutdownDelayOverride = System.getenv("AUTOGENESIS_SHUTDOWN_DELAY_MS")?.takeIf { it.isNotBlank() }
    if (shutdownDelayOverride != null) {
        applicationDefaultJvmArgs = applicationDefaultJvmArgs +
            "-DAUTOGENESIS_SHUTDOWN_DELAY_MS=$shutdownDelayOverride"
    }
    val disableAutoRestore = System.getenv("AUTOGENESIS_DISABLE_AUTO_RESTORE")?.toBooleanStrictOrNull() ?: false
    if (disableAutoRestore) {
        applicationDefaultJvmArgs = applicationDefaultJvmArgs +
            "-DAUTOGENESIS_DISABLE_AUTO_RESTORE=true"
    }
    // Forward the push test endpoint toggle so the push-turn-start e2e probe
    // can hit `/debug/seed-push-subscription`. Defaults to OFF in production
    // builds — see Server.kt:759 for the gating check.
    val pushTestEndpoint = System.getenv("AUTOGENESIS_PUSH_TEST_ENDPOINT")?.takeIf { it.isNotBlank() }
    if (pushTestEndpoint != null) {
        applicationDefaultJvmArgs = applicationDefaultJvmArgs +
            "-Dpush.test.endpoint=$pushTestEndpoint"
    }
}

runtime {
    imageDir.set(layout.buildDirectory.dir("server-runtime"))
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    @Suppress("UNCHECKED_CAST")
    modules.set(rootProject.extra["jreModules"] as List<String>)
    javaHome.set(runtimeJavaLauncher.map { it.metadata.installationPath.asFile.absolutePath })
    // Produce runtime images for Linux and Windows so electron packaging can cross-ship them.
    targetPlatform("linux-x64") {
        jdkHome.set(
            jdkDownload(rootProject.extra["oracleJdk24LinuxUrl"] as String)
        )
    }
    targetPlatform("windows-x64") {
        jdkHome.set(
            jdkDownload(rootProject.extra["oracleJdk24WindowsUrl"] as String)
        )
    }
}

listOf("jre", "runtime", "runtimeZip").forEach { taskName ->
    tasks.named(taskName).configure {
        notCompatibleWithConfigurationCache("The Beryx runtime tasks invoke unsupported APIs such as Task.project at execution time.")
    }
}

tasks.withType(JreTask::class.java).configureEach {
    notCompatibleWithConfigurationCache("This task uses runtime APIs that break the configuration cache.")
}
tasks.withType(RuntimeTask::class.java).configureEach {
    notCompatibleWithConfigurationCache("This task uses runtime APIs that break the configuration cache.")
}
tasks.withType(RuntimeZipTask::class.java).configureEach {
    notCompatibleWithConfigurationCache("This task uses runtime APIs that break the configuration cache.")
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "24"
    targetCompatibility = "24"
}

tasks.jar {
    from(sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// Ensure KSP regenerates for new @RpcMethod annotations
afterEvaluate {
    tasks.findByName("kspKotlin")?.let { kspTask ->
        tasks.named("compileKotlin") {
            dependsOn(kspTask)
        }
        // Force KSP to always run to catch new annotations
        kspTask.outputs.upToDateWhen { false }
    }
}

group = "org.ttt.autogenesis"
version = "0.1.0"

dependencies {
    implementation(project(":sharedModel"))
    implementation("com.TTT:TPipe:1.0.0")
    implementation("com.TTT:TPipe-Bedrock:1.0.0")
    implementation("com.TTT:TPipe-Defaults:1.0.0")
    add("ksp", project(":rpc-ksp"))
    val ktorVersion = "3.1.3"
    val kotlinxSerializationVersion = "1.8.0"
    val kotlinxCoroutinesVersion = "1.10.2"

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
    implementation("net.accelbyte.sdk:sdk:0.77.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation(platform("com.linecorp.armeria:armeria-bom:${libs.versions.armeria.get()}"))
    implementation(libs.armeria)
    implementation(libs.armeria.grpc)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    // Web Push: nl.martijndwars:web-push depends on Apache HttpClient, Bouncy
    // Castle, and jose4j. Declaring Bouncy Castle explicitly so the runtime
    // provider is registered before PushService.send() is called.
    implementation("nl.martijndwars:web-push:5.1.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    testImplementation(libs.kotlin.test)
    testImplementation("io.mockk:mockk:1.14.5")
    testImplementation("org.junit.vintage:junit-vintage-engine:5.12.2")
}

// Phase 2 of feature/live-pvp-and-billing: split the JVM tests into a default
// `test` task (skips @Tag("sandbox")) and an opt-in `testSandbox` task that
// runs ONLY the @Tag("sandbox") tests. The default test is unaffected so CI
// remains green; operators opt in to the sandbox tests by running
// `./gradlew :server:testSandbox` with real AccelByte creds in the env.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED", "-XX:-EnableJVMCI")
    useJUnitPlatform {
        includeEngines("junit-jupiter", "junit-vintage")
        // Phase 2 of feature/live-pvp-and-billing: the default `test` task
        // excludes @Tag("sandbox"); the opt-in `testSandbox` task below
        // re-includes them.
        if (name == "test") {
            excludeTags("sandbox")
        }
    }
    // The default test task includes the standard packages; the testSandbox
    // task (registered below) clears the includeTestsMatching filter so the
    // sandbox tests are not gated on package name.
    if (name == "test") {
        filter {
            includeTestsMatching("agent.runners.*")
            includeTestsMatching("agent.builders.*")
            includeTestsMatching("agent.math.*")
            includeTestsMatching("ams.*")
            includeTestsMatching("accelbyte.*")
            includeTestsMatching("org.ttt.autogenesis.*")
            // Phase 4/5/6 of feature/live-pvp-and-billing added tests in
            // these packages; the default include list is kept narrow to
            // skip sandbox-tagged tests in any package, so we add the new
            // packages explicitly.
            includeTestsMatching("accounting.*")
            includeTestsMatching("globals.*")
            includeTestsMatching("matchmaking.*")
            includeTestsMatching("gameInit.*")
        }
    }
}

tasks.register<Test>("testSandbox") {
    group = "verification"
    description = "Runs only the @Tag(\"sandbox\") tests. Requires real AccelByte credentials in accelbyte.local.properties or env."
    useJUnitPlatform {
        includeEngines("junit-jupiter", "junit-vintage")
        includeTags("sandbox")
    }
    // Override the package filter so sandbox tests in any package run.
    filter {
        includeTestsMatching("*")
    }
    shouldRunAfter("test")
}

// ----------------------------------------------------------------------
// AMS upload staging
//
// `stageAmsUpload` produces server/build/ams-upload/, an AMS-upload-ready
// folder consumed by UBuild's out-of-band `ams upload` invocation:
//     cd server/build/ams-upload
//     ams upload -H <host> -c <id> -s <secret> -n autogenesis-server \
//                -p . -e bin/autogenesis-server
//
// The task reuses the Beryx :server:runtime Linux output and the same
// accelbyte.properties validation flow that electronApp already uses. No
// `ams upload` call is wired into gradle; UBuild drives that.
//
// Validation is duplicated from electronApp/build.gradle.kts rather than
// extracted to a buildSrc convention plugin. A future refactor can lift
// `selectAccelByteConfig` and `validateAccelByteConfig` into a shared
// precompiled script plugin; that work is out of scope for this task.
// ----------------------------------------------------------------------

val placeholderPatterns = listOf(
    "your_client_id",
    "your_client_secret",
    "change_me",
    "changeme",
    "local-client",
    "local-secret",
)

private val accelByteConfigFilenames = listOf("accelbyte.local.properties", "accelbyte.properties")
private val globalAccelByteConfigDir = File(System.getProperty("user.home"), ".autogenesis/config")

private data class AccelByteConfigCandidate(val file: File, val description: String)

/**
 * Resolves the AccelByte credentials file for [name] from [moduleDir] or the
 * global ~/.autogenesis/config/ directory, then validates it. Throws
 * [GradleException] with an operator-friendly message when no valid file is
 * found, so a missing credential fails the build at config time rather than
 * mid-`runtime`.
 *
 * Search order (first existing file wins):
 *   1. <moduleDir>/accelbyte.local.properties
 *   2. <moduleDir>/accelbyte.properties
 *   3. ~/.autogenesis/config/accelbyte.local.properties
 *   4. ~/.autogenesis/config/accelbyte.properties
 *
 * @param name Logical owner name used in the error message (e.g. "server").
 * @param moduleDir The module directory whose local copy should be preferred.
 * @return The validated [File] ready to be staged.
 */
fun selectAccelByteConfig(name: String, moduleDir: File): File
{
    val candidates = buildAccelByteCandidates(moduleDir)

    candidates.forEach { candidate ->
        if(candidate.file.exists())
        {
            logger.lifecycle("Using AccelByte config ${candidate.description} (${candidate.file.absolutePath}) for $name")
            return validateAccelByteConfig(candidate.file, name)
        }
    }

    val lookedLocations = candidates.joinToString(", ") { it.description }
    throw GradleException(
        "No AccelByte config file found for $name; looked in: $lookedLocations. " +
            "Create ${moduleDir.name}/accelbyte.local.properties (or place one inside /home/.autogenesis/config) with real credentials and rerun the build."
    )
}

private fun buildAccelByteCandidates(moduleDir: File): List<AccelByteConfigCandidate>
{
    val candidates = mutableListOf<AccelByteConfigCandidate>()
    accelByteConfigFilenames.forEach { filename ->
        candidates += AccelByteConfigCandidate(
            moduleDir.resolve(filename),
            "module/${moduleDir.name}/$filename",
        )
        candidates += AccelByteConfigCandidate(
            globalAccelByteConfigDir.resolve(filename),
            "${globalAccelByteConfigDir.absolutePath}/$filename",
        )
    }
    return candidates
}

/**
 * Validates [file] contains non-blank, non-placeholder AccelByte credentials.
 * Required keys: `AB_NAMESPACE`, `AB_CLIENT_ID`, `AB_CLIENT_SECRET`,
 * `AB_BASE_URL`. `AB_CLIENT_ID` and `AB_CLIENT_SECRET` are additionally
 * rejected when they contain any [placeholderPatterns] fragment.
 *
 * @param file The candidate credentials file. Must exist.
 * @param owner Logical owner name used in the error message (e.g. "server").
 * @return [file] unchanged, for fluent chaining.
 */
fun validateAccelByteConfig(file: File, owner: String): File
{
    val properties = Properties()
    file.inputStream().use { properties.load(it) }
    val requiredKeys = listOf("AB_NAMESPACE", "AB_CLIENT_ID", "AB_CLIENT_SECRET", "AB_BASE_URL")
    requiredKeys.forEach { key ->
        val value = properties.getProperty(key)?.trim().orEmpty()
        if(value.isBlank())
        {
            throw GradleException("AccelByte config for $owner (${file.absolutePath}) is missing required property: $key")
        }
        if((key == "AB_CLIENT_ID" || key == "AB_CLIENT_SECRET") && placeholderPatterns.any { value.lowercase().contains(it) })
        {
            throw GradleException("AccelByte config for $owner contains placeholder value for $key; replace it with real credentials before packaging.")
        }
    }
    return file
}

val serverDir = rootProject.file("server")
val serverAccelByteProps = selectAccelByteConfig("server", serverDir)

val serverBeryxRuntimeDir = serverDir.resolve("build/server-runtime")
val serverLinuxRuntimeDir = serverBeryxRuntimeDir.resolve("server-linux-x64")
val amsUploadDir = serverDir.resolve("build/ams-upload")
val amsUploadMarkdown = serverDir.resolve("src/main/ams/AMS_UPLOAD.md")

val stageAmsUpload by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Stages the AMS upload folder under server/build/ams-upload/. " +
        "Run UBuild's `ams upload -p <path> -e bin/autogenesis-server` against the output."
    dependsOn(":server:runtime")
    doFirst {
        delete(destinationDir)
    }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from(serverLinuxRuntimeDir)
    from(serverAccelByteProps) {
        rename { "accelbyte.properties" }
    }
    from(serverAccelByteProps) {
        into("bin")
        rename { "accelbyte.properties" }
    }
    from(amsUploadMarkdown) {
        rename { "AMS_UPLOAD.md" }
    }
    into(amsUploadDir)
    doLast {
        // Beryx normally preserves the executable bit on bin/autogenesis-server,
        // but a defensive chmod keeps `ams upload` from receiving a silent 4xx
        // if a future Beryx upgrade drops the perms. setExecutable is portable
        // and avoids forking a shell.
        val entryScript = destinationDir.resolve("bin/autogenesis-server")
        if(entryScript.exists())
        {
            if(!entryScript.setExecutable(true, false))
            {
                throw GradleException("Failed to mark $entryScript as executable; the AMS upload entry point must be runnable.")
            }
        }
        else
        {
            throw GradleException("Expected $entryScript to exist after staging; the Beryx runtime output is missing the entry script.")
        }
    }
}
