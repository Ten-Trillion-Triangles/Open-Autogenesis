package org.ttt.autogenesis.network

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.copyTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

actual suspend fun HttpClient.openSseChannel(
    scope: CoroutineScope,
    url: String,
    headers: Map<String, String>
): ByteReadChannel {
    val channel = ByteChannel(autoFlush = true)
    val job = scope.launch {
        var closeCause: Throwable? = null
        try {
            prepareGet(url) {
                headers.forEach { (name, value) ->
                    this.headers.append(name, value)
                }
            }.execute { response ->
                response.bodyAsChannel().copyTo(channel)
            }
        } catch (err: Throwable) {
            closeCause = err
            throw err
        } finally {
            if (closeCause != null) {
                channel.cancel(closeCause)
            } else {
                channel.close()
            }
        }
    }
    return channel
}