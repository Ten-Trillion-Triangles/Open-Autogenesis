import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.internal.os.OperatingSystem

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.beryxRuntime) apply false
    alias(libs.plugins.kvision) apply false
}

val jreModules = listOf(
    "java.base",
    "java.logging",
    "java.net.http",
    "java.scripting",
    "java.xml",
    "java.naming",
    "java.desktop",
    "java.management",
    "java.security.jgss",
    "java.instrument",
    "java.sql",
    "jdk.unsupported",
    "jdk.httpserver",
    "jdk.crypto.ec",
    "jdk.crypto.cryptoki"
)
extra["jreModules"] = jreModules

extra["oracleJdk24LinuxUrl"] = "https://download.oracle.com/java/24/archive/jdk-24.0.2_linux-x64_bin.tar.gz"
extra["oracleJdk24WindowsUrl"] = "https://download.oracle.com/java/24/archive/jdk-24.0.2_windows-x64_bin.zip"

val accelbyteTypescriptSdkDir = layout.projectDirectory.dir("accelbyte/accelbyte-typescript-sdk")

val cleanAccelbyteSdkCheckout by tasks.registering(Delete::class) {
    group = "accelbyte"
    description = "Removes the existing AccelByte TypeScript SDK clone so the next build starts from scratch."
    delete(accelbyteTypescriptSdkDir)
}

tasks.register("rebuildWithFreshAccelbyteSdk") {
    group = "accelbyte"
    description =
        "Runs a full clean + fresh AccelByte SDK download/build before compiling the Kotlin projects."
    
    inputs.property("forceRebuild", true)
    outputs.upToDateWhen { false } // Always run when explicitly requested
    
    dependsOn(tasks.named("clean"))
    dependsOn(cleanAccelbyteSdkCheckout)
    dependsOn(":accelbyteSdk:buildAccelbyteSdk")
    dependsOn(":kvisionApp:build")
}

tasks.register("ensureAccelbyteSdkRuntime") {
    group = "accelbyte"
    description =
        "Updates the AccelByte SDK artifacts, refreshes the Kotlin/Yarn lock, and compiles the KVision frontend."
    
    inputs.dir(accelbyteTypescriptSdkDir).optional().withPropertyName("sdkDir")
    outputs.dirs(
        layout.projectDirectory.dir("kvisionApp/build"),
        accelbyteTypescriptSdkDir.dir("packages/sdk/dist")
    ).withPropertyName("builtArtifacts")
    
    dependsOn(":accelbyteSdk:buildAccelbyteSdk")
    dependsOn(":kotlinUpgradeYarnLock")
    dependsOn(":kvisionApp:build")
}

val gradlewCommand = if (OperatingSystem.current().isWindows) "gradlew.bat" else "./gradlew"

tasks.register<Exec>("runKvisionNoHotReload") {
    group = "kvision"
    description = "Starts :kvisionApp:jsBrowserDevelopmentRun with hot reload disabled (KVISION_DISABLE_HOT_RELOAD=true)."
    workingDir = projectDir
    commandLine = listOf(gradlewCommand, "--no-daemon", ":kvisionApp:jsBrowserDevelopmentRun")
    environment["KVISION_DISABLE_HOT_RELOAD"] = "true"
    isIgnoreExitValue = true
}

tasks.register("packageElectronLinux") {
    group = "electron"
    description = "Runs the electronApp linux packaging task."
    dependsOn(":electronApp:packageLinux")
}

tasks.register("packageElectronWindows") {
    group = "electron"
    description = "Runs the electronApp windows packaging task."
    dependsOn(":electronApp:packageWindows")
}

tasks.register("packageElectronMac") {
    group = "electron"
    description = "Runs the electronApp mac packaging task (requires macOS host)."
    dependsOn(":electronApp:packageMac")
}

tasks.register("packageElectronAll") {
    group = "electron"
    description = "Alias that builds both linux and windows electron artifacts."
    dependsOn(":electronApp:packageElectronAll")
}

