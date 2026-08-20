package agent.builders

import bedrockPipe.BedrockMultimodalPipe
import bedrockPipe.BedrockPriorityTier
import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import globals.BedrockConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.Json.Default.encodeToJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import matchmaking.UrlHandoverRegistry
import network.MapUploadErrorHandlers
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.MapUploadRequest
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.network.RpcMessage
import org.ttt.autogenesis.network.RpcRegistry
import org.ttt.autogenesis.server.UiSignalRpcHandlers
import org.ttt.autogenesis.serverextend.RestPlayerConnectionManager
import structs.MapData

@kotlinx.serialization.Serializable
data class MapSafetyCheck(
    var isAllowed: Boolean = false,
    var reason: String = ""
)

/**
 * Payload the safety pipes operate on after the gate has unpacked the
 * client-side zip. The image bytes are the multimodal fragment the image
 * classifier consumes; the [MapData] is the structured fragment the content
 * classifier consumes.
 *
 * @param imageBytes Raw image bytes extracted from the zip's image entry.
 *   Inferred MIME type is `image/png` for the image classification pipe.
 * @param mapData The structured map data parsed from the zip's `map.json`
 *   entry. Contains the writing-agent config, story scenario, and policy
 *   fields that the content classifier inspects.
 */
internal data class MapSafetyPayload(
    val imageBytes: ByteArray,
    val mapData: MapData
)

/**
 * Builds the map-safety validation pipeline.
 *
 * The pipe's `setOnFailure` extracts the rejection reason from the LLM
 * output and the originating `playerId` from the parent pipe's
 * MiniBank, then hands both to [MapUploadErrorHandlers.sendMapUploadError]
 * which owns the RPC plumbing. The pipe itself never touches JSON, RPC,
 * or the connection manager — that is the singleton's responsibility.
 *
 * The caller (the gate layer) drives the pipeline with a [MultimodalContent]
 * carrying the unpacked payload as a binary fragment. The image bytes flow
 * into the image-checker pipe via the multimodal payload; the [MapData] is
 * stashed on the content-checker pipe via [setJsonInput] (already wired in
 * this builder) so the LLM validator can read it from `input.text`.
 *
 * @param playerId Identifier of the player whose map is being validated.
 *   Stored in the parent pipe's MiniBank so the failure callback can
 *   resolve the originating client without the caller threading a
 *   reference through every pipe stage.
 * @param payload Unpacked map payload (image bytes + structured MapData)
 *   produced by the gate layer from `MapPackManager.unpack`.
 */
