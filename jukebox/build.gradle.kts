plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar

kotlin {
    js(IR) {
        browser()
        binaries.executable()
        compilerOptions {
            freeCompilerArgs.add("-Xes-long-as-bigint")
        }
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(project(":sharedModel"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
    }
}

dependencies {
    add("kspJs", project(":rpc-ksp"))
}

// -------------------------------------------------------------------------
// Web UI + audio asset packaging
// -------------------------------------------------------------------------
// Kotlin/JS executable mode emits only a single bundle (jukebox.js) to
// build/kotlin-webpack/js/productionExecutable/. It does NOT copy the web
// UI (index.html, app.js, styles.css) or audio MP3s from src/jsMain/resources
// into the output directory. Without those assets the browser cannot load
// the page or fetch audio buffers, so audio playback fails at runtime even
// though the engine itself is wired up correctly.
//
// The tasks below copy the web UI and audio into the productionExecutable
// output directory, producing a complete self-contained dist that can be
// served by any static HTTP server.
// -------------------------------------------------------------------------

val productionExecutableOutputDir =
    layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")

// The Kotlin/JS plugin's `jsBrowserDistribution` Sync task syncs BOTH
// `processedResources/js/main` (which contains jukebox/index.html etc.
// from src/jsMain/resources/jukebox/) AND `kotlin-webpack/js/productionExecutable`
// into the dist output. This produces duplicate entries that fail the Sync
// task, so we clean and replace the dist with a flat layout after the
// Sync runs.
//
// The `copyJukeboxWebUi` task:
//   1. Deletes the stale `jukebox/` subfolder that the Sync task left behind
//   2. Copies the three web UI files flat to the dist root
//   3. Re-creates the audio directory (which is preserved by the Sync)
//
// It runs `mustRunAfter jsBrowserDistribution` so the Sync finishes first.
val distOutputDir = layout.buildDirectory.dir("dist/js/productionExecutable")
val copyJukeboxWebUi = tasks.register<Copy>("copyJukeboxWebUi") {
    group = "build"
    description = "Cleans the stale jukebox/ subfolder in the dist and writes a flat web UI layout"
    // Step 1: Delete the stale `jukebox/` subfolder that the Sync task created
    doFirst {
        val staleJukeboxDir = file("${distOutputDir.get().asFile}/jukebox")
        if (staleJukeboxDir.exists()) {
            staleJukeboxDir.deleteRecursively()
            logger.lifecycle("copyJukeboxWebUi: removed stale dist/jukebox/ subfolder")
        }
    }
    // Step 2: Copy web UI files flat to dist root
    from("src/jsMain/resources/jukebox/index.html")
    from("src/jsMain/resources/jukebox/app.js")
    from("src/jsMain/resources/jukebox/styles.css")
    into(distOutputDir)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mustRunAfter("jsBrowserDistribution")
}

// Make the assemble task (which `:jukebox:build` depends on) wait on the
// web UI copy so a plain `./gradlew :jukebox:build` produces a complete dist.
tasks.named("assemble") {
    dependsOn(copyJukeboxWebUi)
}

// Convenience aggregate: build + copy all assets in one command.
val packageJukeboxDistribution = tasks.register("packageJukeboxDistribution") {
    group = "build"
    description = "Builds the Kotlin/JS bundle and copies web UI into the dist output"
    dependsOn("assemble", copyJukeboxWebUi)
}