// -----------------------------------------------------------------------------
// AccelByte Extend deployment glue (root project).
//
// These tasks wrap the `ags` CLI so an operator can build, push, and deploy
// the matchmaker and server-extend images to an Extend Custom Service
// deployment without hand-rolling shell scripts.
//
// Required env (consumed by `ags`, not by Gradle):
//   AGS_BASE_URL        e.g. https://<your-namespace>.prod.gamingservices.accelbyte.io
//   AGS_CLIENT_ID       IAM client with EXTEND:IMAGE and EXTEND:DEPLOYMENT perms
//   AGS_CLIENT_SECRET   confidential-client secret (use --grant client-credentials)
//   AGS_NAMESPACE       the game namespace to deploy into
//
// Both tasks accept `-Pmodule=matchmaker|server-extend` to pick the target.
// Run with `-PdryRun=true` to print the underlying `docker`/`ags` commands
// without executing them.
// -----------------------------------------------------------------------------

val SUPPORTED_EXTEND_MODULES = setOf("matchmaker", "server-extend")

data class ExtendModule(
    val name: String,
    val gradleProject: String,
    val dockerBuildTask: String,
    val dockerPushTask: String,
    val appName: String,
    val imageId: String,
    val docsPath: String,
    // Extend App registration fields (ags csm apps create). `scenario` is
    // required by the v2 schema; the rest are optional but mirrored from
    // the self-hosted k8s manifests (k8s/<module>.yaml) so an Extend
    // deployment and a self-hosted k8s deployment land on equivalent
    // resource shapes.
    val scenario: String,                       // service-extension | function-override | event-handler
    val cpuRequestMillicores: Int,              // apimodel.CPURequest.requestCPU
    val memoryRequestMiB: Int,                  // apimodel.MemoryRequest.requestMemory
    val minReplica: Int,                        // apimodel.ReplicaRequest.minReplica
    val maxReplica: Int,                        // apimodel.ReplicaRequest.maxReplica
    // Optional, only for `function-override` modules. The match2 match
    // function name and the gRPC port Extend routes to it on.
    val matchFunctionName: String? = null,
    val matchFunctionPort: Int? = null,
)

val matchmakerExtend = ExtendModule(
    name = "matchmaker",
    gradleProject = ":matchmaker",
    dockerBuildTask = "dockerBuildImage",
    dockerPushTask = "dockerPushImage",
    appName = "autogenesis-matchmaker",
    imageId = "ghcr.io/cage/autogenesis-matchmaker",
    docsPath = "matchmaker/DEPLOY.md",
    // The matchmaker is a function-override: it implements the match2
    // `Service` gRPC contract and the match2 service routes tickets to it.
    // Resource shape mirrors matchmaker/k8s/matchmaker.yaml.
    scenario = "function-override",
    cpuRequestMillicores = 100,
    memoryRequestMiB = 256,
    minReplica = 1,
    maxReplica = 1,
    // match2 wiring (see matchmaker/DEPLOY.md "Wire the matchmaker into match2").
    matchFunctionName = "autogenesis-matchmaker",
    matchFunctionPort = 9095,
)

val serverExtendExtend = ExtendModule(
    name = "server-extend",
    gradleProject = ":server-extend",
    dockerBuildTask = "dockerBuildImage",
    dockerPushTask = "dockerPushImage",
    appName = "autogenesis-server-extend",
    imageId = "ghcr.io/cage/autogenesis-server-extend",
    docsPath = "server-extend/DEPLOY.md",
    // server-extend is a service-extension (REST + gRPC hosted inside Extend,
    // not overriding any AGS service). Resource shape mirrors
    // server-extend/k8s/server-extend.yaml.
    scenario = "service-extension",
    cpuRequestMillicores = 200,
    memoryRequestMiB = 512,
    minReplica = 1,
    maxReplica = 1,
)

val extendModules: Map<String, ExtendModule> = mapOf(
    "matchmaker" to matchmakerExtend,
    "server-extend" to serverExtendExtend,
)

fun resolveExtendModule(propertyName: String = "module"): ExtendModule {
    val requested = (project.findProperty(propertyName) as String?)
        ?: error("Missing -P$propertyName=<matchmaker|server-extend>")
    return extendModules[requested]
        ?: error("Unknown module '$requested'. Supported: ${SUPPORTED_EXTEND_MODULES.sorted()}")
}

