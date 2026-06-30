package org.ttt.autogenesis.server.push

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Test
import org.ttt.autogenesis.server.vfs.VirtualFileSystem
import structs.accelbyte.cloudsave.PlayerRecordResponse
import structs.push.PushSubscriptionDto
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for [PushSubscriptionStore] that mock the [VirtualFileSystem]
 * interface (cross-module `internal` access makes the real LocalVirtualFileSystem
 * hard to instantiate from outside the vfs package). Integration coverage
 * is provided by [PushNotificationServiceIntegrationTest] which exercises
 * the full path including the real store + real VFS via the server module's
 * vfs test machinery.
 */
class PushSubscriptionStoreTest
{
    private val testDto = PushSubscriptionDto(
        endpoint = "https://fcm.googleapis.com/fcm/send/test-token",
        p256dh = "BKn2dZ7Y7aB-pX4Y7Y7aB-pX4Y7Y7aB-pX4Y7Y7aB-pX4Y7Y7aB-pX4Y7Y7aB-pX4Y7Y",
        auth = "testauth1234567890"
    )

    private val mobileDto = PushSubscriptionDto(
        endpoint = "https://fcm.googleapis.com/fcm/send/mobile-token",
        p256dh = "BMobile-mobile-mobile-mobile-mobile-mobile-mobile-mobile-mobile-mobile-mobile-",
        auth = "mobileauth1234567890"
    )

    private fun envelopeOf(vararg dtos: PushSubscriptionDto): JsonElement = buildJsonObject {
        put("subscriptions", kotlinx.serialization.json.JsonArray(dtos.toList().map { dto ->
            kotlinx.serialization.json.JsonObject(mapOf(
                "endpoint" to JsonPrimitive(dto.endpoint),
                "p256dh" to JsonPrimitive(dto.p256dh),
                "auth" to JsonPrimitive(dto.auth),
            ))
        }))
    }

    private fun legacyFlatDto(dto: PushSubscriptionDto): JsonElement = buildJsonObject {
        put("endpoint", JsonPrimitive(dto.endpoint))
        put("p256dh", JsonPrimitive(dto.p256dh))
        put("auth", JsonPrimitive(dto.auth))
    }

