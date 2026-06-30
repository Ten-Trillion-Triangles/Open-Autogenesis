import java.io.File
import java.time.Duration
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    js(IR) {
        browser()
        nodejs()
        binaries.library()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val jsMain by getting {
            dependencies {
                val accelbyteDist = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk").absolutePath
                val accelbyteDistIam = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-iam").absolutePath
                val accelbyteDistSession = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-session").absolutePath
                val accelbyteDistCloudsave = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-cloudsave").absolutePath
                val accelbyteDistLobby = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-lobby").absolutePath
                val accelbyteDistMatchmaking = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-matchmaking").absolutePath
                val accelbyteDistChat = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-chat").absolutePath
                implementation(npm("@accelbyte/sdk", "file:$accelbyteDist"))
                implementation(npm("@accelbyte/sdk-iam", "file:$accelbyteDistIam"))
                implementation(npm("@accelbyte/sdk-session", "file:$accelbyteDistSession"))
                implementation(npm("@accelbyte/sdk-cloudsave", "file:$accelbyteDistCloudsave"))
                implementation(npm("@accelbyte/sdk-lobby", "file:$accelbyteDistLobby"))
                implementation(npm("@accelbyte/sdk-matchmaking", "file:$accelbyteDistMatchmaking"))
                implementation(npm("@accelbyte/sdk-chat", "file:$accelbyteDistChat"))
                val accelbyteDistLeaderboard = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-leaderboard").absolutePath
                val accelbyteDistPlatform = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-platform").absolutePath
                val accelbyteDistSocial = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-social").absolutePath
                val accelbyteDistUgc = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-ugc").absolutePath
                implementation(npm("@accelbyte/sdk-leaderboard", "file:$accelbyteDistLeaderboard"))
                implementation(npm("@accelbyte/sdk-platform", "file:$accelbyteDistPlatform"))
                implementation(npm("@accelbyte/sdk-social", "file:$accelbyteDistSocial"))
                implementation(npm("@accelbyte/sdk-ugc", "file:$accelbyteDistUgc"))
                val accelbyteDistAchievement = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-achievement").absolutePath
                implementation(npm("@accelbyte/sdk-achievement", "file:$accelbyteDistAchievement"))
                val accelbyteDistBasic = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-basic").absolutePath
                implementation(npm("@accelbyte/sdk-basic", "file:$accelbyteDistBasic"))
                val accelbyteDistBuildinfo = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-buildinfo").absolutePath
                implementation(npm("@accelbyte/sdk-buildinfo", "file:$accelbyteDistBuildinfo"))
                val accelbyteDistEvent = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-event").absolutePath
                implementation(npm("@accelbyte/sdk-event", "file:$accelbyteDistEvent"))
                val accelbyteDistCsm = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-csm").absolutePath
                implementation(npm("@accelbyte/sdk-csm", "file:$accelbyteDistCsm"))
                val accelbyteDistDiffer = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-differ").absolutePath
                implementation(npm("@accelbyte/sdk-differ", "file:$accelbyteDistDiffer"))
                val accelbyteDistGdpr = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-gdpr").absolutePath
                implementation(npm("@accelbyte/sdk-gdpr", "file:$accelbyteDistGdpr"))
                val accelbyteDistGroups = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-groups").absolutePath
                implementation(npm("@accelbyte/sdk-groups", "file:$accelbyteDistGroups"))
                val accelbyteDistDsm = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-dsmcontroller").absolutePath
                implementation(npm("@accelbyte/sdk-dsmcontroller", "file:$accelbyteDistDsm"))
                val accelbyteDistGameTelemetry = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-gametelemetry").absolutePath
                implementation(npm("@accelbyte/sdk-gametelemetry", "file:$accelbyteDistGameTelemetry"))
                val accelbyteDistLegal = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-legal").absolutePath
                implementation(npm("@accelbyte/sdk-legal", "file:$accelbyteDistLegal"))
                val accelbyteDistQosm = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-qosmanager").absolutePath
                implementation(npm("@accelbyte/sdk-qosmanager", "file:$accelbyteDistQosm"))
                val accelbyteDistReporting = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-reporting").absolutePath
                implementation(npm("@accelbyte/sdk-reporting", "file:$accelbyteDistReporting"))
                val accelbyteDistSeasonpass = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-seasonpass").absolutePath
                implementation(npm("@accelbyte/sdk-seasonpass", "file:$accelbyteDistSeasonpass"))
                val accelbyteDistConfig = File(rootDir, "accelbyte/accelbyte-typescript-sdk/packages/sdk-config").absolutePath
                implementation(npm("@accelbyte/sdk-config", "file:$accelbyteDistConfig"))
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

val accelbyteTypescriptSdkRepo: String by project
val accelbyteTypescriptSdkBranch = providers.gradleProperty("accelbyteTypescriptSdkBranch").orNull?.takeIf { it.isNotBlank() }
val forceAccelbyteSdkDownload = providers.gradleProperty("forceAccelbyteSdkDownload").orElse("false").map { it.toBoolean() }
val accelbyteSdkBuildScript =
    providers.gradleProperty("accelbyteSdkBuildScript").orElse("workspaces foreach --recursive --topological run build --formats cjs,esm")
val accelbyteSdkBuildCommand = accelbyteSdkBuildScript.map { script ->
    script.split("\\s+".toRegex()).filter { it.isNotBlank() }
}
val accelbyteTypescriptSdkMirror = providers.gradleProperty("accelbyteTypescriptSdkMirror").orNull?.takeIf { it.isNotBlank() }

val accelbyteDir = rootDir.resolve("accelbyte").apply { if (!exists()) mkdirs() }
val accelbyteTypescriptSdkDir = accelbyteDir.resolve("accelbyte-typescript-sdk")
val accelbyteSdkPatchFile = rootDir.resolve("accelbyteSdk/patches/accelbyte-sdk-deps.patch")

val needsSdkDownload = providers.provider {
    forceAccelbyteSdkDownload.get() ||
        !accelbyteTypescriptSdkDir.exists() ||
        (accelbyteTypescriptSdkDir.listFiles()?.isEmpty() == true)
}

val downloadAccelbyteSdk = tasks.register("downloadAccelbyteSdk") {
    group = "accelbyte"
    description = "Clones the AccelByte TypeScript SDK if it is missing or was explicitly requested."
    
    inputs.property("repo", accelbyteTypescriptSdkRepo)
    inputs.property("branch", accelbyteTypescriptSdkBranch ?: "main")
    inputs.property("mirror", accelbyteTypescriptSdkMirror ?: "")
    inputs.property("forceDownload", forceAccelbyteSdkDownload)
    
    outputs.dir(accelbyteTypescriptSdkDir)
    outputs.upToDateWhen { 
        accelbyteTypescriptSdkDir.resolve("package.json").exists() && 
        accelbyteTypescriptSdkDir.resolve(".git").exists() &&
        !forceAccelbyteSdkDownload.get()
    }
    
    onlyIf { 
        needsSdkDownload.get() && !accelbyteTypescriptSdkDir.resolve("package.json").exists()
    }
    doFirst {
        if (accelbyteTypescriptSdkDir.exists()) {
            delete(accelbyteTypescriptSdkDir)
        }
    }
    doLast {
        try {
            if (accelbyteTypescriptSdkMirror != null) {
                exec {
                    environment = System.getenv() as MutableMap<String, Any>
                    timeout = Duration.ofMinutes(10)
                    commandLine(
                        "git",
                        "clone",
                        "--depth",
                        "1",
                        accelbyteTypescriptSdkMirror,
                        accelbyteTypescriptSdkDir.absolutePath,
                    )
                }
            } else {
                val command = mutableListOf("git", "clone", "--depth", "1")
                accelbyteTypescriptSdkBranch?.let { command += listOf("--branch", it) }
                command += listOf(accelbyteTypescriptSdkRepo, accelbyteTypescriptSdkDir.absolutePath)
                exec {
                    environment = System.getenv() as MutableMap<String, Any>
                    timeout = Duration.ofMinutes(10)
                    commandLine(command)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to download AccelByte SDK: ${e.message}")
            logger.warn("You can run './setup-sdk.sh' manually to set up the SDK")
        }
    }
}

val updateAccelbyteSdk = tasks.register("updateAccelbyteSdk") {
    group = "accelbyte"
    description = "Updates the AccelByte TypeScript SDK (skipped if package.json exists)."
    onlyIf { 
        accelbyteTypescriptSdkDir.resolve(".git").exists() && 
        !forceAccelbyteSdkDownload.get() &&
        !accelbyteTypescriptSdkDir.resolve("package.json").exists()
    }
    doLast {
        try {
            // For shallow clones, just re-clone to get latest
            if (accelbyteTypescriptSdkDir.exists()) {
                delete(accelbyteTypescriptSdkDir)
            }
            
            if (accelbyteTypescriptSdkMirror != null) {
                exec {
                    environment = System.getenv() as MutableMap<String, Any>
                    timeout = Duration.ofMinutes(10)
                    commandLine(
                        "git",
                        "clone",
                        "--depth",
                        "1",
                        accelbyteTypescriptSdkMirror,
                        accelbyteTypescriptSdkDir.absolutePath,
                    )
                }
            } else {
                val command = mutableListOf("git", "clone", "--depth", "1")
                accelbyteTypescriptSdkBranch?.let { command += listOf("--branch", it) }
                command += listOf(accelbyteTypescriptSdkRepo, accelbyteTypescriptSdkDir.absolutePath)
                exec {
                    environment = System.getenv() as MutableMap<String, Any>
                    timeout = Duration.ofMinutes(10)
                    commandLine(command)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to update AccelByte SDK: ${e.message}")
            logger.warn("You can run './setup-sdk.sh' manually to set up the SDK")
        }
    }
    mustRunAfter(downloadAccelbyteSdk)
}

val buildAccelbyteSdk = tasks.register("buildAccelbyteSdk") {
    group = "accelbyte"
    description = "Installs npm dependencies and builds the AccelByte TypeScript SDK artifacts."
    dependsOn(downloadAccelbyteSdk, updateAccelbyteSdk)
    
    inputs.dir(accelbyteTypescriptSdkDir).withPropertyName("sdkSource")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(accelbyteTypescriptSdkDir.resolve("package.json"), accelbyteTypescriptSdkDir.resolve("yarn.lock"))
        .withPropertyName("packageFiles")
    inputs.file(accelbyteSdkPatchFile).withPropertyName("localPatch")
    
    outputs.dirs(
        accelbyteTypescriptSdkDir.resolve("packages/sdk/dist"),
        accelbyteTypescriptSdkDir.resolve("packages/sdk-iam/dist"),
        accelbyteTypescriptSdkDir.resolve("node_modules")
    )
    
    onlyIf { accelbyteTypescriptSdkDir.resolve("package.json").exists() }

    doFirst {
        if (accelbyteSdkPatchFile.exists()) {
            exec {
                workingDir(accelbyteTypescriptSdkDir)
                isIgnoreExitValue = true
                commandLine(
                    "git",
                    "restore",
                    "--",
                    "packages/sdk/src/utils/Network.ts",
                    "packages/sdk/package.json",
                    "packages/sdk/tsup.config.ts",
                    "packages/sdk-iam/src/custom/clients/InputValidationHelper.ts",
                    "packages/validator/package.json",
                    "tsupconfig.base.ts"
                )
            }
            exec {
                workingDir(accelbyteTypescriptSdkDir)
                commandLine(
                    "bash",
                    "-lc",
                    """python3 - <<'PY'
from pathlib import Path
base = Path('tsupconfig.base.ts')
base.write_text('''import { defineConfig } from 'tsup'

const mapped = {
  '@accelbyte/validator': {
    name: 'abValidator',
    namedImports: [
      'validateDisplayName',
      'validateEmail',
      'validateForbiddenWords',
      'ValidateForbiddenWordsErrorType',
      'validateLength',
      'ValidateLengthErrorType',
      'validateNotEmpty',
      'validatePassword',
      'validateRegex',
      'ValidateRegexErrorType',
      'validateUserName'
    ]
  },
  axios: { name: 'axios' },
  buffer: { name: 'buffer', namedImports: ['Buffer'] },
  'crypto-js': { name: 'cryptojs' },
  platform: { name: 'platform' },
  uuid: { name: 'uuid', namedImports: ['v4'] },
  validator: { name: 'validator' },
  zod: { name: 'zod', namedImports: ['z'] }
}

export default defineConfig([
  {
    entry: ['src/index.ts', 'src/all-query-imports.ts'],
    format: ['cjs', 'esm'],
    dts: true
  }
])
''')
sdk = Path('packages/sdk/tsup.config.ts')
sdk.write_text('''import { defineConfig } from "tsup";

export default defineConfig([
  // Node CJS build
  {
    entry: { "node/index": "src/index.node.ts" },
    format: ["cjs"],
    outDir: "dist/cjs",
    target: "node14",
    sourcemap: true,
    splitting: false,
    clean: true,
    tsconfig: "./tsconfig.build.json",
  },
  // ES build for Node
  {
    entry: { "node/index.node": "src/index.node.ts" },
    format: ["esm"],
    outDir: "dist/es",
    target: "node14",
    sourcemap: true,
    splitting: false,
    tsconfig: "./tsconfig.build.json",
  },
  // ES build for Browser
  {
    entry: { "browser/index.browser": "src/index.browser.ts" },
    format: ["esm"],
    outDir: "dist/es",
    target: "es2020",
    sourcemap: true,
    splitting: false,
    tsconfig: "./tsconfig.build.json",
  }
]);
''')
PY"""
                )
            }
            exec {
                workingDir(accelbyteTypescriptSdkDir)
                commandLine(
                    "bash",
                    "-lc",
                    """python3 - <<'PY'
import json
from pathlib import Path

vp = Path('packages/validator/package.json')
data = json.loads(vp.read_text())

# Move validator and zod from devDependencies to dependencies
if 'validator' in data.get('devDependencies', {}):
    del data['devDependencies']['validator']
if 'zod' in data.get('devDependencies', {}):
    del data['devDependencies']['zod']

data.setdefault('dependencies', {})['validator'] = '13.7.0'
data.setdefault('dependencies', {})['zod'] = '3.23.8'

vp.write_text(json.dumps(data, indent=2) + '\n')
PY"""
                )
            }
        }
    }

    doLast {
        exec {
            workingDir(accelbyteTypescriptSdkDir)
            commandLine("yarn", "install")
        }
        exec {
            workingDir(accelbyteTypescriptSdkDir)
            commandLine(listOf("yarn") + accelbyteSdkBuildCommand.get())
        }
    }
}

val regenerateAccelbyteBindings = tasks.register("regenerateAccelbyteBindings") {
    group = "accelbyte"
    description = "Runs Dukat to refresh the Kotlin bindings from the AccelByte TypeScript SDK."
    dependsOn(buildAccelbyteSdk)
    
    inputs.dir(accelbyteTypescriptSdkDir.resolve("packages")).withPropertyName("typescriptSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("scripts/regenerate-bindings.sh")).withPropertyName("regenerateScript")
    
    outputs.dir(projectDir.resolve("src")).withPropertyName("kotlinBindings")
    
    onlyIf { file("scripts/regenerate-bindings.sh").exists() }
    doLast {
        exec {
            workingDir(rootDir)
            commandLine("bash", "scripts/regenerate-bindings.sh")
        }
    }
}

tasks.register("refreshAccelbyteBindings") {
    group = "accelbyte"
    description = "Downloads/builds the SDK and regenerates the Kotlin bindings via Dukat."
    dependsOn(regenerateAccelbyteBindings)
}

tasks.withType<KotlinNpmInstallTask>().configureEach {
    dependsOn(buildAccelbyteSdk)
}

project.rootProject.tasks.withType<KotlinNpmInstallTask>().configureEach {
    dependsOn(buildAccelbyteSdk)
}