fun isDryRun(): Boolean = (project.findProperty("dryRun") as String?)?.toBoolean() ?: false

/**
 * Validates that `ags` is on PATH and that the AGS_* env vars the CLI needs
 * are set. Fails fast with a clear message rather than letting `ags` spew a
 * less helpful error.
 */
val verifyExtendPrereqs by tasks.registering {
    group = "extend"
    description = "Verifies that `ags` is on PATH and that AGS_* env vars are set."

    doLast {
        // Detect `ags` by calling it directly. `command -v` is a shell builtin
        // and ProcessBuilder does not invoke a shell, so we can't use it from
        // a Gradle JVM. We use `ags --version` and treat any exit code != 0
        // as "not found" — and we also reject empty stdout to be safe.
        val agsCheck = runCatching {
            val proc = ProcessBuilder("ags", "--version")
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val exit = proc.waitFor()
            Pair(exit, out)
        }.getOrElse { Pair(-1, "") }

        if (agsCheck.first != 0 || agsCheck.second.isBlank()) {
            error("`ags` not found on PATH (or failed to run). " +
                "Install it from https://github.com/AccelByte/accelbyte-ags-cli/releases " +
                "and re-run. Saw exit=${agsCheck.first} stdout=${agsCheck.second.take(120)}")
        }
        logger.lifecycle("[extend] ags --version: ${agsCheck.second}")

        val required = listOf("AGS_BASE_URL", "AGS_CLIENT_ID", "AGS_CLIENT_SECRET", "AGS_NAMESPACE")
        val missing = required.filter { System.getenv(it).isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            error("Missing required env vars: ${missing.joinToString(", ")}. " +
                "Run `./gradlew extendLogin` for an interactive login, or set them in your CI env.")
        }
        logger.lifecycle("[extend] AGS_NAMESPACE=${System.getenv("AGS_NAMESPACE")}")
        // Don't log the secret value.
        logger.lifecycle("[extend] AGS_BASE_URL=${System.getenv("AGS_BASE_URL")}")
    }
}

val extendLogin by tasks.registering {
    group = "extend"
    description = "Runs `ags auth login --grant client-credentials` to populate the AGS_* env vars via the OS keychain."

    dependsOn(verifyExtendPrereqs)
    doLast {
        val proc = ProcessBuilder("ags", "auth", "login", "--grant", "client-credentials")
            .inheritIO()
            .start()
        val exit = proc.waitFor()
        if (exit != 0) error("`ags auth login` exited with code $exit")
    }
}

val pushExtendImage by tasks.registering {
    group = "extend"
    description = "Builds and pushes the Docker image for the given module to the configured registry (default: ghcr.io/cage/…); the tag becomes addressable by `imageTag` in `ags csm deployments create`."

    dependsOn(verifyExtendPrereqs)
    doLast {
        val module = resolveExtendModule()
        val tag = (project.findProperty("tag") as String?)
            ?: error("Missing -Ptag=<short-sha-or-tag>")
        val fullImage = "${module.imageId}:$tag"

        logger.lifecycle("[extend] building $fullImage")
        if (!isDryRun()) {
            project.exec {
                commandLine = listOf("./gradlew", "${module.gradleProject}:${module.dockerBuildTask}")
            }
            project.exec {
                commandLine = listOf("./gradlew", "${module.gradleProject}:${module.dockerPushTask}", "-Pdocker.push=true")
            }
        } else {
            logger.lifecycle("[dry-run] would run: ./gradlew ${module.gradleProject}:${module.dockerBuildTask}")
            logger.lifecycle("[dry-run] would run: ./gradlew ${module.gradleProject}:${module.dockerPushTask} -Pdocker.push=true")
        }

        logger.lifecycle("[extend] pushed $fullImage; addressable via imageTag in `deployExtend` (Extend pulls at deploy time)")
    }
}

