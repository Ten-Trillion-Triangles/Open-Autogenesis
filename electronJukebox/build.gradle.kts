import com.github.gradle.node.npm.task.NpmTask
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider

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
// jukebox uses the `packageJukeboxDistribution` task which produces a complete flat dist
// (index.html, app.js, styles.css, jukebox.js, audio/) at dist/js/productionExecutable.
// Unlike kvisionApp, there is no separate processedResources dir to merge.
val jukeboxDistDir = rootProject.file("jukebox/build/dist/js/productionExecutable")

val stageJukeboxDist by tasks.registering(Copy::class) {
    group = "build"
    description = "Stages the jukebox dist (HTML/CSS/JS bundle + audio assets) into the staging dir"
    dependsOn(":jukebox:packageJukeboxDistribution")
    doFirst {
        delete(destinationDir)
    }
    from(jukeboxDistDir)
    into(stagingDir.map { it.dir("frontend") })
}

val stageConfig by tasks.registering {
    group = "build"
    description = "Writes a minimal app-config.json (jukebox is standalone; no AccelByte config required)"
    // The jukebox dev tool does not need any game-server configuration.
    // We still emit a placeholder config file so downstream consumers (Phase 2's main.js)
    // can rely on the file's existence at build/staging/config/app-config.json.
    val configFile = layout.buildDirectory.file("staging/config/app-config.json")
    outputs.file(configFile)
    doLast {
        val outFile = configFile.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText("{}\n")
        logger.lifecycle("Wrote placeholder jukebox app-config.json to ${outFile.absolutePath}")
    }
}

val stageResources by tasks.registering {
    group = "build"
    description = "Aggregates all staging tasks (jukebox dist + config) for the electron package tasks"
    dependsOn(
        stageJukeboxDist,
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

fun renameElectronArtifacts(outputDir: java.io.File, extensionMap: Map<String, String>) {
    if (!outputDir.exists()) {
        return
    }
    val artifactRegex = Regex("""Autogenesis-Jukebox-([^-]+)-(.+?)\.zip""")

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
        val targetFile = java.io.File(outputDir, targetName)
        if (targetFile.exists()) {
            logger.lifecycle("Skipping rename of ${zipFile.name} because $targetName already exists")
            return@forEach
        }
        if (!zipFile.renameTo(targetFile)) {
            throw org.gradle.api.GradleException("Unable to rename ${zipFile.name} to $targetName")
        }
        logger.lifecycle("Renamed ${zipFile.name} -> $targetName")
        val blockmapFile = java.io.File(outputDir, "${zipFile.name}.blockmap")
        if (blockmapFile.exists()) {
            val targetBlockmap = java.io.File(outputDir, "$targetName.blockmap")
            if (!blockmapFile.renameTo(targetBlockmap)) {
                logger.warn("Failed to rename ${blockmapFile.name} -> ${targetBlockmap.name}")
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

tasks.register("packageJukeboxElectronAll") {
    group = "electron"
    description = "Builds both linux and windows jukebox electron artifacts"
    dependsOn(packageLinux, packageWindows)
}