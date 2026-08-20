rootProject.name = "Autogenesis"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":accelbyteSdk")
include(":kvisionApp")
include(":mapEditor")
include(":matchmaker")
include(":server")
include(":server-extend")
include(":sharedModel")
include(":rpc-ksp")
include(":electronApp")
include(":electronJukebox")
include(":jukebox")
include(":audioTracksEditor")


// Look for TPipe with fallback logic - check up to 4 directories above using relative paths
fun findTPipeWithFallback(): File? {
    var currentDir = rootDir.canonicalFile
    
    // Go up to 4 directories above the build file
    repeat(4) {
        currentDir = currentDir.parentFile ?: return null
        
        // Try nested structure first: TPipe/TPipe
        val nestedTPipe = File(currentDir, "TPipe${File.separator}TPipe")
        if (nestedTPipe.exists() && nestedTPipe.isDirectory && File(nestedTPipe, "build.gradle.kts").exists()) {
             val canonical = nestedTPipe.canonicalFile
             if (canonical != rootDir.canonicalFile) {
                 println("Settings: Found TPipe (nested) at $canonical")
                 return canonical
             }
        }
        
        // Try flat structure: TPipe
        val flatTPipe = File(currentDir, "TPipe")
        if (flatTPipe.exists() && flatTPipe.isDirectory && File(flatTPipe, "build.gradle.kts").exists()) {
            val canonical = flatTPipe.canonicalFile
             if (canonical != rootDir.canonicalFile) {
                 println("Settings: Found TPipe (flat) at $canonical")
                 return canonical
             }
        }
    }
    
    return null
}

val tPipeDir = findTPipeWithFallback()

if (tPipeDir != null) {
    println("Settings: Including TPipe build from $tPipeDir")
    includeBuild(tPipeDir)
} else {
    logger.warn("TPipe project not found within 4 directory levels above ${rootDir.name}")
}