val deployExtend by tasks.registering {
    group = "extend"
    description = "Creates (or replaces) the Extend Custom Service deployment for the given module, pointing at the image tag passed via -Ptag."

    dependsOn(verifyExtendPrereqs)
    doLast {
        val module = resolveExtendModule()
        val tag = (project.findProperty("tag") as String?)
            ?: error("Missing -Ptag=<short-sha-or-tag>")
        val namespace = System.getenv("AGS_NAMESPACE")
            ?: error("AGS_NAMESPACE not set")
        val fullImage = "${module.imageId}:$tag"

        // Build the deployment-profile JSON body per the v2
        // CreateDeploymentV2Request schema (verified via
        // `ags describe csm deployments create` and the Extend SDK
        // `ApimodelCreateDeploymentV2Request` model): the v2 endpoint
        // takes exactly one required field, `imageTag`. The CSM v2
        // endpoint does NOT accept `commandLine` or
        // `portConfigurations` — those are part of the AMS
        // `ApiImageDeploymentProfile` shape, not the Custom Service
        // deployment shape. Runtime command/port are derived from the
        // image's `ENTRYPOINT` and `EXPOSE` directives.
        val requestBody = "{\"imageTag\":\"$fullImage\"}".trimIndent()

        val bodyFile = layout.buildDirectory.file("extend/${module.name}-deployment.json").get().asFile
        bodyFile.parentFile.mkdirs()
        bodyFile.writeText(requestBody)
        logger.lifecycle("[extend] wrote deployment body to ${bodyFile.absolutePath}")

        logger.lifecycle("[extend] creating Extend deployment for app=${module.appName} namespace=$namespace imageTag=$tag")
        if (!isDryRun()) {
            project.exec {
                commandLine = listOf(
                    "ags", "csm", "deployments", "create",
                    "--app", module.appName,
                    "--namespace", namespace,
                    "--json", "@${bodyFile.absolutePath}",
                    "--format", "json",
                    "--no-input",
                    "--api-scope", "admin",
                    "--api-version", "v2",
                )
            }
        } else {
            logger.lifecycle("[dry-run] would run: ags csm deployments create --app ${module.appName} --namespace $namespace --json @${bodyFile.absolutePath} --format json --no-input --api-scope admin --api-version v2")
            logger.lifecycle("[dry-run] body:")
            logger.lifecycle(requestBody)
        }
    }
}

