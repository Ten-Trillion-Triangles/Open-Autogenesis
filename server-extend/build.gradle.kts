import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
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
    applicationName = "autogenesis-server-extend"
    mainClass.set("org.ttt.autogenesis.serverextend.ServerExtendKt")
    applicationDefaultJvmArgs = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    // Forward AUTOGENESIS_SHUTDOWN_DELAY_MS to the :server-extend:run JVM
    // for symmetry with the main server's dev-mode override.
    val shutdownDelayOverride = System.getenv("AUTOGENESIS_SHUTDOWN_DELAY_MS")?.takeIf { it.isNotBlank() }
    if (shutdownDelayOverride != null) {
        applicationDefaultJvmArgs = applicationDefaultJvmArgs +
            "-DAUTOGENESIS_SHUTDOWN_DELAY_MS=$shutdownDelayOverride"
    }
}

tasks.withType<Test>().configureEach {
    // Match the runtime --add-opens for tests that touch AccelByteConfig's reflective env patching
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

runtime {
    imageDir.set(layout.buildDirectory.dir("server-extend-runtime"))
    options.set(
        listOf(
            "--strip-debug",
            "--compress",
            "2",
            "--no-header-files",
            "--no-man-pages"
        )
    )
    @Suppress("UNCHECKED_CAST")
    modules.set(rootProject.extra["jreModules"] as List<String>)
    javaHome.set(runtimeJavaLauncher.map { it.metadata.installationPath.asFile.absolutePath })
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
    implementation(project(":server"))
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
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
    implementation("net.accelbyte.sdk:sdk:0.77.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    // Web Push: nl.martijndwars:web-push depends on Apache HttpClient, Bouncy
    // Castle, and jose4j. Declaring Bouncy Castle explicitly so the runtime
    // provider is registered before PushService.send() is called.
    implementation("nl.martijndwars:web-push:5.1.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Apache HttpClient is a transitive of web-push but we reference
    // HttpResponse / StatusLine directly so declare it explicitly.
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    testImplementation(libs.kotlin.test)
    testImplementation("io.mockk:mockk:1.14.5")
    testImplementation("org.junit.vintage:junit-vintage-engine:5.12.2")
}

// -----------------------------------------------------------------------------
// Docker image build/push (Extend deployment plumbing).
//
// `:server-extend:runtime` is the beryx-runtime task that already produces
// the self-contained Linux x64 image at
// `build/server-extend-runtime/server-extend-linux-x64/`. The Dockerfile in
// this module root copies that directory into the container and sets
// `bin/entrypoint.sh` as the entrypoint so PID 1 == the JVM.
//
// The image name is configurable via the `docker.image` Gradle property
// (default `ghcr.io/cage/autogenesis-server-extend`); the tag is
// `<short-sha>` when available and `dev` otherwise. Operators set
// `-Pdocker.push=true` to actually push (the default is build-only, so a
// local `:dockerBuildImage` doesn't accidentally hit the registry).
// -----------------------------------------------------------------------------

val dockerImage: String = (project.findProperty("docker.image") as String?)
    ?: "ghcr.io/cage/autogenesis-server-extend"
val dockerTag: String = runCatching {
    val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    proc.waitFor()
    if (proc.exitValue() == 0) proc.inputStream.bufferedReader().readText().trim()
    else "dev"
}.getOrDefault("dev")

extra["docker.image"] = dockerImage
extra["docker.tag"] = dockerTag

val dockerBuildImage: TaskProvider<Exec> = tasks.register<Exec>("dockerBuildImage") {
    group = "docker"
    description = "Builds the server-extend Docker image from the current beryx runtime output (build/server-extend-runtime/server-extend-linux-x64/)."

    dependsOn("runtime")
    commandLine = listOf(
        "docker", "build",
        "-t", "$dockerImage:$dockerTag",
        "-t", "$dockerImage:dev",
        "-f", "Dockerfile",
        "."
    )
}

val dockerPushImage: TaskProvider<Exec> = tasks.register<Exec>("dockerPushImage") {
    group = "docker"
    description = "Pushes the server-extend Docker image to the configured registry (default: ghcr.io/cage/autogenesis-server-extend)."

    dependsOn(dockerBuildImage)
    val doPush = (project.findProperty("docker.push") as String?)?.toBoolean() ?: false
    onlyIf { doPush }
    commandLine = listOf("docker", "push", "$dockerImage:$dockerTag")
}