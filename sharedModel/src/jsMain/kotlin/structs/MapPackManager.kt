package structs

import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import org.khronos.webgl.Uint8Array
import org.ttt.autogenesis.network.RpcJson
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import kotlin.js.Promise

@JsModule("jszip")
@JsNonModule
external class JSZip {
    fun file(name: String, data: Any): JSZip
    fun file(name: String): JSZipObject?
    fun generateAsync(options: dynamic): Promise<Any>
    
    companion object {
        fun loadAsync(data: Any): Promise<JSZip>
    }
}

external class JSZipObject {
    fun async(type: String): Promise<Any>
}

actual object MapPackManager {
    actual suspend fun pack(imageName: String, imageBytes: ByteArray, mapData: MapData): ByteArray {
        console.log("[MPM] pack: writingAgentConfig.ruleCategories.size=${mapData.writingAgentConfig.ruleCategories.size}")
        mapData.writingAgentConfig.ruleCategories.forEachIndexed { index, cat ->
            console.log("[MPM] pack: ruleCategory[$index] '${cat.name}': chancePercent=${cat.chancePercent}, rules=${cat.rules.size}")
        }
        val packData = MapPackData(imageName, mapData)
        val jsonString = RpcJson.encodeToString(MapPackData.serializer(), packData)
        
        val zip = JSZip()
        zip.file("map.json", jsonString)
        zip.file(imageName, imageBytes.toUint8Array())
        
        val options = js("{}")
        options.type = "uint8array"
        
        val uint8Array = zip.generateAsync(options).await() as Uint8Array
        return uint8Array.toByteArray()
    }
    
    actual suspend fun unpack(packBytes: ByteArray): UnpackedMapPack {
        val uint8Array = packBytes.toUint8Array()
        val zip = JSZip.loadAsync(uint8Array).await()
        
        val jsonFile = zip.file("map.json")
        val jsonString = jsonFile!!.async("string").await() as String
        console.log("[MPM] unpack: map.json size=${jsonString.length} chars")
        val mapPackData = RpcJson.decodeFromString(MapPackData.serializer(), jsonString)
        console.log("[MPM] unpack: mapPackData.mapData.writingAgentConfig.ruleCategories.size=${mapPackData.mapData.writingAgentConfig.ruleCategories.size}")
        mapPackData.mapData.writingAgentConfig.ruleCategories.forEachIndexed { index, cat ->
            console.log("[MPM] unpack: ruleCategory[$index] '${cat.name}': chancePercent=${cat.chancePercent}, rules=${cat.rules.size}")
        }
        
        val imageFile = zip.file(mapPackData.imageName)
        val imageUint8Array = imageFile!!.async("uint8array").await() as Uint8Array
        val imageBytes = imageUint8Array.toByteArray()
        
        return UnpackedMapPack(
            imageName = mapPackData.imageName,
            imageBytes = imageBytes,
            mapData = mapPackData.mapData
        )
    }
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val dynamicBytes = this.asDynamic()
    return Uint8Array(dynamicBytes.buffer, dynamicBytes.byteOffset, dynamicBytes.byteLength)
}

private fun Uint8Array.toByteArray(): ByteArray {
    val dynamicBytes = this.asDynamic()
    return Int8Array(dynamicBytes.buffer, dynamicBytes.byteOffset, dynamicBytes.byteLength).asDynamic() as ByteArray
}
