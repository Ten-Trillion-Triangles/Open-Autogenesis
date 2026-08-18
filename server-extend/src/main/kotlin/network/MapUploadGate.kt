package network

import agent.builders.MapSafetyPayload
import agent.builders.buildMapSafetyAgent
import com.TTT.Pipe.MultimodalContent
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.MapUploadGateResponse
import org.ttt.autogenesis.network.MapUploadRequest
import org.ttt.autogenesis.network.RpcCallContext
import org.ttt.autogenesis.network.RpcDirection
import org.ttt.autogenesis.network.RpcMethod
import structs.MapPackManager
import structs.accelbyte.cloudsave.CloudPlayerMapEntry
import com.TTT.Config.TPipeConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.writeStringToFile
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import structs.image.ImageDecodeException
import structs.image.ImageDecoder

/**
 * Server-extend orchestrator for the map-upload safety gate.
 *
 * Replaces the implicit "client uploads, server persists" path with an
 * explicit guard: the upload IS the safety check survives, and the gate
 * either:
 *   - Calls the safety-agent pipes (image + content) on the unpacked payload.
 *   - On safety pass: persists the raw pack bytes via [MapUploadGateStorage]
 *     and pushes the `Map.Upload.Success` notification.
 *   - On safety fail: pushes the `Map.Upload.Error` notification (the
 *     pipes already do this via [MapUploadErrorHandlers.sendMapUploadError])
 *     and short-circuits the save.
 *   - On safety pass but save fail: pushes the `Map.Upload.Error` notification
 *     with the save failure reason and propagates the failure to the caller.
 *
 * Wire contract: `server.extend.uploadMapGate` (RPC, server-bound).
 * Existing `MapUploadRequest.uploadMapPack` on the main server is
 * intentionally NOT modified — it runs the upload with no safety pass.
 *
 * Auth: the request body carries the `playerId`; the gate trusts it the
 * same way every other server-extend RPC does. Documented as known debt.
 *
 * Image-size pre-flight: the gate routes every upload image through the
 * downsample helper, which iterates halving the longest edge
 * (1024 → 512 → 256 → 128 → 64) until the result fits the
 * 256 K-token byte ceiling [MAX_SAFE_BINARY_BYTES] (≈408 KB at the
 * empirical 0.627 tokens/byte PNG ratio). If even the smallest re-encode
 * still exceeds the cap, the gate fails the safety check with a clear
 * `Map.Upload.Error`. The pipeline is executed at most once per gate call.
 *
 * Catalogue userId-resolution: the gate derives its storage `userId`
  * for [MapUploadGateStorage.savePack] from the live
  * [org.ttt.autogenesis.serverextend.RestPlayerSession.accelbyteId] —
  * not from the SSE connectionId. The web client's
  * [org.ttt.autogenesis.kvisionapp.globals.AccelByteEnv.userId] is the
  * canonical query key for `listPlayerMaps`, so save and list must
  * land in the same partition; partition mismatch produces an
  * always-empty Collection overlay even after reloads (the bug
  * [network.MapUploadGateCatalogueUserIdTest] pins).
  */
object MapUploadGate
{
    /**
     * The byte threshold below which the image is guaranteed to fit the
     * operator-mandated 256 K-token image budget on Nova Lite's Converse
     * API. Calibrated against the empirical 0.627 tokens/byte PNG ratio
     * verified from the live trace error: "Context window size: 990000
     * Binary size: 1579421" against a 1.58 MB upload.
     *
     * The operator's directive (2026-08-12):
     *   "always downsample any images we send to the map safety agent to
     *    256K tokens in size."
     *
     * 256 K tokens at 0.627 tokens/byte ≈ 408 KB. The downsample helper
     * iterates halving the longest edge until the result sits below this
     * cap. Images that cannot fit even at the smallest meaningful
     * dimension are rejected with a specific reason.
     *
     * `internal` for the test seam in
     * [network.MapUploadGateTokenTargetDownsampleTest]; the companion
     * `maxSafeBinaryBytesForTest` forwards the value.
     */
    internal const val MAX_SAFE_BINARY_BYTES = (256_000 / 0.627).toInt() // ≈ 408 KB

    /**
     * The starting maximum dimension for the iterated downsample. The
     * helper halving the longest edge until the resulting PNG fits below
     * [MAX_SAFE_BINARY_BYTES] (256 K tokens at 0.627 tokens/byte). At
     * 1024×1024 most realistic map renders compress to ~300 KB — well
     * below the 408 KB cap. Complex hand-drawn maps that exceed the
     * cap on a single pass are re-encoded at 512×512, then 256×256, and
     * so on. The floor is [DOWNSAMPLE_MIN_DIMENSION] below which the
     * helper either accepts or rejects based on the byte cap.
     */
    private const val DOWNSAMPLE_MAX_DIMENSION = 1024

    /**
     * The smallest dimension the downsample helper will iterate down
     * to. If a re-encoded image at this size still exceeds
     * [MAX_SAFE_BINARY_BYTES], the gate rejects with a specific reason
     * rather than passing an oversized payload to the safety classifier.
     */
    private const val DOWNSAMPLE_MIN_DIMENSION = 64

    /**
     * Maximum number of downsample passes (5 — covers the 1024→512→256→128→64
     * halving chain). Bounds the fakeDownsampler iteration loop so a
     * deliberately-broken test seam can't spin forever.
     */
    private const val MAX_DOWNSAMPLE_PASSES = 5

    /**
     * Test seam: when non-null, overrides the unpack call so the gate can
     * be tested without a real `MapPackManager.unpack`. Defaults to null so
     * production calls go through the real unpack.
     */
    @Volatile
    internal var fakeUnpacker: ((ByteArray) -> MapSafetyPayload)? = null