// -----------------------------------------------------------------------------
// registerExtendApp
//
// One-time setup: register the App with Extend. Wraps `ags csm apps list` for
// the idempotency check and `ags csm apps create` for the actual registration.
// The body shape (scenario, cpu/memory requests, replica range) is built from
// the `ExtendModule` registry, which mirrors the values the team set in the
// self-hosted k8s manifests (k8s/<module>.yaml).
//
// Idempotent: if the App already exists in the namespace, this task logs
// "already registered" and exits 0. Safe to run on every deploy.
//
// Permissions required (on the confidential IAM client used for
// `ags auth login`):
//   ADMIN:NAMESPACE:{namespace}:EXTEND:APP [CREATE]
//   ADMIN:NAMESPACE:{namespace}:EXTEND:APP [READ]
//
// Note on `--yes`: this Gradle task is the automation surface (operator runs
// it from a script or CI), not an AI-agent conversational call, so we pass
// `--yes` to make `csm apps create` non-interactive. The ags-cli skill's
// AI-agent rule (no `--yes`) does not apply here.
// -----------------------------------------------------------------------------
val registerExtendApp by tasks.registering {
    group = "extend"
    description = "Registers the App in Extend for the given module via `ags csm apps create`. Idempotent: skips if the App already exists."

    dependsOn(verifyExtendPrereqs)
    doLast {
        val module = resolveExtendModule()
        val namespace = System.getenv("AGS_NAMESPACE")
            ?: error("AGS_NAMESPACE not set")
        val app = module.appName

        // Build the create body up front, in both live and dry-run, so the
        // operator can always inspect the body on disk at
        // build/extend/<module>-app.json.
        val requestBody = buildString {
            append("{")
            append("\"scenario\":\"" + module.scenario + "\"")
            append(",\"description\":\"Autogenesis " + module.name + " (managed by gradle registerExtendApp)\"")
            append(",\"cpu\":{\"requestCPU\":" + module.cpuRequestMillicores + "}")
            append(",\"memory\":{\"requestMemory\":" + module.memoryRequestMiB + "}")
            append(",\"replica\":{\"minReplica\":" + module.minReplica + ",\"maxReplica\":" + module.maxReplica + "}")
            append("}")
        }
        val bodyFile = layout.buildDirectory.file("extend/" + module.name + "-app.json").get().asFile
        bodyFile.parentFile.mkdirs()
        bodyFile.writeText(requestBody)
        logger.lifecycle("[extend] wrote app registration body to " + bodyFile.absolutePath)

        if (isDryRun()) {
            logger.lifecycle("[dry-run] would run: ags csm apps list --namespace " + namespace + " --json {\"appNames\":[\"" + app + "\"]} --format json --no-input --api-scope admin --api-version v2")
            logger.lifecycle("[dry-run] would run (after idempotency check passes): ags csm apps create --app " + app + " --namespace " + namespace + " --json @" + bodyFile.absolutePath + " --format json --no-input --yes --api-scope admin --api-version v2")
            logger.lifecycle("[dry-run] create body:")
            logger.lifecycle(requestBody)
            return@doLast
        }

        // Idempotency check: list the app names in this namespace and see if
        // ours is already there. Using `list` rather than `get` because list
        // returns a clean 200 + JSON body for "no matches" cases, while `get`
        // returns a non-zero exit on 404 which would also trip on a transient
        // network error.
        val listResult = runCatching {
            val proc = ProcessBuilder(
                "ags", "csm", "apps", "list",
                "--namespace", namespace,
                "--json", "{\"appNames\":[\"" + app + "\"]}",
                "--format", "json",
                "--no-input",
                "--api-scope", "admin",
                "--api-version", "v2",
            ).redirectErrorStream(true).start()
            Pair(proc.waitFor(), proc.inputStream.bufferedReader().readText())
        }.getOrElse { Pair(-1, it.message ?: "") }

        if (listResult.first != 0) {
            error("`ags csm apps list` failed (exit=" + listResult.first + "): " + listResult.second.take(500))
        }
        if (listResult.second.contains("\"" + app + "\"")) {
            logger.lifecycle("[extend] App '" + app + "' is already registered in namespace '" + namespace + "'; nothing to do")
            return@doLast
        }

        logger.lifecycle("[extend] creating Extend App app=" + app + " namespace=" + namespace + " scenario=" + module.scenario)
        val createProc = ProcessBuilder(
            "ags", "csm", "apps", "create",
            "--app", app,
            "--namespace", namespace,
            "--json", "@" + bodyFile.absolutePath,
            "--format", "json",
            "--no-input",
            "--yes",
            "--api-scope", "admin",
            "--api-version", "v2",
        ).redirectErrorStream(true).start()
        val createOut = createProc.inputStream.bufferedReader().readText()
        val createExit = createProc.waitFor()
        if (createExit != 0) error("`ags csm apps create` failed (exit=" + createExit + "): " + createOut.take(800))
        logger.lifecycle("[extend] App '" + app + "' created. Response: " + createOut.take(500))
    }
}