internal suspend fun buildMapSafetyAgent(
    playerId: String,
    payload: MapSafetyPayload
) : Pipeline
{
    // Crack the payload open at the top so both pipes can read the
    // image bytes and the structured map data as named locals instead of
    // dereferencing `payload.imageBytes` / `payload.mapData` at every site.
    val imageBytes = payload.imageBytes
    val mapData = payload.mapData

    //Declare pipe properties.
    val imageChecker = BedrockMultimodalPipe().apply {
        setPipeName("image pipe")
        setRegion("us-east-2")
        useConverseApi()
        setServiceTier(BedrockPriorityTier.Flex)
        setModel(BedrockConfig.novaModelName)
        setTemperature(.6)
        setTopP(.7)
        setReasoning("high")
        setTokenBudget(BedrockConfig.novaBudgetSettings)

        //Bind the unpacked image bytes onto the pipe's metadata so the
        //classifier can read them from the pipe's local state instead of
        //threading them through the multimodal payload.
        pipeMetadata["imageBytes"] = imageBytes

        //DITL pre-init: rebuild the inbound MultimodalContent so this pipe
        //only sees the image. Pull imageBytes out of the pipe's own
        //metadata, wipe the inbound text + binaries, and reattach the
        //image as a fresh binary fragment. Anything the gate layer
        //carried over (raw text prompts, sibling map JSON) is discarded
        //here so the image classifier has a clean view of the picture.
        //If the metadata pull returns null (the artifact was never
        //bound upstream) we mark the content object for termination
        //rather than letting the LLM fire on an empty content fragment.
        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "MapSafety: imageCheckerPipe.setPreInitFunction entry")
            val img = pipeMetadata["imageBytes"] as? ByteArray
            if (img != null) {
                it.text = ""
                it.binaryContent = mutableListOf()
                it.addBinary(img, mimeType = "image/png", filename = "map-image.png")
            } else {
                it.terminatePipeline = true
            }
            Logger.debug(LogCategory.SYSTEM, "MapSafety: imageCheckerPipe.setPreInitFunction success")
        }

        setSystemPrompt("""You are a map safety check agent. Your job is to make sure that the map about to be
            |uploaded does not violate the following safety policies:
            |
            |- Does not contain pornography
            |- Does not contain anything related to CSAM or child abuse
            |- Does not contain obviously other illegal content in it's imagery.
            |- Is clearly a drawing of a map of some kind
            |
        """.trimMargin())

        setJsonOutput(MapSafetyCheck::class)

        setFooterPrompt("""
            You will be provided an image of the map to examine, verify the image adheres to these rules and is safe.
            If it is not safe, set the value of isAllowed to false, and state the reason why in the reason variable
            of your json output.
        """.trimIndent())

        //Declare validator function.
        setValidatorFunction {
            val output = it.text
            val result = extractJson<MapSafetyCheck>(output) ?: MapSafetyCheck()

            return@setValidatorFunction result.isAllowed
        }

        //Stash the playerId in the parent pipe's MiniBank before any LLM
        //call so the failure callback can resolve the originating client.
        //The MiniBank lives on the pipe itself and survives the LLM round-trip.
        val newWindow = ContextWindow()
        newWindow.contextElements.add(playerId)
        getMiniContextBankObject().contextMap["id"] = newWindow

        //Now define the failure catch if it flags the content as a safety violation.
        setOnFailure { original, processed ->
            //Extract the rejection reason from the LLM output.
            val resultText = processed.text
            val safetyResult = extractJson<MapSafetyCheck>(resultText) ?: MapSafetyCheck()
            val failureReason = safetyResult.reason

            //Fetch the originating playerId from the parent pipe's MiniBank.
            //The lambda parameter is `original` (the MultimodalContent flowing
            //into the pipe); its `currentPipe` reference gives us the bank.
            val parentPipe = original.currentPipe
            val miniBank = parentPipe?.getMiniContextBankObject() ?: MiniBank()
            val id = miniBank.contextMap["id"]?.contextElements?.last() ?: ""

            //Build rpc and send error downstream
            MapUploadErrorHandlers.sendMapUploadError(id, failureReason)
            processed.terminatePipeline = true //Kill pipeline here.


            return@setOnFailure processed
        }

    }

    val contentChecker = BedrockMultimodalPipe().apply {
        setPipeName("text pipe")
        setRegion("us-east-2")
        useConverseApi()
        setServiceTier(BedrockPriorityTier.Flex)
        setModel(BedrockConfig.novaModelName)
        setTemperature(.6)
        setTopP(.7)
        setReasoning("high")
        setTokenBudget(BedrockConfig.novaBudgetSettings)

        //Serialize the structured MapData to JSON and bind it onto the
        //pipe's metadata. com.TTT.Util.serialize handles AI-malformed-JSON
        //resilience in addition to the straight kotlinx round-trip, so the
        //stored string survives both canonical and LLM-flavoured inputs.
        //Downstream readers (validators, failure callbacks, tracers) can
        //pull the string out of pipeMetadata["mapDataJson"] without
        //re-walking the typed object.
        pipeMetadata["mapDataJson"] = serialize(mapData)

        //DITL pre-init: rebuild the inbound MultimodalContent so this pipe
        //only sees the serialized map data. Pull mapDataJson out of the
        //pipe's own metadata, wipe the inbound binaries, and reattach
        //the JSON as the pipe's text fragment. The image (if any leaked
        //in from upstream) is discarded here so the content classifier
        //does not waste tokens on a picture it has no policy to judge.
        //If the metadata pull returns null (the artifact was never
        //bound upstream) we mark the content object for termination
        //rather than letting the LLM fire on an empty content fragment.
        setPreInitFunction {
            Logger.debug(LogCategory.SYSTEM, "MapSafety: contentCheckerPipe.setPreInitFunction entry")
            val mapJson = pipeMetadata["mapDataJson"] as? String
            if (mapJson != null) {
                it.binaryContent = mutableListOf()
                it.text = mapJson
            } else {
                it.terminatePipeline = true
            }
            Logger.debug(LogCategory.SYSTEM, "MapSafety: contentCheckerPipe.setPreInitFunction success")
        }

        setSystemPrompt("""Your job is to check the content of the map: The text data and stories for illegal
            |or harmful content based on the policy we define below:
            |
            |- Support of nazi, neo-nazi, far right, or fascist ideology as propaganda or clear glorification in
            |an extreme and non-satirical manner. This includes eugenics, far right terrorist organizations, 
            |critical race theory, white replacement theory, pro MAGA or Donald Trump propaganda in a non-satirical or
            |non-hostile to Maga way, Anti-LBGQ propaganda, support of the KKK etc.
            |- Any child abuse material or clear CSAM content. This must be blatant and very explicit to count. 
            |It should be obvious. Teen dramas, fanfics and things that are not clearly obvious violations of this also
            |don't count. It must be clear, and illegal cases of CSAM which we do not want anywhere near our servers or
            |systems.
        """.trimMargin())

        setJsonInput(MapData::class)
        setJsonInput(MapSafetyCheck::class)

        setFooterPrompt("""If you find data in the text that is in blatant violation return false for isAllowed and
            |state the reason why in the reason variable of your json output.
        """.trimMargin())

        setValidatorFunction {
            val result = it.text
            val json = extractJson<MapSafetyCheck>(result) ?: MapSafetyCheck()

            return@setValidatorFunction json.isAllowed
        }

        setOnFailure { original, processed ->
            val resultText = processed.text
            val safeResult = extractJson<MapSafetyCheck>(resultText) ?: MapSafetyCheck()
            val failureReason = safeResult.reason

            val parentPipe = original.currentPipe
            val miniBank = parentPipe?.getMiniContextBankObject() ?: MiniBank()
            val id = miniBank.contextMap["id"]?.contextElements?.last() ?: ""

            MapUploadErrorHandlers.sendMapUploadError(id, failureReason)
            processed.terminatePipeline = true

            return@setOnFailure processed
        }
    }

    return Pipeline().apply {
        add(imageChecker)
        add(contentChecker)
        init(true)
    }
}