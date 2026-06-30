plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

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