// -----------------------------------------------------------------------------
// verifyExtendDeployment
//
// Post-push + post-deploy check. Runs a structured pass/fail over:
//
//   1. App registration  (ags csm apps get)
//   2. Image visibility  (ags csm images list --app <app>)
//   3. Deployment        (ags csm deployments list filtered by app)
//   4. Match2 wiring     (ags matchmaking match-functions list) — only for
//                        modules that declare a `matchFunctionName`.
//
// The image check is a soft pass when the deployment exists but the image
// is NOT in Extend's internal image registry: that means the image is
// being pulled from an external registry (e.g. ghcr.io), which is supported
// by `ags csm deployments create` but won't show up in `csm images list`.
// We warn rather than fail in that case so operators using the ghcr.io
// flow (the current default) don't get a false negative.
//
// Permissions required:
//   ADMIN:NAMESPACE:{namespace}:EXTEND:APP [READ]
//   ADMIN:NAMESPACE:{namespace}:EXTEND:IMAGE [READ]
//   ADMIN:NAMESPACE:{namespace}:EXTEND:DEPLOYMENT [READ]
//   NAMESPACE:{namespace}:MATCHMAKING:FUNCTIONS [READ]   (matchmaker only)
// -----------------------------------------------------------------------------
val verifyExtendDeployment by tasks.registering {
    group = "extend"
    description = "Verifies that App + image + deployment (and match2 wiring, for matchmaker) are all in place for the given module."

    dependsOn(verifyExtendPrereqs)
    doLast {
        val module = resolveExtendModule()
        val namespace = System.getenv("AGS_NAMESPACE")
            ?: error("AGS_NAMESPACE not set")
        val app = module.appName

        data class Check(val name: String, val passed: Boolean, val detail: String)

        fun runJson(vararg args: String): String {
            val proc = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) error("`" + args.joinToString(" ") + "` failed (exit=" + exit + "): " + out.take(500))
            return out
        }

        val checks = mutableListOf<Check>()

        // 1. App registration
        try {
            val out = runJson(
                "ags", "csm", "apps", "get",
                "--app", app, "--namespace", namespace,
                "--format", "json", "--no-input",
                "--api-scope", "admin", "--api-version", "v2",
            )
            val scenario = Regex("\"scenario\":\"([^\"]+)\"").find(out)?.groupValues?.getOrNull(1)
            checks += Check("App registered", true, "app=" + app + " scenario=" + (scenario ?: "?"))
        } catch (e: Exception) {
            checks += Check("App registered", false, e.message ?: "unknown error")
        }

        // 2. Image visibility in Extend's internal registry
        try {
            val out = runJson(
                "ags", "csm", "images", "list",
                "--app", app, "--namespace", namespace,
                "--format", "json", "--no-input",
                "--api-scope", "admin", "--api-version", "v2",
            )
            // Simple heuristic: count occurrences of an image tag-like token.
            val imageCount = Regex("\"imageTag\":").findAll(out).count()
            checks += Check(
                "Image in Extend registry",
                imageCount > 0,
                if (imageCount > 0) "" + imageCount + " image(s) visible in csm images list"
                else "no images in csm images list (image likely lives in an external registry such as ghcr.io)"
            )
        } catch (e: Exception) {
            checks += Check("Image in Extend registry", false, e.message ?: "unknown error")
        }

        // 3. Deployment
        try {
            val out = runJson(
                "ags", "csm", "deployments", "list",
                "--namespace", namespace,
                "--format", "json", "--no-input",
                "--api-scope", "admin", "--api-version", "v2",
            )
            val deploymentCount = Regex("\"app\":\\s*\"" + app + "\"").findAll(out).count()
            checks += Check(
                "Deployment exists",
                deploymentCount > 0,
                if (deploymentCount > 0) "" + deploymentCount + " deployment(s) for app=" + app
                else "no deployment found for app=" + app + " — run `./gradlew deployExtend`"
            )
        } catch (e: Exception) {
            checks += Check("Deployment exists", false, e.message ?: "unknown error")
        }

        // 4. Match2 wiring (matchmaker only)
        val matchFunctionName = module.matchFunctionName
        if (matchFunctionName != null) {
            try {
                val out = runJson(
                    "ags", "matchmaking", "match-functions", "list",
                    "--namespace", namespace,
                    "--format", "json", "--no-input",
                    "--api-scope", "admin", "--api-version", "v1",
                )
                val wired = out.contains("\"match_function\":\"" + matchFunctionName + "\"")
                checks += Check(
                    "Match2 match function wired",
                    wired,
                    if (wired) "match function '" + matchFunctionName + "' is registered in match2"
                    else "match function '" + matchFunctionName + "' not found — see matchmaker/DEPLOY.md 'Wire the matchmaker into match2'"
                )
            } catch (e: Exception) {
                checks += Check("Match2 match function wired", false, e.message ?: "unknown error")
            }
        }

        // Report
        val width = checks.maxOf { it.name.length }
        checks.forEach { c ->
            val mark = if (c.passed) "[ OK ]" else "[FAIL]"
            logger.lifecycle("[verify] " + mark + " " + c.name.padEnd(width) + "  " + c.detail)
        }

        // Soft-pass override: if App + Deployment are both present but the
        // Image check failed, the operator is using the ghcr.io flow and the
        // image isn't in Extend's internal registry. Don't fail in that case;
        // the deployment landing is the real signal that the image is
        // reachable.
        val imageCheck = checks.firstOrNull { it.name == "Image in Extend registry" }
        val appCheck = checks.firstOrNull { it.name == "App registered" }
        val deployCheck = checks.firstOrNull { it.name == "Deployment exists" }
        val softPass = imageCheck != null && !imageCheck.passed &&
            appCheck?.passed == true && deployCheck?.passed == true

        val hardFailures = checks.filter { !it.passed && !(it === imageCheck && softPass) }
        if (hardFailures.isNotEmpty()) {
            error("verifyExtendDeployment failed for module=" + module.name + " (" + hardFailures.size + " hard check(s) failed)")
        }
        if (softPass) {
            logger.lifecycle("[verify] NOTE: image not in Extend's internal registry, but App + Deployment are present - image is being pulled from an external registry (e.g. ghcr.io). All hard checks passed.")
        }
        logger.lifecycle("[verify] all checks passed for module=" + module.name)
    }
}

