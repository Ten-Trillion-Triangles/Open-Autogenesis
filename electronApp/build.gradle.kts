import com.github.gradle.node.npm.task.NpmTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.nodeGradle)
}

node {
    download.set(true)
    version.set("20.9.0")
    npmVersion.set("10.9.0")
}

val npmCacheDir = layout.buildDirectory.dir("npm-cache")
val electronCacheDir = layout.buildDirectory.dir("electron-cache")

tasks.withType<NpmTask>().configureEach {
    environment.put("npm_config_cache", npmCacheDir.get().asFile.absolutePath)
    environment.put("XDG_CACHE_HOME", npmCacheDir.get().asFile.absolutePath)
    environment.put("ELECTRON_CACHE", electronCacheDir.get().asFile.absolutePath)
}

val stagingDir = layout.buildDirectory.dir("staging")
val frontendWebpackDist = rootProject.file("kvisionApp/build/kotlin-webpack/js/productionExecutable")
val frontendResourcesDist = rootProject.file("kvisionApp/build/processedResources/js/main")
val serverRuntimeDir = rootProject.file("server/build/server-runtime")
val serverExtendRuntimeDir = rootProject.file("server-extend/build/server-extend-runtime")

val placeholderPatterns = listOf("your_client_id", "your_client_secret", "change_me", "changeme", "local-client", "local-secret")

private val accelByteConfigFilenames = listOf("accelbyte.local.properties", "accelbyte.properties")
private val globalAccelByteConfigDir = File(System.getProperty("user.home"), ".autogenesis/config")

private data class AccelByteConfigCandidate(val file: File, val description: String)

