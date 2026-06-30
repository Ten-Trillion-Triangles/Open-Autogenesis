plugins {
    alias(libs.plugins.kotlinJvm)
    id("com.google.devtools.ksp")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(24)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    // The compilerOptions DSL in the kotlin block handles this globally.
    // This block can be removed or left empty if no other specific compiler options are needed per task.
}

dependencies {
    implementation(project(":sharedModel"))
    implementation(libs.autoService)
    implementation(libs.org.jetbrains.kotlin.reflect)
    implementation(libs.symbolProcessingApi)
}