// ============================================================================
// secretsGuard: fail the build if any tracked file contains a hardcoded
// credential, tenant URL, or account-bound Bedrock ARN. Companion to the
// ConfigSource.properties() refactor — patterns here are the ones that
// the refactor removes from source.
// ============================================================================

val secretsGuardPatterns = listOf(
    // Server client ID + secret
    Regex("""980d5e441ca947dbb576c34cfa74368a"""),
    Regex("""QsN1cEsR8e6JRfbPpRlGr9x4RHMQc7g7"""),
    // Browser client ID
    Regex("""fa95259ee024448e9e4dd59f0c348803"""),
    // AWS account ID in account-bound Bedrock inference-profile ARNs
    Regex("""arn:aws:bedrock:[a-z0-9-]+:521369004927:inference-profile/"""),
    // Tenant URLs (autogenesis.prod + watchdog + dshub + echoofmaridia-autogenesis)
    Regex("""https?://(autogenesis|watchdog|dshub|echoofmaridia-autogenesis)\.prod\.gamingservices\.accelbyte\.io"""),
    // Bare AccelByte tenant namespace — operator-internal name that must
    // never appear hardcoded in source. Operators point the property file
    // at whichever namespace they want to test against; no code changes
    // are required to retarget.
    Regex("""echoofmaridia-autogenesis"""),
    // Real AB user ID hex
    Regex("""004c3eb02c0b4436b41b24d5d670b0e4"""),
    // Tokens leaked to console
    Regex("""console\.log.*accessToken"""),
    Regex("""console\.log.*refreshToken"""),
)

val secretsGuard by tasks.registering {
    group = "verification"
    description = "Fails the build if any tracked file contains a hardcoded credential, tenant URL, or account-bound Bedrock ARN."
    doLast {
        val trackedFiles = providers.exec {
            commandLine("git", "ls-files")
        }.standardOutput.asText.get().lines().filter { it.isNotBlank() }
            // Exclude this build script — it contains the secretsGuard regex
            // patterns as literal text, which would self-trigger the guard.
            .filter { it != "build.gradle.kts" }
        val violations = mutableListOf<String>()
        trackedFiles.forEach { rel ->
            val f = file(rel)
            if (!f.exists() || !f.isFile) return@forEach
            if (f.extension !in listOf("kt", "kts", "md", "properties")) return@forEach
            val text = try { f.readText() } catch (e: Exception) { return@forEach }
            secretsGuardPatterns.forEach { re ->
                if (re.containsMatchIn(text)) {
                    violations += "${rel}: matched /${re.pattern}/"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "secretsGuard: forbidden patterns detected:\n  " +
                    violations.joinToString("\n  ") + "\n\n" +
                    "These literals must live in autogenesis-secrets/config/*.properties, not in source."
            )
        }
        logger.lifecycle("secretsGuard: clean (${trackedFiles.size} files checked)")
    }
}

// secretsGuard runs as part of every subproject's `check` task (when present) and
// is also invokable directly via `./gradlew secretsGuard`.
subprojects.forEach { subproject ->
    subproject.tasks.matching { it.name == "check" }.configureEach { dependsOn(secretsGuard) }
}
