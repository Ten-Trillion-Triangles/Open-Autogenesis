package org.ttt.autogenesis.network

import io.ktor.client.HttpClient
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope

expect suspend fun HttpClient.openSseChannel(
    scope: CoroutineScope,
    url: String,
    headers: Map<String, String>
): ByteReadChannel
