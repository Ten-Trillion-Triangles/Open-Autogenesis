package org.ttt.autogenesis.network

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

/**
 * Reassembles chunked [RpcMessage.Multipart] messages.
 */
class MultipartAssembler {
    private val buffers = mutableMapOf<String, Array<String?>>()
    private val countReceived = mutableMapOf<String, Int>()

    /**
     * Adds a chunk to the assembler.
     * @return Reassembled string if complete, null otherwise.
     */
    fun addChunk(chunk: RpcMessage.Multipart): String? {
        val messageId = chunk.messageId
        val buffer = buffers.getOrPut(messageId) { arrayOfNulls<String>(chunk.totalChunks) }
        
        if (chunk.chunkIndex >= chunk.totalChunks) {
            Logger.error(LogCategory.NETWORK, "Received invalid chunk index ${chunk.chunkIndex} for total ${chunk.totalChunks}")
            return null
        }

        if (buffer[chunk.chunkIndex] != null) {
            // Duplicate chunk, ignore
            return null
        }

        buffer[chunk.chunkIndex] = chunk.data
        val currentCount = (countReceived[messageId] ?: 0) + 1
        countReceived[messageId] = currentCount

        if (currentCount == chunk.totalChunks) {
            // Complete
            val fullPayload = buffer.filterNotNull().joinToString("")
            
            // Cleanup
            buffers.remove(messageId)
            countReceived.remove(messageId)
            
            return fullPayload
        }

        return null
    }

    /**
     * Clears any incomplete messages.
     */
    fun clear() {
        buffers.clear()
        countReceived.clear()
    }
}