    @Test
    fun putCallsSaveUserRecordWithSerializedEnvelope() = runBlocking {
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        // No existing record → upsert starts from an empty list.
        coEvery { vfs.fetchUserRecord("user-abc", "push-subscription") } returns Result.failure(
            RuntimeException("not found")
        )
        coEvery { vfs.saveUserRecord("user-abc", "push-subscription", any()) } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = envelopeOf(testDto))
        )
        val store = PushSubscriptionStore { vfs }
        store.put("user-abc", testDto)
        coVerify(exactly = 1) { vfs.saveUserRecord("user-abc", "push-subscription", any()) }
    }

    @Test
    fun putReplacesExistingSubscriptionByEndpoint() = runBlocking {
        // Multi-device: existing envelope contains a DTO whose endpoint matches
        // the new one. After put(), the envelope should contain exactly one DTO
        // (the new keys replaced the old ones for the same endpoint), not two.
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        val savedElement = slot<JsonElement>()
        coEvery { vfs.saveUserRecord("user-abc", "push-subscription", capture(savedElement)) } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = envelopeOf(testDto))
        )
        coEvery { vfs.fetchUserRecord("user-abc", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = envelopeOf(testDto, mobileDto))
        )
        val store = PushSubscriptionStore { vfs }
        val rotatedDto = testDto.copy(auth = "rotated-auth-12345678")
        store.put("user-abc", rotatedDto)
        val written: JsonElement = savedElement.captured
        assertTrue(written is JsonObject, "saved payload should be the envelope object")
        val arr = (written as JsonObject)["subscriptions"]
        assertNotNull(arr)
        val list = arr.toString()
        assertTrue(list.contains("rotated-auth-12345678"), "rotated auth should be present")
        assertFalse(list.contains("testauth1234567890"), "old auth should be replaced")
        // Mobile endpoint should still be there.
        assertTrue(list.contains("mobile-token"), "other devices must not be removed by a same-endpoint update")
    }

    @Test
    fun putAppendsNewEndpointToExistingList() = runBlocking {
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        coEvery { vfs.fetchUserRecord("user-abc", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = envelopeOf(testDto))
        )
        val savedElement = slot<JsonElement>()
        coEvery { vfs.saveUserRecord("user-abc", "push-subscription", capture(savedElement)) } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = envelopeOf(testDto, mobileDto))
        )
        val store = PushSubscriptionStore { vfs }
        store.put("user-abc", mobileDto)
        val written = savedElement.captured as JsonObject
        val list = written["subscriptions"].toString()
        assertTrue(list.contains("test-token"), "desktop endpoint should still be present")
        assertTrue(list.contains("mobile-token"), "mobile endpoint should be appended")
    }

    @Test
    fun getAllReturnsEmptyListWhenNoRecord() = runBlocking {
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        coEvery { vfs.fetchUserRecord("missing", "push-subscription") } returns Result.failure(
            RuntimeException("not found")
        )
        val store = PushSubscriptionStore { vfs }
        assertEquals(emptyList(), store.getAll("missing"))
    }

    @Test
    fun getAllReturnsAllStoredSubscriptions() = runBlocking {
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        coEvery { vfs.fetchUserRecord("user-abc", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = envelopeOf(testDto, mobileDto))
        )
        val store = PushSubscriptionStore { vfs }
        val all = store.getAll("user-abc")
        assertEquals(2, all.size)
        assertEquals(testDto, all[0])
        assertEquals(mobileDto, all[1])
    }

    @Test
    fun getAllMigratesLegacySingleDtoToList() = runBlocking {
        // Records written before the multi-device migration are stored as a
        // flat single DTO (no envelope). getAll must still find them.
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        coEvery { vfs.fetchUserRecord("user-legacy", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = legacyFlatDto(testDto))
        )
        val store = PushSubscriptionStore { vfs }
        val all = store.getAll("user-legacy")
        assertEquals(listOf(testDto), all)
    }

    @Test
    fun getAllMigratesAccelByteCloudSaveWrappedValue() = runBlocking {
        // AccelByte CloudSave wraps the stored value one level deep: the envelope
        // is stored as {"value": {"subscriptions": [...]}}. getAll must unwrap.
        val wrapped: JsonElement = buildJsonObject {
            put("value", envelopeOf(testDto, mobileDto))
        }
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        coEvery { vfs.fetchUserRecord("user-wrapped", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = wrapped)
        )
        val store = PushSubscriptionStore { vfs }
        val all = store.getAll("user-wrapped")
        assertEquals(2, all.size)
        assertEquals(testDto, all[0])
    }

    @Test
    fun getReturnsLastSubscriptionForBackwardsCompat() = runBlocking {
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        coEvery { vfs.fetchUserRecord("user-abc", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = envelopeOf(testDto, mobileDto))
        )
        val store = PushSubscriptionStore { vfs }
        // get() is the legacy single-subscription accessor; it returns the
        // most recently stored (last) entry.
        assertEquals(mobileDto, store.get("user-abc"))
    }

    @Test
    fun getReturnsNullWhenRecordEmpty() = runBlocking {
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        coEvery { vfs.fetchUserRecord("user-abc", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = null)
        )
        val store = PushSubscriptionStore { vfs }
        assertNull(store.get("user-abc"))
    }

    @Test
    fun removeByEndpointPrunesOnlyMatchingEntry() = runBlocking {
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        coEvery { vfs.fetchUserRecord("user-abc", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = envelopeOf(testDto, mobileDto))
        )
        val savedElement = slot<JsonElement>()
        coEvery { vfs.saveUserRecord("user-abc", "push-subscription", capture(savedElement)) } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = envelopeOf(mobileDto))
        )
        val store = PushSubscriptionStore { vfs }
        store.removeByEndpoint("user-abc", testDto.endpoint)
        val written = savedElement.captured as JsonObject
        val list = written["subscriptions"].toString()
        assertFalse(list.contains("test-token"), "matching endpoint must be removed")
        assertTrue(list.contains("mobile-token"), "other devices must remain")
    }

    @Test
    fun removeByEndpointDeletesRecordWhenLastEntryRemoved() = runBlocking {
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        coEvery { vfs.fetchUserRecord("user-abc", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = envelopeOf(testDto))
        )
        coEvery { vfs.deleteUserRecord("user-abc", "push-subscription") } returns Result.success(Unit)
        val store = PushSubscriptionStore { vfs }
        store.removeByEndpoint("user-abc", testDto.endpoint)
        coVerify(exactly = 1) { vfs.deleteUserRecord("user-abc", "push-subscription") }
    }

    @Test
    fun removeByEndpointIsIdempotentOnUnknownEndpoint() = runBlocking {
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        coEvery { vfs.fetchUserRecord("user-abc", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = envelopeOf(testDto))
        )
        val store = PushSubscriptionStore { vfs }
        store.removeByEndpoint("user-abc", "https://unknown.example/never-existed")
        coVerify(exactly = 0) { vfs.saveUserRecord(any(), any(), any()) }
        coVerify(exactly = 0) { vfs.deleteUserRecord(any(), any()) }
    }

    @Test
    fun removeDeletesAllSubscriptions() = runBlocking {
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        coEvery { vfs.deleteUserRecord("user-abc", "push-subscription") } returns Result.success(Unit)
        val store = PushSubscriptionStore { vfs }
        store.remove("user-abc")
        coVerify(exactly = 1) { vfs.deleteUserRecord("user-abc", "push-subscription") }
    }

    @Test
    fun blankAccelByteIdIsANoOpForAllMutators() = runBlocking {
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        val store = PushSubscriptionStore { vfs }
        store.put("", testDto)
        store.removeByEndpoint("", testDto.endpoint)
        store.remove("")
        coVerify(exactly = 0) { vfs.saveUserRecord(any(), any(), any()) }
        coVerify(exactly = 0) { vfs.deleteUserRecord(any(), any()) }
        coVerify(exactly = 0) { vfs.fetchUserRecord(any(), any()) }
        assertEquals(emptyList(), store.getAll(""))
    }
}
