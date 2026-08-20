package ui

import globals.KEnv
import interfaces.WidgetInterface
import interfaces.updateData
import io.kvision.core.AlignItems
import io.kvision.core.CssSize
import io.kvision.core.JustifyContent
import io.kvision.core.JustifyItems
import io.kvision.core.UNIT
import io.kvision.core.onChange
import io.kvision.html.Input
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.p
import io.kvision.panel.HPanel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File
import org.w3c.files.FileList
import org.w3c.files.FileReader
import structs.MapPackManager
import kotlin.js.Promise

/**
 * Top menu bar which houses the contents of the settings buttons.
 */
class TopBar : HPanel(
    justify = JustifyContent.CENTER,
    alignItems = AlignItems.CENTER,
    spacing = 10
), WidgetInterface {
    var selectedImageFile: File? = null
        private set

    var selectedImageFiles: List<File> = emptyList()
        private set

    var onImageFilesSelected: ((List<File>) -> Unit)? = null

    private val hiddenFileInput = Input(InputType.FILE).apply {
        setStyle("display", "none")
        setAttribute("aria-hidden", "true")
        setAttribute("accept", "image/*")
        onChange {
            val files = getElement()?.unsafeCast<HTMLInputElement>()?.files
            val selection = files.toFileList()
            selectedImageFiles = selection
            selectedImageFile = selection.firstOrNull()
            onImageFilesSelected?.invoke(selection)

            val canvas = KEnv.getWidget("MapCanvas")

            if(canvas != null && selectedImageFile != null)
            {
                val imageFile = selectedImageFile!!
                val blobUrl = org.w3c.dom.url.URL.createObjectURL(imageFile)
                canvas.updateData(CanvasImageData(blobUrl))
            }


        }
    }
    
    private val hiddenZipInput = Input(InputType.FILE).apply {
        setStyle("display", "none")
        setAttribute("aria-hidden", "true")
        setAttribute("accept", ".map,.zip,application/zip")
        onChange {
            val files = getElement()?.unsafeCast<HTMLInputElement>()?.files
            val file = files?.item(0)
            if(file != null)
            {
                loadMapPack(file)
            }
        }
    }

    init {
        width = CssSize(100, UNIT.perc)
        height = CssSize(60, UNIT.px)
        background = io.kvision.core.Background(color = io.kvision.core.Color.name(io.kvision.core.Col.BLACK))
        color = io.kvision.core.Color.name(io.kvision.core.Col.WHITE)
        paddingLeft = CssSize(20, UNIT.px)
        paddingRight = CssSize(20, UNIT.px)

        //Load image button
        button(
            text = "Load Image"
        ) {
            fontSize = CssSize(18, UNIT.px)

            onClick {
                showFileDialog()
            }
        }

        //Load map pack button
        button(text = "Load Pack") {
            fontSize = CssSize(18, UNIT.px)
            onClick {
                showZipFileDialog()
            }
        }

        //Export button
        button(text = "Export Pack") {
            fontSize = CssSize(18, UNIT.px)
            onClick {
                exportMapPack()
            }
        }

        //Scenario button
        button(text = "Scenario") {
            fontSize = CssSize(18, UNIT.px)
            onClick {
                openScenarioDialog()
            }
        }

        //Writing Settings button
        button(text = "Writing Settings") {
            fontSize = CssSize(18, UNIT.px)
            onClick {
                openWritingSettingsDialog()
            }
        }

        add(hiddenFileInput)
        add(hiddenZipInput)
    }

    /**
     * Exports the current map as a ZIP file containing the image and map data.
     */
    private fun exportMapPack()
    {
        MainScope().launch {
            try
            {
                val canvas = KEnv.getWidget("MapCanvas") as? MapCanvas
                if(canvas == null)
                {
                    console.error("MapCanvas not found")
                    kotlinx.browser.window.alert("Error: Map canvas not found")
                    return@launch
                }
                
                console.log("Export started. selectedImageFile: ${selectedImageFile?.name ?: "null"}")
                
                val imageFile = selectedImageFile
                val imageBytes: ByteArray
                val imageName: String
                
                if(imageFile != null)
                {
                    // Synthetic File objects created from blob URLs (e.g. "map.png" from loadMapPack)
                    // may have stale underlying blob references that fail FileReader reads.
                    // In that case, fall back to the current canvas image source instead.
                    if(imageFile.name == "map.png")
                    {
                        console.log("selectedImageFile was created from blob (name=map.png), using canvas image source instead to avoid stale blob")
                        val imageSrc = canvas.getImageSrc()
                        if(imageSrc.startsWith("blob:") || imageSrc.startsWith("http"))
                        {
                            imageBytes = fetchImageAsBytes(imageSrc)
                            imageName = "map.png"
                        }
                        else
                        {
                            console.error("Cannot export: Canvas image source is not a valid URL")
                            kotlinx.browser.window.alert("Please reload the map image before exporting")
                            return@launch
                        }
                    }
                    else
                    {
                        console.log("Reading image from file: ${imageFile.name}")
                        imageBytes = readFileAsBytes(imageFile)
                        imageName = imageFile.name
                    }
                }
                else
                {
                    console.log("No file selected, fetching from canvas image src")
                    val imageSrc = canvas.getImageSrc()
                    console.log("Image src: $imageSrc")
                    
                    if(imageSrc.startsWith("blob:") || imageSrc.startsWith("http"))
                    {
                        imageBytes = fetchImageAsBytes(imageSrc)
                        imageName = "map.png"
                    }
                    else
                    {
                        console.error("Cannot export: No image loaded")
                        kotlinx.browser.window.alert("Please load an image first before exporting")
                        return@launch
                    }
                }
                
                console.log("Getting map data...")
                val mapData = canvas.getMapData()
                console.log("[TOPBAR] exportMapPack: mapData.writingAgentConfig.ruleCategories.size=${mapData.writingAgentConfig.ruleCategories.size}")
                mapData.writingAgentConfig.ruleCategories.forEachIndexed { index, cat ->
                    console.log("[TOPBAR] exportMapPack: mapData.ruleCategory[$index] '${cat.name}': chancePercent=${cat.chancePercent}, rules=${cat.rules.size}")
                }
                console.log("[TOPBAR] exportMapPack: mapData.storyWeights=(${mapData.writingAgentConfig.storyWeights.geopolitics}, ${mapData.writingAgentConfig.storyWeights.absurdity}, ${mapData.writingAgentConfig.storyWeights.dreamlikeQualities}, ${mapData.writingAgentConfig.storyWeights.unexpectedTwists})")
                console.log("Map data: ${mapData.pins.size} pins, ${mapData.connections.size} connections")
                
                console.log("Packing map...")
                val packBytes = MapPackManager.pack(imageName, imageBytes, mapData)
                console.log("Pack created: ${packBytes.size} bytes")
                
                downloadFile(packBytes, "map-pack.map")
                
                console.log("Map pack exported successfully")
                kotlinx.browser.window.alert("Map pack exported successfully!")
            }
            catch(e: Exception)
            {
                console.error("Export failed: ${e.message}")
                console.error(e)
                kotlinx.browser.window.alert("Export failed: ${e.message}")
            }
        }
    }
    
    /**
     * Fetches an image from a URL and returns it as a ByteArray.
     *
     * @param url The image URL to fetch.
     * @return ByteArray containing the image data.
     */
    private suspend fun fetchImageAsBytes(url: String): ByteArray
    {
        return suspendCoroutine { continuation ->
            val xhr = org.w3c.xhr.XMLHttpRequest()
            xhr.open("GET", url)
            xhr.asDynamic().responseType = "arraybuffer"
            
            xhr.onload = {
                if(xhr.status == 200.toShort())
                {
                    val arrayBuffer = xhr.response as ArrayBuffer
                    val uint8Array = Uint8Array(arrayBuffer)
                    val byteArray = ByteArray(uint8Array.length)
                    for(i in 0 until uint8Array.length)
                    {
                        byteArray[i] = uint8Array[i]
                    }
                    continuation.resume(byteArray)
                }
                else
                {
                    continuation.resumeWithException(Exception("Failed to fetch image: ${xhr.status}"))
                }
            }
            
            xhr.onerror = {
                continuation.resumeWithException(Exception("Network error while fetching image"))
            }
            
            xhr.send()
        }
    }
    
    /**
     * Reads a File object and returns its contents as a ByteArray.
     *
     * @param file The file to read.
     * @return ByteArray containing the file data.
     */
    private suspend fun readFileAsBytes(file: File): ByteArray
    {
        return suspendCoroutine { continuation ->
            val reader = FileReader()
            reader.onload = {
                val arrayBuffer = reader.result as ArrayBuffer
                val uint8Array = Uint8Array(arrayBuffer)
                val byteArray = ByteArray(uint8Array.length)
                for(i in 0 until uint8Array.length)
                {
                    byteArray[i] = uint8Array[i]
                }
                continuation.resume(byteArray)
            }
            reader.onerror = {
                continuation.resumeWithException(Exception("Failed to read file"))
            }
            reader.readAsArrayBuffer(file)
        }
    }
    
    /**
     * Triggers a browser download for the given byte array.
     *
     * @param bytes The file content as ByteArray.
     * @param filename The name for the downloaded file.
     */
    private fun downloadFile(bytes: ByteArray, filename: String)
    {
        val uint8Array = Uint8Array(bytes.size)
        for(i in bytes.indices)
        {
            uint8Array.asDynamic()[i] = bytes[i]
        }
        
        val blob = Blob(arrayOf(uint8Array), BlobPropertyBag(type = "application/zip"))
        val url = org.w3c.dom.url.URL.createObjectURL(blob)
        
        val a = kotlinx.browser.document.createElement("a") as HTMLAnchorElement
        a.href = url
        a.download = filename
        kotlinx.browser.document.body?.appendChild(a)
        a.click()
        kotlinx.browser.document.body?.removeChild(a)
        org.w3c.dom.url.URL.revokeObjectURL(url)
    }

    /**
     * Converts a FileList to a Kotlin List of File objects.
     *
     * @return List of File objects.
     */
    private fun FileList?.toFileList(): List<File>
    {
        if(this == null) return emptyList()
        val list = mutableListOf<File>()
        var index = 0
        while(true)
        {
            this.item(index)?.let(list::add) ?: break
            index++
        }
        return list
    }

    /**
     * Opens the file picker dialog for image selection.
     */
    private fun showFileDialog()
    {
        val input = hiddenFileInput.getElement()?.unsafeCast<HTMLInputElement>() ?: return
        input.value = ""
        input.click()
    }
    
    /**
     * Opens the file picker dialog for ZIP file selection.
     */
    private fun showZipFileDialog()
    {
        val input = hiddenZipInput.getElement()?.unsafeCast<HTMLInputElement>() ?: return
        input.value = ""
        input.click()
    }
    
    /**
     * Opens the scenario dialog to edit world name and story scenario.
     */
    private fun openScenarioDialog()
    {
        val canvas = KEnv.getWidget("MapCanvas") as? MapCanvas
        if(canvas == null)
        {
            console.error("MapCanvas not found")
            kotlinx.browser.window.alert("Error: Map canvas not found")
            return
        }

        console.log("Opening scenario dialog with canvas.worldName='${canvas.worldName}', canvas.storyScenario length=${canvas.storyScenario.length}")

        val dialog = ScenarioDialog(
            onSave = { worldName, storyScenario ->
                canvas.worldName = worldName
                canvas.storyScenario = storyScenario
                console.log("Scenario saved: worldName='$worldName', storyScenario length=${storyScenario.length}")
            },
            onCancel = {
                console.log("Scenario dialog cancelled")
            },
            initialWorldName = canvas.worldName,
            initialStoryScenario = canvas.storyScenario
        )

        KEnv.mainRoot?.add(dialog)
    }

    /**
     * Opens the writing settings dialog to configure author personality,
     * writing instructions, rule categories, and story weights.
     */
    private fun openWritingSettingsDialog()
    {
        val canvas = KEnv.getWidget("MapCanvas") as? MapCanvas
        if(canvas == null)
        {
            console.error("MapCanvas not found")
            kotlinx.browser.window.alert("Error: Map canvas not found")
            return
        }

        console.log("[TOPBAR] openWritingSettingsDialog: canvas.writingAgentConfig.ruleCategories.size=${canvas.writingAgentConfig.ruleCategories.size}")
        canvas.writingAgentConfig.ruleCategories.forEachIndexed { index, cat ->
            console.log("[TOPBAR] openWritingSettingsDialog: ruleCategory[$index] '${cat.name}': chancePercent=${cat.chancePercent}, rules=${cat.rules.size}")
        }
        console.log("[TOPBAR] openWritingSettingsDialog: canvas.writingAgentConfig.storyWeights=(${canvas.writingAgentConfig.storyWeights.geopolitics}, ${canvas.writingAgentConfig.storyWeights.absurdity}, ${canvas.writingAgentConfig.storyWeights.dreamlikeQualities}, ${canvas.writingAgentConfig.storyWeights.unexpectedTwists})")
        console.log("[TOPBAR] openWritingSettingsDialog: author='${canvas.author}', writingInstructions length=${canvas.writingInstructions.length}")

        val dialog = WritingSettingsDialog(
            onSave = { author, writingInstructions, config ->
                canvas.author = author
                canvas.writingInstructions = writingInstructions
                canvas.writingAgentConfig = config
                console.log("Writing settings saved: author='$author', writingInstructions length=${writingInstructions.length}")
                console.log("  config.selectionCriteria size=${config.selectionCriteria.size}")
                console.log("  config.ruleCategories size=${config.ruleCategories.size}")
                console.log("  config.storyWeights=(${config.storyWeights.geopolitics}, ${config.storyWeights.absurdity}, ${config.storyWeights.dreamlikeQualities}, ${config.storyWeights.unexpectedTwists})")
                console.log("  config.selectionStrategy=${config.selectionStrategy}")
                console.log("  config.authorEnabled=${config.authorEnabled}, config.alwaysApplyRulesEnabled=${config.alwaysApplyRulesEnabled}, config.guardrailsEnabled=${config.guardrailsEnabled}")
            },
            onCancel = {
                console.log("Writing settings dialog cancelled")
            },
            initialAuthor = canvas.author,
            initialWritingInstructions = canvas.writingInstructions,
            initialConfig = canvas.writingAgentConfig
        )

        KEnv.mainRoot?.add(dialog)
    }

    /**
     * Loads a map pack from a ZIP file and restores the map state.
     *
     * @param file The ZIP file containing the map pack.
     */
    private fun loadMapPack(file: File)
    {
        MainScope().launch {
            try
            {
                console.log("Loading map pack: ${file.name}")
                
                val packBytes = readFileAsBytes(file)
                console.log("Read ${packBytes.size} bytes from zip file")
                
                console.log("Calling MapPackManager.unpack()...")
                val unpacked = try
                {
                    MapPackManager.unpack(packBytes)
                }
                catch(e: Exception)
                {
                    console.error("Unpack failed:")
                    console.error("Error type: ${e::class.simpleName}")
                    console.error("Error message: ${e.message}")
                    console.error("Error cause: ${e.cause}")
                    console.error(e)
                    throw e
                }
                console.log("Unpacked: ${unpacked.imageName}, ${unpacked.mapData.pins.size} pins, ${unpacked.mapData.connections.size} connections")
                
                val canvas = KEnv.getWidget("MapCanvas") as? MapCanvas
                if(canvas == null)
                {
                    console.error("MapCanvas not found")
                    kotlinx.browser.window.alert("Error: Map canvas not found")
                    return@launch
                }
                
                console.log("Loading into canvas...")
                canvas.loadFromPack(unpacked)
                
                // Store the image as a proper File object for future exports
                console.log("Creating File object from image bytes...")
                val imageUint8Array = unpacked.imageBytes.toUint8Array()
                val imageBlob = Blob(arrayOf(imageUint8Array), BlobPropertyBag(type = "image/png"))
                
                // Create a File-like object with name property
                val fileObj = js("new File([imageBlob], imageName, {type: 'image/png'})")
                selectedImageFile = fileObj.unsafeCast<File>()
                console.log("Stored image file: ${selectedImageFile?.name}")
                
                console.log("Map pack loaded successfully")
                kotlinx.browser.window.alert("Map pack loaded successfully!")
            }
            catch(e: Exception)
            {
                console.error("Load failed: ${e.message}")
                console.error(e)
                kotlinx.browser.window.alert("Load failed: ${e.message ?: e.toString()}")
            }
        }
    }
}

/**
 * Suspends execution and provides a continuation for async operations.
 *
 * @param block The block that receives a continuation callback.
 * @return The result value from the continuation.
 */
private suspend fun <T> suspendCoroutine(block: (Continuation<T>) -> Unit): T
{
    return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        block(object : Continuation<T> {
            override fun resume(value: T)
            {
                continuation.resumeWith(Result.success(value))
            }
            override fun resumeWithException(exception: Throwable)
            {
                continuation.resumeWith(Result.failure(exception))
            }
        })
    }
}

/**
 * Continuation interface for async callbacks.
 */
private interface Continuation<T> {
    fun resume(value: T)
    fun resumeWithException(exception: Throwable)
}

/**
 * Converts a ByteArray to a Uint8Array for JavaScript interop.
 *
 * @return Uint8Array containing the same data.
 */
private fun ByteArray.toUint8Array(): Uint8Array
{
    val uint8Array = Uint8Array(this.size)
    for(i in this.indices)
    {
        uint8Array.asDynamic()[i] = this[i]
    }
    return uint8Array
}
