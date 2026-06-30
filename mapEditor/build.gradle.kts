plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kvision)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting
        val jsMain by getting {
                dependencies {
                    implementation(project(":sharedModel"))
                    implementation(libs.kvision)
                implementation(libs.kvisionJs)
                implementation(libs.kvisionBootstrap)
                implementation(libs.kotlinxSerializationJsonLib)
                implementation(libs.kotlinx.coroutines.core)
                implementation("io.kvision:kvision-fontawesome:9.1.1")
            }
        }
        val commonTest by getting
        val jsTest by getting
    }
}
