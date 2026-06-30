package structs

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.ttt.autogenesis.network.RpcJson

actual object MapPackManager {
    actual suspend fun pack(imageName: String, imageBytes: ByteArray, mapData: MapData): ByteArray {
        val packData = MapPackData(imageName, mapData)
        val jsonString = RpcJson.encodeToString(MapPackData.serializer(), packData)
        
        val outputStream = ByteArrayOutputStream()
        ZipOutputStream(outputStream).use { zip ->
            zip.putNextEntry(ZipEntry("map.json"))
            zip.write(jsonString.toByteArray())
            zip.closeEntry()
            
            zip.putNextEntry(ZipEntry(imageName))
            zip.write(imageBytes)
            zip.closeEntry()
        }
        
        return outputStream.toByteArray()
    }
    
    actual suspend fun unpack(packBytes: ByteArray): UnpackedMapPack {
        val inputStream = ByteArrayInputStream(packBytes)
        var mapPackData: MapPackData? = null
        var imageBytes: ByteArray? = null
        
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "map.json" -> {
                        val jsonBytes = zip.readBytes()
                        val jsonString = String(jsonBytes)
                        mapPackData = RpcJson.decodeFromString(MapPackData.serializer(), jsonString)
                    }
                    else -> {
                        imageBytes = zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        
        return UnpackedMapPack(
            imageName = mapPackData!!.imageName,
            imageBytes = imageBytes!!,
            mapData = mapPackData!!.mapData
        )
    }
}