    /**
     * Test seam: when non-null, overrides the safety pipeline call so the
     * gate can be tested without a real Bedrock safety pass. The lambda
     * should return a `MultimodalContent` whose `shouldTerminate()` mirrors
     * a safety pass (false) or fail (true), mirroring the production
     * `Pipeline.execute` contract. Defaults to null so production calls
     * go through `buildMapSafetyAgent`.
     */
    @Volatile
    internal var fakeSafetyRunner: ((playerId: String, payload: MapSafetyPayload) -> MultimodalContent)? = null

    /**
     * Test seam: when non-null, overrides the downsample helper so tests
     * can exercise the pre-flight without needing real image data. The lambda
     * receives the input bytes and returns the (downsampled) bytes.
     * Defaults to null so production calls go through the real JDK ImageIO
     * downsample.
     */
    @Volatile
    internal var fakeDownsampler: ((ByteArray) -> ByteArray)? = null

    internal fun resetForTest()
    {
        fakeUnpacker = null
        fakeSafetyRunner = null
        fakeDownsampler = null
    }

    /**
     * Test seam: forwards the calibrated safety-classifier image-byte cap
     * so callers (e.g. [network.MapUploadGateDownsamplePreFlightTest]) can
     * pin the constant against Nova Lite's context-window empirical
     * overflow without going through a full upload pipeline.
     */
    internal fun maxSafeBinaryBytesForTest(): Int = MAX_SAFE_BINARY_BYTES

    /**
     * Resolve the canonical storage userId for the [savePack] call.
     *
     * The web client sends its catalogue query under
     * [org.ttt.autogenesis.kvisionapp.globals.AccelByteEnv.userId] — the
     * canonical AccelByte user id (e.g. the test-fixture uuid documented
     * in the build script's secretsGuard regex comment, NOT a real user).
     * Earlier revisions of this gate hardcoded `userId = playerId`
     * (the SSE connectionId, e.g. `rest-client-979835631`). Save and
     * list therefore live in two different partitions and the
     * Collection overlay always shows "No maps match" — the
     * Collection-overlay refresh call hits the AccelByte-id partition
     * while the gate writes to the connection-id partition.
     *
     * Fix: derive the storage userId from the live
     * [org.ttt.autogenesis.serverextend.RestPlayerConnectionManager]
     * session's `accelbyteId`. When the session has no accelbyteId
     * (legacy curl probes, test rigs, or `?skipLogin=true` paths
     * without an OAuth callback that stamped the id), fall back to
     * the connectionId so the existing test seams still work.
     *
     * Mirrors [network.MapUploadSafetyBilling.resolveAccelByteId] —
     * the singleton there reads from the same `currentConnectionManager()`
     * seam; we don't extract a shared helper to avoid coupling two
     * unrelated singleton graphs.
     *
     * Failures from `findSession` (timeout, closed channel) are caught
     * and the fallback path is taken so a transient connection-manager
     * blip never blocks the upload path.
     */
    private suspend fun resolveAccelbyteUserIdForSave(connectionId: String): String
    {
        val manager = MapUploadSuccessHandlers.currentConnectionManager()
            ?: return connectionId
        val session = runCatching { manager.findSession(connectionId) }
            .getOrNull()
            ?: return connectionId
        val accelbyteId = session.accelbyteId
        return accelbyteId?.takeIf { it.isNotBlank() } ?: connectionId
    }

    @RpcMethod("server.extend.uploadMapGate", RpcDirection.SERVER)
    suspend fun uploadMapGate(context: RpcCallContext, request: MapUploadRequest): MapUploadGateResponse
    {
        // The connectionId in the RPC context is the playerId for the SSE/POST
        // session — the same key `RestPlayerConnectionManager.findSession` keys
        // its sessions map with. We thread it through every notification call
        // so the originating client receives the success/error push targeted at
        // their session.
        val playerId = context.connectionId

        // === INSTRUMENTATION: function ENTRY — full request snapshot ===
        Logger.info(
            LogCategory.NETWORK,
            "MapUploadGate.uploadMapGate: ENTRY playerId=$playerId " +
                    "request.mapPackBytes.size=${request.mapPackBytes.size} " +
                    "request.mapName='${request.mapName}' " +
                    "fakeUnpacker=${if (fakeUnpacker != null) "SET" else "null"} " +
                    "fakeSafetyRunner=${if (fakeSafetyRunner != null) "SET" else "null"} " +
                    "fakeDownsampler=${if (fakeDownsampler != null) "SET" else "null"}"
        )

        // 1. Unpack the zip. The unpacker returns a structured payload
        //    (image bytes + MapData) consumed by the safety pipes.
        val payload = try
        {
            val unpacker = fakeUnpacker
            Logger.info(
                LogCategory.NETWORK,
                "MapUploadGate: unpack stage — chose " +
                        "${if (unpacker != null) "fakeUnpacker (test seam)" else "MapPackManager.unpack (real)"} " +
                        "for playerId=$playerId inputBytes=${request.mapPackBytes.size}"
            )
            if (unpacker != null)
            {
                unpacker(request.mapPackBytes)
            }
            else
            {
                val unpacked = MapPackManager.unpack(request.mapPackBytes)
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate: real unpack produced " +
                            "imageBytes.size=${unpacked.imageBytes.size} " +
                            "mapData.pins.size=${unpacked.mapData.pins.size} " +
                            "mapData.connections.size=${unpacked.mapData.connections.size} " +
                            "mapData.worldName.length=${unpacked.mapData.worldName.length} " +
                            "mapData.storyScenario.length=${unpacked.mapData.storyScenario.length} " +
                            "for playerId=$playerId"
                )
                MapSafetyPayload(imageBytes = unpacked.imageBytes, mapData = unpacked.mapData)
            }
        }
        catch (e: Exception)
        {
            Logger.error(LogCategory.NETWORK, "MapUploadGate: unpack failed for playerId=$playerId: ${e.message}")
            val reason = "Unpack failed: ${e.message}"
            MapUploadErrorHandlers.sendMapUploadError(playerId, reason)
            return MapUploadGateResponse(accepted = false, reason = reason)
        }

