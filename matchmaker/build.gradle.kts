import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    // Phase 1 of feature/live-pvp-and-billing: protobuf-gradle-plugin powers
    // the matchmaker gRPC transport. The version is pinned to match the
    // protobuf-java runtime declared in `dependencies { ... }` below.
    id("com.google.protobuf") version "0.9.4"
    application
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
    applicationName = "matchmaker"
    mainClass.set("org.ttt.autogenesis.matchmaker.MatchmakerMainKt")
    applicationDefaultJvmArgs = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

group = "org.ttt.autogenesis"
version = "0.1.0"

// Hoisted so the protobuf {} script block below can read them.
val kotlinxCoroutinesVersion = "1.10.2"
val kotlinxSerializationVersion = "1.8.0"
val grpcVersion = "1.57.2"
val protobufVersion = "3.25.3"

dependencies {
    implementation(project(":sharedModel"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")

    // gRPC runtime + protobuf codegen. Versions kept in lockstep with the
    // existing `protobuf-java:3.25.3` baseline; the `grpc-kotlin-stub` line
    // lets us implement the server with a coroutine base class.
    "implementation"("io.grpc:grpc-stub:$grpcVersion")
    "implementation"("io.grpc:grpc-protobuf:$grpcVersion")
    "implementation"("io.grpc:grpc-protobuf-lite:$grpcVersion")
    "implementation"("io.grpc:grpc-netty-shaded:$grpcVersion")
    "implementation"("io.grpc:grpc-kotlin-stub:1.4.1")
    // Standard `grpc.health.v1.Health` service implementation. Required so
    // the k8s `grpc_health_probe` and the in-container `HEALTHCHECK` can
    // both report SERVING/NOT_SERVING on the gRPC port.
    "implementation"("io.grpc:grpc-services:$grpcVersion")
    "implementation"("com.google.protobuf:protobuf-java:$protobufVersion")
    "implementation"("com.google.protobuf:protobuf-kotlin-lite:$protobufVersion")
    "compileOnly"("org.apache.tomcat:annotations-api:6.0.53") // for @Generated

    testImplementation(libs.kotlin.test)
    testImplementation("org.junit.vintage:junit-vintage-engine:5.12.2")
    testImplementation("io.mockk:mockk:1.14.5")
    // InProcessServerBuilder is in io.grpc.grpc-core; grpc-testing + grpc-stub
    // already pull it in transitively, so we don't need a separate
    // grpc-inprocess dependency (which is not published for 1.57.2 on Maven
    // Central).
    testImplementation("io.grpc:grpc-testing:$grpcVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
}

// Phase 1 of feature/live-pvp-and-billing: generate Kotlin + Java sources from
// the vendored match2 proto. The Java package `net.accelbyte.matchmaker.proto`
// (set in the .proto) maps to the Kotlin package of the same name.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc") {
                    option("lite")
                }
                create("grpckt") {
                    option("lite")
                }
            }
            // The default `java` builtin already runs at full-fat (non-lite);
            // to keep the generated message classes lite-only we omit the
            // explicit `create("java")` and rely on the protobuf-kotlin-lite
            // runtime artifact for the JVM. The `kotlin` builtin generates
            // DSL-style Kotlin builders; we add it with the lite option so
            // the generated stubs use the lite runtime.
            builtins {
                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

// Add the generated proto sources to the main source set so `compileKotlin`
// picks them up.
sourceSets {
    main {
        java {
            srcDirs(
                layout.buildDirectory.dir("generated/source/proto/main/java"),
                layout.buildDirectory.dir("generated/source/proto/main/grpc"),
                layout.buildDirectory.dir("generated/source/proto/main/grpckt"),
                layout.buildDirectory.dir("generated/source/proto/main/kotlin")
            )
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

// -----------------------------------------------------------------------------
// Docker image build/push (Extend deployment plumbing).
//
// `:matchmaker:installDist` is the existing artifact we wrap; the Dockerfile
// in this module root copies `build/install/matchmaker/` into the image and
// sets `bin/entrypoint.sh` as the entrypoint so PID 1 == the JVM.
//
// The image name is configurable via the `docker.image` Gradle property
// (default `ghcr.io/cage/autogenesis-matchmaker`); the tag is `<short-sha>`
// when available and `dev` otherwise. Operators set
// `-Pdocker.registry=…` to push somewhere other than the default.
// -----------------------------------------------------------------------------

val dockerImage: String = (project.findProperty("docker.image") as String?)
    ?: "ghcr.io/cage/autogenesis-matchmaker"
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
    description = "Builds the matchmaker Docker image from the current build/install/matchmaker output."

    dependsOn("installDist")
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
    description = "Pushes the matchmaker Docker image to the configured registry (default: ghcr.io/cage/autogenesis-matchmaker)."

    dependsOn(dockerBuildImage)
    val doPush = (project.findProperty("docker.push") as String?)?.toBoolean() ?: false
    onlyIf { doPush }
    commandLine = listOf("docker", "push", "$dockerImage:$dockerTag")
}