fun selectAccelByteConfig(name: String, moduleDir: File): File {
    val candidates = buildAccelByteCandidates(moduleDir)

    candidates.forEach { candidate ->
        if (candidate.file.exists()) {
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

private fun buildAccelByteCandidates(moduleDir: File): List<AccelByteConfigCandidate> {
    val candidates = mutableListOf<AccelByteConfigCandidate>()
    accelByteConfigFilenames.forEach { filename ->
        candidates += AccelByteConfigCandidate(
            moduleDir.resolve(filename),
            "module/${moduleDir.name}/$filename"
        )
        candidates += AccelByteConfigCandidate(
            globalAccelByteConfigDir.resolve(filename),
            "${globalAccelByteConfigDir.absolutePath}/$filename"
        )
    }
    return candidates
}

fun validateAccelByteConfig(file: File, owner: String): File {
    val properties = Properties()
    file.inputStream().use { properties.load(it) }
    val requiredKeys = listOf("AB_NAMESPACE", "AB_CLIENT_ID", "AB_CLIENT_SECRET", "AB_BASE_URL")
    requiredKeys.forEach { key ->
        val value = properties.getProperty(key)?.trim().orEmpty()
        if (value.isBlank()) {
            throw GradleException("AccelByte config for $owner (${file.absolutePath}) is missing required property: $key")
        }
        if ((key == "AB_CLIENT_ID" || key == "AB_CLIENT_SECRET") && placeholderPatterns.any { value.lowercase().contains(it) }) {
            throw GradleException("AccelByte config for $owner contains placeholder value for $key; replace it with real credentials before packaging.")
        }
    }
    return file
}

val serverDir = rootProject.file("server")
val serverExtendDir = rootProject.file("server-extend")
val serverAccelByteProps = selectAccelByteConfig("server", serverDir)
val serverExtendAccelByteProps = selectAccelByteConfig("server-extend", serverExtendDir)

val stageFrontend by tasks.registering(Copy::class) {
    dependsOn(":kvisionApp:jsBrowserProductionWebpack")
    doFirst {
        delete(destinationDir)
    }
    from(frontendWebpackDist)
    from(frontendResourcesDist)
    into(stagingDir.map { it.dir("frontend") })
}

val stageServerRuntimeLinux by tasks.registering(Copy::class) {
    dependsOn(":server:runtime")
    doFirst {
        delete(destinationDir)
    }
    from(serverRuntimeDir.resolve("server-linux-x64"))
    from(serverAccelByteProps) {
        rename { "accelbyte.properties" }
    }
    from(serverAccelByteProps) {
        into("bin")
        rename { "accelbyte.properties" }
    }
    into(stagingDir.map { it.dir("runtime/server-linux-x64") })
}

val stageServerRuntimeWindows by tasks.registering(Copy::class) {
    dependsOn(":server:runtime")
    doFirst {
        delete(destinationDir)
    }
    from(serverRuntimeDir.resolve("server-windows-x64"))
    from(serverAccelByteProps) {
        rename { "accelbyte.properties" }
    }
    from(serverAccelByteProps) {
        into("bin")
        rename { "accelbyte.properties" }
    }
    into(stagingDir.map { it.dir("runtime/server-windows-x64") })
}

val stageServerExtendRuntimeLinux by tasks.registering(Copy::class) {
    dependsOn(":server-extend:runtime")
    doFirst {
        delete(destinationDir)
    }
    from(serverExtendRuntimeDir.resolve("server-extend-linux-x64"))
    from(serverExtendAccelByteProps) {
        rename { "accelbyte.properties" }
    }
    from(serverExtendAccelByteProps) {
        into("bin")
        rename { "accelbyte.properties" }
    }
    into(stagingDir.map { it.dir("runtime/server-extend-linux-x64") })
}

val stageServerExtendRuntimeWindows by tasks.registering(Copy::class) {
    dependsOn(":server-extend:runtime")
    doFirst {
        delete(destinationDir)
    }
    from(serverExtendRuntimeDir.resolve("server-extend-windows-x64"))
    from(serverExtendAccelByteProps) {
        rename { "accelbyte.properties" }
    }
    from(serverExtendAccelByteProps) {
        into("bin")
        rename { "accelbyte.properties" }
    }
    into(stagingDir.map { it.dir("runtime/server-extend-windows-x64") })
}

val stageConfig by tasks.registering(Copy::class) {
    doFirst {
        delete(destinationDir)
    }
    from(serverAccelByteProps) {
        rename { "accelbyte.properties" }
    }
    from(serverExtendAccelByteProps) {
        rename { "accelbyte-extend.properties" }
    }
    into(stagingDir.map { it.dir("config") })
}

val stageResources by tasks.registering {
    dependsOn(
        stageFrontend,
        stageServerRuntimeLinux,
        stageServerRuntimeWindows,
        stageServerExtendRuntimeLinux,
        stageServerExtendRuntimeWindows,
        stageConfig,
    )
}

val npmLibDir = node.npmVersion.map { version ->
    layout.projectDirectory.dir(".gradle/npm/npm-v$version/lib/node_modules/npm")
}

val electronOutputDir = layout.buildDirectory.dir("electron")

val cleanBuild by tasks.registering(Delete::class) {
    group = "build"
    description = "Clears the build directory to ensure fresh packaging"
    delete(layout.buildDirectory)
}

val prepareNpmLib by tasks.registering(Copy::class) {
    dependsOn(tasks.named("nodeSetup"), tasks.named("npmSetup"))
    from(npmLibDir)
    into(layout.projectDirectory.dir("node_modules/lib/node_modules/npm"))
}

val stripNpmBins by tasks.registering(Delete::class) {
    dependsOn(npmInstallTask)
    delete(
        layout.projectDirectory.file("node_modules/.bin/npm"),
        layout.projectDirectory.file("node_modules/.bin/npm.cmd"),
        layout.projectDirectory.file("node_modules/.bin/npm.ps1"),
        layout.projectDirectory.file("node_modules/.bin/npm-prefix"),
        layout.projectDirectory.file("node_modules/.bin/npm-prefix.cmd"),
        layout.projectDirectory.file("node_modules/.bin/npm-prefix.ps1"),
        layout.projectDirectory.file("node_modules/.bin/npx"),
        layout.projectDirectory.file("node_modules/.bin/npx.cmd"),
        layout.projectDirectory.file("node_modules/.bin/npx.ps1")
    )
}

val npmInstallTask = tasks.named("npmInstall")

val artifactExtensionByPlatform = mapOf(
    "windows" to "exe",
    "mac" to "dmg",
    "linux" to "AppImage",
)

fun TaskProvider<NpmTask>.withArtifactRenaming(): TaskProvider<NpmTask> {
    configure {
        doLast {
            renameElectronArtifacts(electronOutputDir.get().asFile, artifactExtensionByPlatform)
        }
    }
    return this
}

fun renameElectronArtifacts(outputDir: File, extensionMap: Map<String, String>) {
    if (!outputDir.exists()) {
        return
    }
    val artifactRegex = Regex("""Autogenesis-([^-]+)-(.+?)\.zip""")

    outputDir.listFiles { file ->
        file.isFile && file.extension == "zip"
    }?.forEach { zipFile ->
        val match = artifactRegex.matchEntire(zipFile.name) ?: return@forEach
        val platformKey = match.groupValues[1].lowercase()
        val baseName = zipFile.name.removeSuffix(".zip")
        val suffix = baseName.substringAfterLast('.', "")
        val hasExplicitExtension = suffix.isNotEmpty() && suffix.any { !it.isDigit() }
        val targetName = if (hasExplicitExtension) {
            baseName
        } else {
            val resolvedExt = extensionMap[platformKey] ?: return@forEach
            "$baseName.$resolvedExt"
        }
        val targetFile = File(outputDir, targetName)
        if (targetFile.exists()) {
            logger.lifecycle("Skipping rename of ${zipFile.name} because $targetName already exists")
            return@forEach
        }
        if (!zipFile.renameTo(targetFile)) {
            throw GradleException("Unable to rename ${zipFile.name} to $targetName")
        }
        logger.lifecycle("Renamed ${zipFile.name} → $targetName")
        val blockmapFile = File(outputDir, "${zipFile.name}.blockmap")
        if (blockmapFile.exists()) {
            val targetBlockmap = File(outputDir, "$targetName.blockmap")
            if (!blockmapFile.renameTo(targetBlockmap)) {
                logger.warn("Failed to rename ${blockmapFile.name} → ${targetBlockmap.name}")
            }
        }
    }
}

fun registerElectronPackageTask(name: String, script: String) = tasks.register<NpmTask>(name) {
    dependsOn(cleanBuild)
    dependsOn(npmInstallTask, stageResources)
    dependsOn(prepareNpmLib)
    dependsOn(stripNpmBins)
    args.set(listOf("run", script))
}.withArtifactRenaming()

val packageLinux by registerElectronPackageTask("packageLinux", "package:linux")
val packageWindows by registerElectronPackageTask("packageWindows", "package:windows")
val packageMac by registerElectronPackageTask("packageMac", "package:mac")

tasks.register("packageElectronAll") {
    group = "electron"
    description = "Builds both linux and windows electron artifacts"
    dependsOn(packageLinux, packageWindows)
}