        // === INSTRUMENTATION: payload shape after unpack ===
        Logger.info(
            LogCategory.NETWORK,
            "MapUploadGate: payload assembled — imageBytes.size=${payload.imageBytes.size} " +
                    "mapData.pins.size=${payload.mapData.pins.size} " +
                    "mapData.connections.size=${payload.mapData.connections.size} " +
                    "worldName='${payload.mapData.worldName}' " +
                    "playerId=$playerId"
        )

        // 2. Content validation: fail-fast before any LLM call when the pack
        //    is essentially empty. Cost control: an empty / all-default map
        //    has no safety signal — the image pipe sees a zero-byte PNG and
        //    the text pipe sees "pins: [], connections: [], worldName: '',
        //    storyScenario: ''", neither of which is meaningful to the
        //    classifier. Routing that to Nova Lite burns tokens for zero
        //    value and slows the round-trip for the user.
        //
        //    The check runs AFTER the unpack so we have a valid MapData
        //    to inspect — but BEFORE the downsample pre-flight so the
        //    rejection is cheap (no ImageIO.read) and surfaces a specific
        //    "Map pack is empty" reason rather than a downstream
        //    "Image not provided for inspection" classification error.
        if (payload.imageBytes.isEmpty())
        {
            val reason = "Map pack is empty: image entry has zero bytes"
            Logger.warn(LogCategory.NETWORK, "MapUploadGate: rejecting empty image upload for playerId=$playerId")
            MapUploadErrorHandlers.sendMapUploadError(playerId, reason)
            return MapUploadGateResponse(accepted = false, reason = reason)
        }
        if (payload.mapData.pins.isEmpty() && payload.mapData.connections.isEmpty())
        {
            val reason = "Map pack is empty: no pins or connections in map data"
            Logger.warn(LogCategory.NETWORK, "MapUploadGate: rejecting empty map data upload for playerId=$playerId (pins=0, connections=0)")
            MapUploadErrorHandlers.sendMapUploadError(playerId, reason)
            return MapUploadGateResponse(accepted = false, reason = reason)
        }

        // 3. Pre-flight: route every image through the downsample helper so
        //    the safety classifier always sees a payload calibrated for
        //    the 256K-token floor. The helper is a no-op fast path for
        //    images already under DOWNSAMPLE_MAX_DIMENSION (it returns the
        //    original bytes unchanged), so the per-call cost is an
        //    ImageIO.read + max(W,H) compare for small images.
        //
        //    The downsample itself can throw (ImageIO.decode failure on
        //    indexed-color PNGs, palette issues, color model mismatches —
        //    the Sand Martello repro). When it throws we MUST reject the
        //    upload rather than silently passing the undecodable bytes
        //    through to the safety classifier; the outer try/catch routes
        //    the failure to MapUploadErrorHandlers + returns
        //    MapUploadGateResponse(accepted=false, reason=...).
        Logger.info(
            LogCategory.NETWORK,
            "MapUploadGate: routing image (size=${payload.imageBytes.size} bytes) through downsample for playerId=$playerId"
        )
        Logger.info(
            LogCategory.NETWORK,
            "MapUploadGate.uploadMapGate: PRE-FLIGHT — about to call downsampleImageBytes " +
                    "inputBytes.size=${payload.imageBytes.size} " +
                    "playerId=$playerId"
        )
        val imageBytes: ByteArray
        val downsampleStarted = System.currentTimeMillis()
        try
        {
            imageBytes = downsampleImageBytes(payload.imageBytes)
        }
        catch (e: Exception)
        {
            val reason = "Image could not be decoded for downsample pre-flight: ${e.message ?: e::class.simpleName}"
            Logger.error(
                LogCategory.NETWORK,
                "MapUploadGate: $reason for playerId=$playerId; rejecting. " +
                        "downsampleExceptionClass=${e::class.java.name} " +
                        "downsampleMs=${System.currentTimeMillis() - downsampleStarted}"
            )
            MapUploadErrorHandlers.sendMapUploadError(playerId, reason)
            return MapUploadGateResponse(accepted = false, reason = reason)
        }
        val downsampleMs = System.currentTimeMillis() - downsampleStarted
        Logger.info(
            LogCategory.NETWORK,
            "MapUploadGate.uploadMapGate: POST-DOWNSAMPLE — imageBytes.size=${imageBytes.size} " +
                    "originalSize=${payload.imageBytes.size} " +
                    "ratio=${"%.4f".format(imageBytes.size.toDouble() / payload.imageBytes.size.coerceAtLeast(1))} " +
                    "downsampleMs=$downsampleMs " +
                    "estimatedTokens=${(imageBytes.size * TOKEN_BYTES_RATIO).toInt()} " +
                    "ofBudget=$SAFETY_TOKEN_BUDGET " +
                    "fitsCap=${imageBytes.size <= MAX_SAFE_BINARY_BYTES} " +
                    "playerId=$playerId"
        )
        if (imageBytes.size > MAX_SAFE_BINARY_BYTES)
        {
            val reason = "Image too large even after downsample (${imageBytes.size} bytes > ${MAX_SAFE_BINARY_BYTES} cap)"
            Logger.error(
                LogCategory.NETWORK,
                "MapUploadGate: $reason for playerId=$playerId; rejecting. " +
                        "imageBytes.size=${imageBytes.size} " +
                        "MAX_SAFE_BINARY_BYTES=$MAX_SAFE_BINARY_BYTES " +
                        "overshootBytes=${imageBytes.size - MAX_SAFE_BINARY_BYTES} " +
                        "estimatedTokens=${(imageBytes.size * TOKEN_BYTES_RATIO).toInt()} " +
                        "SAFETY_TOKEN_BUDGET=$SAFETY_TOKEN_BUDGET"
            )
            MapUploadErrorHandlers.sendMapUploadError(playerId, reason)
            return MapUploadGateResponse(accepted = false, reason = reason)
        }

