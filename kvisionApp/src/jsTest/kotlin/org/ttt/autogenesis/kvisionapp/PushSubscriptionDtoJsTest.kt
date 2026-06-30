@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package org.ttt.autogenesis.kvisionapp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import structs.push.PushSubscriptionDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * jsTest that verifies the [PushSubscriptionDto] JSON contract used by the
 * Web Push subscription registration RPC.
 *
 * The DTO is encoded by the browser's [PushNotificationService] and decoded
 * by the server's [org.ttt.autogenesis.server.push.PushSubscriptionStore].
 * If the wire format drifts on either side, subscriptions silently fail to
 * register and the user never gets push notifications — exactly the kind of
 * cross-tier bug that demands an explicit round-trip test.
 */
class PushSubscriptionDtoJsTest
{
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun encodeThenDecodeProducesEquivalentDto()
    {
        val dto = PushSubscriptionDto(
            endpoint = "https://fcm.googleapis.com/fcm/send/cB3xYp_test_token",
            p256dh = "BKn2dZ7Y7aB-pX4Y7Y7aB-pX4Y7Y7aB-pX4Y7Y7aB-pX4Y7Y7aB-pX4Y7Y7aB-pX4Y7Y",
            auth = "testauth1234567890"
        )
        val encoded = json.encodeToString(PushSubscriptionDto.serializer(), dto)
        val decoded = json.decodeFromString(PushSubscriptionDto.serializer(), encoded)
        assertEquals(dto, decoded, "Round-tripped DTO must equal original")
    }

    @Test
    fun encodedJsonHasThreeFieldsInExpectedOrder()
    {
        val dto = PushSubscriptionDto(
            endpoint = "https://example.com/push",
            p256dh = "key",
            auth = "auth"
        )
        val encoded = json.encodeToString(PushSubscriptionDto.serializer(), dto)
        val obj = json.parseToJsonElement(encoded) as JsonObject
        // Field order matters because the server's VFS storage layer reads
        // by name; verify all three are present and string-typed.
        assertEquals(3, obj.size)
        assertTrue("endpoint" in obj.keys)
        assertTrue("p256dh" in obj.keys)
        assertTrue("auth" in obj.keys)
    }

    @Test
    fun decoderIgnoresUnknownFields()
    {
        val obj: JsonObject = buildJsonObject {
            put("endpoint", "https://example.com")
            put("p256dh", "key")
            put("auth", "auth")
            put("future_field_added_by_server", "should_be_ignored")
        }
        val dto = json.decodeFromJsonElement(PushSubscriptionDto.serializer(), obj)
        assertEquals("https://example.com", dto.endpoint)
        assertEquals("key", dto.p256dh)
        assertEquals("auth", dto.auth)
    }

    @Test
    fun encodedStringIsValidBase64UrlForP256dhAndAuth()
    {
        val dto = PushSubscriptionDto(
            endpoint = "https://example.com",
            p256dh = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef_-0123456789",
            auth = "ABCDEFG_-0123456789"
        )
        val encoded = json.encodeToString(PushSubscriptionDto.serializer(), dto)
        // The encoded string must preserve the Base64URL-safe characters
        // (no '+', '/', '=' padding) since the values go straight onto the
        // wire and the Push server treats them as Base64URL strings.
        assertTrue("+ char leaked" !in encoded, "encoded DTO must not contain '+': $encoded")
        assertTrue("/ char leaked" !in encoded, "encoded DTO must not contain '/': $encoded")
    }
}