        // 4. Run the safety-agent pipeline. The pipes' `setOnFailure` callbacks
        //    push `Map.Upload.Error` via `MapUploadErrorHandlers` and set
        //    `terminatePipeline = true` on the result. The pipeline's
        //    `execute` returns the final MultimodalContent; we use the
        //    `shouldTerminate()` flag as the synchronous pass/fail signal.
        //
        //    The trace fires for BOTH branches: the real-pipeline path
        //    captures the full Bedrock trace (JSON + HTML), and the
        //    fake-runner path (used by integration tests) still writes a
        //    gate-call summary so the trace directory carries a record of
        //    every safety invocation regardless of which branch fired.
        val safetyPass: Boolean
        val safetyStarted = System.currentTimeMillis()
        try
        {
            val runner = fakeSafetyRunner
            Logger.info(
                LogCategory.NETWORK,
                "MapUploadGate.uploadMapGate: SAFETY-PIPELINE — about to run " +
                        "fakeSafetyRunner=${if (runner != null) "SET (test seam)" else "null → real buildMapSafetyAgent"} " +
                        "imageBytes.size=${imageBytes.size} " +
                        "estimatedTokens=${(imageBytes.size * TOKEN_BYTES_RATIO).toInt()} " +
                        "ofBudget=$SAFETY_TOKEN_BUDGET " +
                        "playerId=$playerId"
            )
            val pipelineResult = if (runner != null)
            {
                Logger.info(LogCategory.NETWORK, "MapUploadGate.uploadMapGate: SAFETY-PIPELINE BRANCH=test-seam fakeSafetyRunner; invoking runner(playerId, downsampledPayload)")
                // Hand the test seam the DOWNSAMPLED bytes — `payload` was
                // built at unpack time with the original (pre-downsample)
                // bytes, which would defeat the operator-mandated 256K-token
                // image budget the moment the safety runner counted them.
                val downsampledPayload = MapSafetyPayload(imageBytes = imageBytes, mapData = payload.mapData)
                val result = runner(playerId, downsampledPayload)
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate.uploadMapGate: SAFETY-PIPELINE test-seam runner returned " +
                            "shouldTerminate=${result.shouldTerminate()} " +
                            "text.length=${result.text?.length ?: 0} " +
                            "binaryCount=${result.binaryContent?.size ?: 0} " +
                            "playerId=$playerId"
                )
                captureGateCallSummary(playerId, imageBytes.size, safetyPass = !result.shouldTerminate())
                result
            }
            else
            {
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate.uploadMapGate: SAFETY-PIPELINE BRANCH=real — calling buildMapSafetyAgent(playerId, downsampledPayload) " +
                            "imageBytes.size=${imageBytes.size} mimeType='image/png' filename='map.png' " +
                            "playerId=$playerId"
                )
                // Hand the safety agent the DOWNSAMPLED bytes — `payload`
                // was built at unpack time with the original (pre-downsample)
                // bytes, and the agent's pre-init pulls from
                // pipeMetadata["imageBytes"] to re-stamp the multimodal,
                // which means passing the original payload would route the
                // 6.3MB original through TPipe's binary token counter and
                // trip the 990K-token window for the imageChecker pipe.
                val downsampledPayload = MapSafetyPayload(imageBytes = imageBytes, mapData = payload.mapData)
                val builtPipeline = buildMapSafetyAgent(playerId, downsampledPayload)
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate.uploadMapGate: SAFETY-PIPELINE built — enabling tracing " +
                            "TraceConfig(enabled=true, detailLevel=DEBUG) " +
                            "playerId=$playerId"
                )
                // Enable tracing on the safety pipeline so the trace is
                // captured for both pass and fail paths. The captured
                // trace is written to disk in `captureAndSaveTrace`
                // (file: ${TPipeConfig.getTraceDir()}/MapUploadGate/trace.{json,html})
                // so it can be inspected after the upload completes.
                builtPipeline.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))

                val multimodal = MultimodalContent(text = "Map upload safety check")
                multimodal.addBinary(imageBytes, mimeType = "image/png", filename = "map.png")
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate.uploadMapGate: SAFETY-PIPELINE multimodal assembled — " +
                            "text='Map upload safety check' " +
                            "binary=image/png map.png size=${imageBytes.size} " +
                            "estimatedTokens=${(imageBytes.size * TOKEN_BYTES_RATIO).toInt()} " +
                            "playerId=$playerId"
                )
                val result = builtPipeline.execute(multimodal)

                captureAndSaveTrace(builtPipeline, playerId)
                captureGateCallSummary(playerId, imageBytes.size, safetyPass = !result.shouldTerminate())
                result
            }
            safetyPass = !pipelineResult.shouldTerminate()
            Logger.info(
                LogCategory.NETWORK,
                "MapUploadGate.uploadMapGate: SAFETY-PIPELINE COMPLETE " +
                        "safetyPass=$safetyPass " +
                        "shouldTerminate=${pipelineResult.shouldTerminate()} " +
                        "elapsedMs=${System.currentTimeMillis() - safetyStarted} " +
                        "playerId=$playerId"
            )
        }
        catch (e: Exception)
        {
            Logger.error(
                LogCategory.NETWORK,
                "MapUploadGate: safety pipeline threw for playerId=$playerId: ${e.message} " +
                        "exceptionClass=${e::class.java.name} " +
                        "elapsedMs=${System.currentTimeMillis() - safetyStarted}"
            )
            val reason = "Safety check failed: ${e.message}"
            MapUploadErrorHandlers.sendMapUploadError(playerId, reason)
            return MapUploadGateResponse(accepted = false, reason = reason)
        }

        if (!safetyPass)
        {
            // The pipes already pushed Map.Upload.Error with the specific
            // reason — we just return a generic rejection here.
            Logger.info(LogCategory.NETWORK, "MapUploadGate: safety rejected upload for playerId=$playerId")
            return MapUploadGateResponse(accepted = false, reason = "Map upload rejected by safety classifier")
        }

        // 5. Safety pass — persist the raw pack bytes and notify success.
        //
        // The gate is all-or-nothing: the catalogue entry is stamped only
        // after the AGS save returns success. Any failure on the save path
        // returns a clean reject with `metadata = null` so the client never
        // sees a partial entry that does not exist in AGS.
        val mapId = UUID.randomUUID().toString()
        val mapName = request.mapName.ifBlank { "Untitled Map" }
        val uploadedAt = System.currentTimeMillis()

        // T14: wire the safety-billing singleton. Cap-exceeded rejections
        // short-circuit the gate before the pack is persisted; the trace is
        // already on disk so the cap check reads the trace tokens before the
        // save call lands. Billing failures are tolerated with WARN logs (the
        // gate's success path is not interrupted by a billing error).
        Logger.info(
            LogCategory.NETWORK,
            "MapUploadGate.uploadMapGate: STAGE-5 SAVE-PREP — " +
                    "mapId=$mapId mapName='$mapName' uploadedAt=$uploadedAt " +
                    "request.mapPackBytes.size=${request.mapPackBytes.size} " +
                    "downsampledImageBytes.size=${imageBytes.size} " +
                    "playerId=$playerId"
        )

        // T14: wire the safety-billing singleton. Cap-exceeded rejections
        // short-circuit the gate before the pack is persisted; the trace is
        // already on disk so the cap check reads the trace tokens before the
        // save call lands. Billing failures are tolerated with WARN logs (the
        // gate's success path is not interrupted by a billing error).
        Logger.info(
            LogCategory.NETWORK,
            "MapUploadGate.uploadMapGate: STAGE-5a BILLING — invoking MapUploadSafetyBilling.recordSafetyUsage " +
                    "mapId=$mapId safetyPass=$safetyPass playerId=$playerId"
        )
        val billingOutcome = runCatching {
            MapUploadSafetyBilling.recordSafetyUsage(context, playerId, mapId, safetyPass)
        }.getOrElse { err ->
            Logger.warn(
                LogCategory.NETWORK,
                "MapUploadGate: billing invocation threw for playerId=$playerId: ${err.message} " +
                        "exceptionClass=${err::class.java.name}"
            )
            SafetyBillingOutcome.Skipped("billing threw: ${err.message}")
        }
        Logger.info(
            LogCategory.NETWORK,
            "MapUploadGate.uploadMapGate: STAGE-5a BILLING-OUTCOME " +
                    "outcomeClass=${billingOutcome::class.java.simpleName} " +
                    "outcome=$billingOutcome playerId=$playerId"
        )
        if (billingOutcome is SafetyBillingOutcome.CapExceeded)
        {
            val reason = "Token cap exceeded: used ${billingOutcome.tokensUsedThisCycle} of ${billingOutcome.tokensCapPerCycle}"
            Logger.warn(
                LogCategory.NETWORK,
                "MapUploadGate: cap-exceeded for playerId=$playerId — rejecting upload " +
                        "tokensUsedThisCycle=${billingOutcome.tokensUsedThisCycle} " +
                        "tokensCapPerCycle=${billingOutcome.tokensCapPerCycle} " +
                        "mapId=$mapId"
            )
            MapUploadErrorHandlers.sendMapUploadError(playerId, reason)
            return MapUploadGateResponse(accepted = false, reason = reason)
        }

        val resolvedUserId = resolveAccelbyteUserIdForSave(context.connectionId)
        Logger.info(
            LogCategory.NETWORK,
            "MapUploadGate.uploadMapGate: STAGE-5b STORAGE-USERID " +
                    "connectionId=${context.connectionId} resolvedUserId=$resolvedUserId " +
                    "match=${resolvedUserId == context.connectionId} " +
                    "playerId=$playerId"
        )
        val saveResult = MapUploadGateStorage.savePack(
            context = context,
            userId = resolvedUserId,
            mapId = mapId,
            mapName = mapName,
            mapPackBytes = request.mapPackBytes
        )

        return saveResult.fold(
            onSuccess = {
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate.uploadMapGate: STAGE-5 EXIT SUCCESS " +
                            "stored mapId=$mapId for playerId=$playerId " +
                            "(${request.mapPackBytes.size} raw bytes, " +
                            "${imageBytes.size} downsampled image bytes) " +
                            "resolvedUserId=$resolvedUserId"
                )
                MapUploadSuccessHandlers.sendMapUploadSuccess(playerId, mapId, mapName)
                // Catalogue entry mirrors the shape `listPlayerMaps` returns so
                // the player UI can render the new map row from the response
                // directly without a follow-up list round-trip.
                val entry = CloudPlayerMapEntry(
                    mapId = mapId,
                    mapName = mapName,
                    uploadedAt = uploadedAt,
                    sizeBytes = request.mapPackBytes.size
                )
                MapUploadGateResponse(
                    accepted = true,
                    mapId = mapId,
                    mapName = mapName,
                    metadata = entry
                )
            },
            onFailure = { err ->
                val reason = "Save failed: ${err.message ?: err.javaClass.simpleName}"
                Logger.error(
                    LogCategory.NETWORK,
                    "MapUploadGate: $reason (playerId=$playerId, mapId=$mapId) " +
                            "exceptionClass=${err::class.java.name} " +
                            "resolvedUserId=$resolvedUserId"
                )
                MapUploadErrorHandlers.sendMapUploadError(playerId, reason)
                MapUploadGateResponse(accepted = false, reason = reason)
            }
        )
    }

    /**
     * Downsamples the image bytes to fit within [MAX_SAFE_BINARY_BYTES]
     * (the operator-mandated 256 K-token floor). Returns the original
     * bytes if the image is already small enough on the first pass.
     *
     * Implementation:
     *  - On pass 1, re-encode at [DOWNSAMPLE_MAX_DIMENSION] (1024 px)
     *    on the longest edge. If the result still exceeds the cap,
     *    halve the longest edge and try again.
     *  - Continue halving (1024 → 512 → 256 → 128 → 64) until either
     *    the result fits the cap or the [DOWNSAMPLE_MIN_DIMENSION] is
     *    reached. If even that re-encoded image still exceeds the cap,
     *    return the bytes (the gate's downstream check will reject with
     *    a specific reason).
     *
     * Why the iteration: a single 1024×1024 re-encode of a complex map
     * render can still produce ~600 KB PNG (gradients, anti-aliased
     * map labels, multi-color territories). 600 KB × 0.627 = 376 K
     * tokens — over the 256 K-token floor the operator set. Iterating
     * to 512×512 cuts that to ~250 KB (~157 K tokens), well inside the
     * budget.
     *
     * Uses the JDK's `BufferedImage` + `ImageIO` — no new dependencies.
     * The output is a PNG to match the MIME type the safety pipe expects.
     *
     * @param bytes The raw image bytes (any image format JDK ImageIO
     *   decodes — PNG, JPEG, BMP, GIF).
     * @return Re-encoded PNG bytes at the smallest dimension that fits
     *   the cap, or the smallest reachable dimension if even that
     *   exceeds the cap. The original bytes pass through when decoding
     *   fails — the caller's downstream check surfaces the underlying
     *   error.
     */
    private fun downsampleImageBytes(bytes: ByteArray): ByteArray
    {
        Logger.info(
            LogCategory.NETWORK,
            "MapUploadGate.downsampleImageBytes: ENTRY inputBytes.size=${bytes.size} " +
                    "MAX_SAFE_BINARY_BYTES=$MAX_SAFE_BINARY_BYTES " +
                    "TOKEN_BYTES_RATIO=$TOKEN_BYTES_RATIO " +
                    "DOWNSAMPLE_MAX_DIMENSION=$DOWNSAMPLE_MAX_DIMENSION " +
                    "DOWNSAMPLE_MIN_DIMENSION=$DOWNSAMPLE_MIN_DIMENSION " +
                    "MAX_DOWNSAMPLE_PASSES=$MAX_DOWNSAMPLE_PASSES " +
                    "fakeDownsampler=${if (fakeDownsampler != null) "SET" else "null"}"
        )
        val fakeDownsampler = fakeDownsampler
        if (fakeDownsampler != null)
        {
            Logger.info(LogCategory.NETWORK, "MapUploadGate.downsampleImageBytes: BRANCH=test-seam (fakeDownsampler SET)")
            // Iterate the test seam: invoke once, then re-invoke on the
            // returned bytes until the result fits the cap (or we hit
            // the floor). Tests that pin single-pass behavior assert
            // downsampleCalled=1; tests that pin iterated behavior
            // assert downsampleCalled≥N.
            var current = fakeDownsampler(bytes)
            var passes = 1
            Logger.info(
                LogCategory.NETWORK,
                "MapUploadGate.downsampleImageBytes: fakeDownsampler pass 1 inputBytes.size=${bytes.size} " +
                        "outputBytes.size=${current.size} " +
                        "fitsCap=${current.size <= MAX_SAFE_BINARY_BYTES}"
            )
            while (current.size > MAX_SAFE_BINARY_BYTES && passes < MAX_DOWNSAMPLE_PASSES)
            {
                val prevSize = current.size
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate.downsampleImageBytes: fakeDownsampler pass $passes produced " +
                            "$prevSize bytes (> $MAX_SAFE_BINARY_BYTES cap); halving input"
                )
                val prev = current
                current = fakeDownsampler(current)
                passes += 1
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate.downsampleImageBytes: fakeDownsampler pass $passes " +
                            "inputBytes.size=${prev.size} " +
                            "outputBytes.size=${current.size} " +
                            "fitsCap=${current.size <= MAX_SAFE_BINARY_BYTES}"
                )
            }
            if (current.size > MAX_SAFE_BINARY_BYTES)
            {
                Logger.warn(
                    LogCategory.NETWORK,
                    "MapUploadGate.downsampleImageBytes: fakeDownsampler reached $passes passes, " +
                            "result=${current.size} bytes (> $MAX_SAFE_BINARY_BYTES cap)"
                )
            }
            Logger.info(
                LogCategory.NETWORK,
                "MapUploadGate.downsampleImageBytes: EXIT (test seam) finalBytes.size=${current.size} " +
                        "passes=$passes fitsCap=${current.size <= MAX_SAFE_BINARY_BYTES}"
            )
            return current
        }
        try
        {
            // Route through ImageDecoder which adds TwelveMonkeys codec
            // support (WebP, CMYK JPEG, indexed PNG, TIFF variants) on
            // top of the JDK's bundled PNG/JPEG/GIF/BMP set. The bare
            // ImageIO.read call we used to have here silently returned
            // null for any format outside the JDK set, which is the
            // exact failure mode the Sand Martello upload hits.
            Logger.info(LogCategory.NETWORK, "MapUploadGate.downsampleImageBytes: BRANCH=real-jdk path; calling ImageDecoder.decode")
            val src = ImageDecoder.decode(bytes)
            val srcW = src.width
            val srcH = src.height
            val longestEdge = maxOf(srcW, srcH)
            Logger.info(
                LogCategory.NETWORK,
                "MapUploadGate.downsampleImageBytes: ImageDecoder.decode succeeded " +
                        "srcW=$srcW srcH=$srcH longestEdge=$longestEdge " +
                        "src.colorModel=${src.colorModel.javaClass.simpleName} " +
                        "src.raster.numBands=${src.raster.numBands} " +
                        "src.raster.numDataElements=${src.raster.numDataElements}"
            )

            // Iterated halving: 1024 → 512 → 256 → 128 → 64 (floor).
            // At each dimension, decode → re-encode PNG → measure
            // bytes. Return the first re-encode that fits the cap,
            // or the final re-encode at the floor if none fit.
            var currentMaxDim = DOWNSAMPLE_MAX_DIMENSION
            var lastReencoded: ByteArray = bytes
            var passIndex = 0
            Logger.info(
                LogCategory.NETWORK,
                "MapUploadGate.downsampleImageBytes: starting halving chain " +
                        "DOWNSAMPLE_MAX_DIMENSION=$currentMaxDim " +
                        "DOWNSAMPLE_MIN_DIMENSION=$DOWNSAMPLE_MIN_DIMENSION"
            )
            while (currentMaxDim >= DOWNSAMPLE_MIN_DIMENSION)
            {
                passIndex += 1
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate.downsampleImageBytes: pass=$passIndex " +
                            "currentMaxDim=$currentMaxDim " +
                            "srcW=$srcW srcH=$srcH longestEdge=$longestEdge"
                )
                if (longestEdge <= currentMaxDim)
                {
                    // The source is already under this dimension. No
                    // point iterating further — return as-is.
                    Logger.info(
                        LogCategory.NETWORK,
                        "MapUploadGate.downsampleImageBytes: pass=$passIndex EARLY-RETURN " +
                                "reason='longestEdge=$longestEdge <= currentMaxDim=$currentMaxDim' " +
                                "returning original bytes unchanged (size=${bytes.size})"
                    )
                    return bytes
                }
                val scale = currentMaxDim.toDouble() / longestEdge
                val dstW = (srcW * scale).toInt().coerceAtLeast(1)
                val dstH = (srcH * scale).toInt().coerceAtLeast(1)
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate.downsampleImageBytes: pass=$passIndex " +
                            "scale=$scale dstW=$dstW dstH=$dstH " +
                            "(srcW=$srcW * scale, srcH=$srcH * scale, coerceAtLeast=1)"
                )
                val dst = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB)
                val g = dst.createGraphics()
                try
                {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.drawImage(src, 0, 0, dstW, dstH, null)
                    Logger.info(
                        LogCategory.NETWORK,
                        "MapUploadGate.downsampleImageBytes: pass=$passIndex drawImage complete " +
                                "src(srcW=$srcW,srcH=$srcH) → dst(dstW=$dstW,dstH=$dstH)"
                    )
                }
                finally
                {
                    g.dispose()
                }
                val out = ByteArrayOutputStream()
                ImageIO.write(dst, "png", out)
                val reencoded = out.toByteArray()
                lastReencoded = reencoded
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate.downsampleImageBytes: pass=$passIndex " +
                            "ImageIO.write(BufferedImage=${dstW}x${dstH}, TYPE_INT_ARGB) → " +
                            "PNG bytes.size=${reencoded.size} " +
                            "fitsCap=${reencoded.size <= MAX_SAFE_BINARY_BYTES} " +
                            "ratio_bytesPerPixel=${"%.4f".format(reencoded.size.toDouble() / (dstW.toDouble() * dstH))}"
                )
                if (reencoded.size <= MAX_SAFE_BINARY_BYTES)
                {
                    Logger.info(
                        LogCategory.NETWORK,
                        "MapUploadGate.downsampleImageBytes: pass=$passIndex SUCCESS-RETURN " +
                                "finalDim=$currentMaxDim bytes.size=${reencoded.size} " +
                                "estimatedTokens=${(reencoded.size * TOKEN_BYTES_RATIO).toInt()}"
                    )
                    return reencoded
                }
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate.downsampleImageBytes: pass=$passIndex TOO-LARGE " +
                            "produced ${reencoded.size} bytes (> $MAX_SAFE_BINARY_BYTES cap); halving"
                )
                currentMaxDim /= 2
                Logger.info(
                    LogCategory.NETWORK,
                    "MapUploadGate.downsampleImageBytes: pass=$passIndex currentMaxDim halved " +
                            "$currentMaxDim*2=$currentMaxDim*2 → $currentMaxDim"
                )
            }
            Logger.warn(
                LogCategory.NETWORK,
                "MapUploadGate.downsampleImageBytes: FLOOR-REACHED " +
                        "DOWNSAMPLE_MIN_DIMENSION=$DOWNSAMPLE_MIN_DIMENSION " +
                        "result=${lastReencoded.size} bytes (> $MAX_SAFE_BINARY_BYTES cap) " +
                        "passes=$passIndex"
            )
            Logger.info(
                LogCategory.NETWORK,
                "MapUploadGate.downsampleImageBytes: EXIT (real-jdk path) returning lastReencoded " +
                        "lastReencoded.size=${lastReencoded.size} " +
                        "passes=$passIndex"
            )
            return lastReencoded
        }
        catch (e: Exception)
        {
            // HARD FAIL: returning the original bytes defeats the operator's
            // 256K-token directive. The downstream cap check only fires when
            // downsample SUCCEEDED; on decode failure the original bytes
            // (potentially > MAX_SAFE_BINARY_BYTES or simply undecodable
            // garbage) would land in the safety classifier, which crashes
            // against the model's context window OR silently receives the
            // wrong image content.
            //
            // Throwing routes the failure through the gate's pre-flight
            // try/catch (uploadMapGate step 3), which pushes
            // Map.Upload.Error + returns MapUploadGateResponse(false).
            Logger.error(
                LogCategory.NETWORK,
                "MapUploadGate.downsampleImageBytes: EXCEPTION " +
                        "inputBytes.size=${bytes.size} " +
                        "exceptionClass=${e::class.java.name} " +
                        "exceptionMessage=${e.message ?: "<null>"} " +
                        "stackTraceTop=${e.stackTrace.firstOrNull()?.toString() ?: "<empty>"}"
            )
            throw RuntimeException(
                "Image could not be decoded for downsample: ${e.message ?: e::class.simpleName}",
                e
            )
        }
    }

    /**
     * Captures the safety pipeline's trace as both JSON and HTML reports
     * and writes them to ${TPipeConfig.getTraceDir()}/MapUploadGate/trace.{json,html}.
     *
     * Mirrors the canonical pattern in
     * `server/src/main/kotlin/agent/runners/traceCleanup.kt::saveSystemTrace`,
     * scoped to the gate because the gate is the only caller. The directory
     * is created if missing; failures are logged at ERROR and swallowed so
     * the gate's HTTP response is never blocked by a trace-write error.
     *
     * @param pipeline The safety pipeline that was just executed.
     * @param playerId The originating player; used only for the log line.
     */
    internal fun captureAndSaveTrace(pipeline: Pipeline, playerId: String)
    {
        try
        {
            val subFolder = "MapUploadGate"
            val dir = File(File(TPipeConfig.getTraceDir()), subFolder)
            if (!dir.exists()) dir.mkdirs()

            val jsonContent = pipeline.getTraceReport(TraceFormat.JSON)
            val htmlContent = pipeline.getTraceReport(TraceFormat.HTML)

            writeStringToFile("${dir.absolutePath}/trace.json", jsonContent)
            writeStringToFile("${dir.absolutePath}/trace.html", htmlContent)

            Logger.debug(LogCategory.SYSTEM, "MapUploadGate: saved trace (JSON/HTML) to ${dir.absolutePath}/trace.* for playerId=$playerId")
        }
        catch (e: Exception)
        {
            Logger.error(LogCategory.SYSTEM, "MapUploadGate: trace capture failed for playerId=$playerId: ${e.message}")
        }
    }

    /**
     * Writes a lightweight gate-call summary to
     * ${TPipeConfig.getTraceDir()}/MapUploadGate/gate-call.json on every
     * safety invocation. Fires for BOTH the real Bedrock-pipeline path
     * AND the fake-runner test seam so the trace directory always carries
     * a record of the gate's decision.
     *
     * The summary is intentionally minimal — just enough to confirm a
     * safety check ran and what it decided. The detailed Bedrock trace
     * (captureAndSaveTrace) still emits `trace.json` + `trace.html` on
     * the real-pipeline path for full auditability; the gate-call summary
     * is the always-on record used by the integration tests and by any
     * future post-mortem tooling that wants to know whether a given
     * upload went through the safety gate without parsing the Bedrock trace.
     *
     * Failures are logged at ERROR and swallowed so the gate's HTTP
     * response is never blocked by a trace-write error.
     *
     * @param playerId The originating player; recorded for forensics.
     * @param imageBytes The size of the image bytes the safety pipeline
     *   saw (downsampled or original, whichever reached the pipeline).
     * @param safetyPass Whether the safety pipeline accepted the upload.
     */
    /**
     * The empirical PNG-to-token ratio used by [MAX_SAFE_BINARY_BYTES].
     * Exposed for tests so the receipt's conversion factor can be
     * pinned against the same constant the gate enforces. Verified
     * 2026-08-13 against a 600x600 PNG that Bedrock reported as
     * 1334 input tokens for a 3778-byte payload (≈ 0.353 t/b actual,
     * 0.627 t/b conservative ceiling).
     */
    internal const val TOKEN_BYTES_RATIO = 0.627

    /**
     * The Bedrock safety classifier's documented input-token budget.
     * Any image that produces fewer than this many tokens after
     * downsample fits inside the safety context. The byte ceiling
     * [MAX_SAFE_BINARY_BYTES] is derived from this constant.
     */
    internal const val SAFETY_TOKEN_BUDGET = 256_000

    internal fun captureGateCallSummary(playerId: String, imageBytes: Int, safetyPass: Boolean)
    {
        try
        {
            val subFolder = "MapUploadGate"
            val dir = File(File(TPipeConfig.getTraceDir()), subFolder)
            if (!dir.exists()) dir.mkdirs()

            // Estimate the token count the safety classifier will see for
            // this post-downsample payload, using the empirical 0.627
            // tokens/byte PNG ratio. The estimate is conservative — actual
            // ratios observed in the live trace (2026-08-13) run ~0.35
            // t/b, well below the 0.627 ceiling. The estimate lets the
            // operator verify from the artifact alone that the safety
            // agent received a payload within budget. See
            // [MapUploadGateCallTokenReceiptTest] for the pin.
            val estimatedTokens = (imageBytes * TOKEN_BYTES_RATIO).toInt()
            val payload = """
                {
                  "playerId": "$playerId",
                  "imageBytes": $imageBytes,
                  "estimatedTokens": $estimatedTokens,
                  "tokenBudget": $SAFETY_TOKEN_BUDGET,
                  "tokenRatio": $TOKEN_BYTES_RATIO,
                  "safetyPass": $safetyPass,
                  "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent()

            writeStringToFile("${dir.absolutePath}/gate-call.json", payload)

            Logger.debug(LogCategory.SYSTEM, "MapUploadGate: saved gate-call summary (safetyPass=$safetyPass, imageBytes=$imageBytes, estimatedTokens=$estimatedTokens of budget=$SAFETY_TOKEN_BUDGET) for playerId=$playerId")
        }
        catch (e: Exception)
        {
            Logger.error(LogCategory.SYSTEM, "MapUploadGate: gate-call summary capture failed for playerId=$playerId: ${e.message}")
        }
    }
}
