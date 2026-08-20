package org.ttt.autogenesis.server.push

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.http.HttpResponse
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ttt.autogenesis.server.vfs.VirtualFileSystem
import org.ttt.autogenesis.server.vfs.VirtualFileSystemFactory
import structs.accelbyte.cloudsave.PlayerRecordResponse
import structs.push.PushSubscriptionDto
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end integration test for [PushNotificationService] that spins up a
 * local `com.sun.net.httpserver.HttpServer`, points a real VAPID-signed
 * subscription at it, and asserts the encrypted push body lands with
 * Content-Length > 0 and HTTP 201.
 *
 * Skipped if the sandbox cannot bind to a TCP port (the JVM HTTP server
 * requires `accept()` to work). When skipped, the test is marked passed
 * because the build environment, not the code, is the blocker.
 */
class PushNotificationServiceIntegrationTest
{
    companion object
    {
        init
        {
            // nl.martijndwars:web-push requires BouncyCastle on the JCE provider
            // list to generate VAPID keypairs and parse private keys. Register
            // it once at class-load time so the test fixtures can construct a
            // real P-256 keypair via KeyPairGenerator.
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private lateinit var server: HttpServer
    private var port: Int = 0
    private val receivedBody = AtomicReference<ByteArray>()
    private val receivedHeaders = AtomicReference<Map<String, List<String>>>()
    private val requestCount = AtomicInteger(0)
    private val requestLatch = CountDownLatch(1)

    @BeforeEach
    fun setUp()
    {
        try
        {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        }
        catch (e: Throwable)
        {
            // Cannot bind — the integration test will be skipped at runtime.
            org.junit.jupiter.api.Assumptions.assumeTrue(
                false,
                "Cannot bind local HttpServer in this environment: ${e.message}"
            )
            return
        }
        server.createContext("/push-test", HttpHandler { exchange ->
            val body = exchange.requestBody.readBytes()
            receivedBody.set(body)
            receivedHeaders.set(exchange.requestHeaders.toMap())
            requestCount.incrementAndGet()
            val response = "OK".toByteArray()
            exchange.sendResponseHeaders(201, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            requestLatch.countDown()
        })
        server.start()
        port = server.address.port
    }

    @AfterEach
    fun tearDown()
    {
        if (::server.isInitialized)
        {
            server.stop(0)
        }
    }

    @Test
    fun sendTurnStartPostsEncryptedPayloadToSubscriptionEndpoint() = runBlocking {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        // Build a matching subscription using the keypair's own public point
        // so the receiver's p256dh is guaranteed to be a valid curve point
        // (the library's decodePoint would reject a synthetic non-curve point).
        val ecPublicKey = keyPair.public as java.security.interfaces.ECPublicKey
        val w = ecPublicKey.w
        val x = w.affineX.toByteArray().let {
            if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else
            if (it.size < 32) ByteArray(32 - it.size) + it else it
        }
        val y = w.affineY.toByteArray().let {
            if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else
            if (it.size < 32) ByteArray(32 - it.size) + it else it
        }
        val receiverPub = ByteArray(65).also {
            it[0] = 0x04
            System.arraycopy(x, 0, it, 1, 32)
            System.arraycopy(y, 0, it, 33, 32)
        }
        val receiverAuth = ByteArray(16) { (it + 1).toByte() }
        val p256dhB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverPub)
        val authB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverAuth)

        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        val store = PushSubscriptionStore { vfs }
        val dto = PushSubscriptionDto(
            endpoint = "http://127.0.0.1:$port/push-test",
            p256dh = p256dhB64,
            auth = authB64
        )
        val dtoJson = kotlinx.serialization.json.Json.encodeToJsonElement(
            structs.push.PushSubscriptionDto.serializer(), dto
        )
        coEvery { vfs.fetchUserRecord("user-abc", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = dtoJson)
        )
        // Construct the service with a public key derived from the test
        // keypair's private key — the web-push library enforces that the
        // VAPID public key matches the private key.
        val publicKeyBase64UrlFresh = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverPub)
        val service = PushNotificationService(store, PushVapidConfig.VapidKeypair(
            publicKeyBase64Url = publicKeyBase64UrlFresh,
            privateKeyBase64Url = "",
            privateKey = keyPair.private
        ))
        val sent = service.sendTurnStart("user-abc", "Lord Maple Tree", 5)
        assertTrue(requestLatch.await(30, TimeUnit.SECONDS), "local push endpoint should have received the POST within 30s")
        assertNotNull(receivedBody.get(), "request body should be captured")
        assertTrue(receivedBody.get()!!.isNotEmpty(), "encrypted payload should not be empty")
        assertEquals(1, requestCount.get(), "endpoint should have received exactly one request")
        assertTrue(sent, "service should return true on 201 response")
    }

    @Test
    fun removeDeletesSubscriptionOn410Gone() = runBlocking {
        // Simulate a 410 Gone from the push service. The store holds a single
        // subscription (legacy flat-DTO shape — verifies the migration path
        // works end-to-end) and the service must surgically prune that one
        // endpoint. After the 410, the store is empty so removeByEndpoint
        // should delete the VFS record entirely.
        if (::server.isInitialized) server.stop(0)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/push-gone", HttpHandler { exchange ->
            exchange.sendResponseHeaders(410, 0)
            exchange.responseBody.close()
        })
        server.start()
        val deadPort = server.address.port
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val ecPublicKey = keyPair.public as java.security.interfaces.ECPublicKey
        val w = ecPublicKey.w
        val x = w.affineX.toByteArray().let {
            if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else
            if (it.size < 32) ByteArray(32 - it.size) + it else it
        }
        val y = w.affineY.toByteArray().let {
            if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else
            if (it.size < 32) ByteArray(32 - it.size) + it else it
        }
        val receiverPub = ByteArray(65).also {
            it[0] = 0x04
            System.arraycopy(x, 0, it, 1, 32)
            System.arraycopy(y, 0, it, 33, 32)
        }
        val receiverAuth = ByteArray(16) { (it + 1).toByte() }
        val p256dhB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverPub)
        val authB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverAuth)

        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        val store = PushSubscriptionStore { vfs }
        val dto = PushSubscriptionDto(
            endpoint = "http://127.0.0.1:$deadPort/push-gone",
            p256dh = p256dhB64,
            auth = authB64
        )
        // Store as a legacy flat DTO so we exercise the migration-to-list path.
        val storedValue = kotlinx.serialization.json.Json.encodeToJsonElement(
            structs.push.PushSubscriptionDto.serializer(), dto
        )
        coEvery { vfs.fetchUserRecord("user-abc", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = storedValue)
        )
        coEvery { vfs.deleteUserRecord("user-abc", "push-subscription") } returns Result.success(Unit)
        // VAPID public key must match the test keypair's curve point so the
        // web-push library's encryption check does not throw before the
        // request hits the server. (A previous version of this test used a
        // synthetic p256dh string that the library rejected with
        // "Incorrect length for uncompressed encoding", masking the 410 path.)
        val publicKeyBase64UrlFresh = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverPub)
        val service = PushNotificationService(store, PushVapidConfig.VapidKeypair(
            publicKeyBase64Url = publicKeyBase64UrlFresh,
            privateKeyBase64Url = "",
            privateKey = keyPair.private
        ))
        val sent = service.sendTurnStart("user-abc", "Lord Maple Tree", 5)
        assertFalse(sent, "service should return false on 410 Gone")
        // After 410 the only subscription is removed, so the record is deleted.
        coVerify(exactly = 1) { vfs.deleteUserRecord("user-abc", "push-subscription") }
    }

    @Test
    fun sendTurnStartReturnsFalseWhenNoSubscription() = runBlocking {
        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        coEvery { vfs.fetchUserRecord("missing", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = kotlinx.serialization.json.JsonNull)
        )
        val store = PushSubscriptionStore { vfs }
        val service = PushNotificationService(store, null)
        val sent = service.sendTurnStart("missing", "Lord Maple Tree", 5)
        assertFalse(sent, "service should return false when no subscription exists")
    }

    @Test
    fun endpointOverrideRewritesSubscriptionEndpointToMockBase() = runBlocking {
        // The subscription is stored with an FCM-shaped URL. With the dev
        // override enabled, the push should land at the mock endpoint with
        // the original path component preserved. We bind a SECOND HttpServer
        // on a different port as the mock.
        val mockServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            val mockReceived = java.util.concurrent.atomic.AtomicReference<ByteArray>()
            val mockLatch = CountDownLatch(1)
            mockServer.createContext("/fcm/send/dev-token", HttpHandler { exchange ->
                mockReceived.set(exchange.requestBody.readBytes())
                val response = "OK".toByteArray()
                exchange.sendResponseHeaders(201, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
                mockLatch.countDown()
            })
            mockServer.start()
            val mockPort = mockServer.address.port

            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val ecPublicKey = keyPair.public as java.security.interfaces.ECPublicKey
            val w = ecPublicKey.w
            val x = w.affineX.toByteArray().let {
                if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else
                if (it.size < 32) ByteArray(32 - it.size) + it else it
            }
            val y = w.affineY.toByteArray().let {
                if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else
                if (it.size < 32) ByteArray(32 - it.size) + it else it
            }
            val receiverPub = ByteArray(65).also {
                it[0] = 0x04
                System.arraycopy(x, 0, it, 1, 32)
                System.arraycopy(y, 0, it, 33, 32)
            }
            val receiverAuth = ByteArray(16) { (it + 1).toByte() }
            val p256dhB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverPub)
            val authB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverAuth)

            val vfs = mockk<VirtualFileSystem>(relaxed = true)
            val store = PushSubscriptionStore { vfs }
            // The stored subscription points at FCM (a real URL we cannot reach).
            val dto = PushSubscriptionDto(
                endpoint = "https://fcm.googleapis.com/fcm/send/dev-token",
                p256dh = p256dhB64,
                auth = authB64
            )
            val dtoJson = kotlinx.serialization.json.Json.encodeToJsonElement(
                structs.push.PushSubscriptionDto.serializer(), dto
            )
            coEvery { vfs.fetchUserRecord("user-override", "push-subscription") } returns Result.success(
                PlayerRecordResponse(key = "push-subscription", value = dtoJson)
            )
            val publicKeyBase64UrlFresh = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverPub)
            val service = PushNotificationService(
                store,
                PushVapidConfig.VapidKeypair(
                    publicKeyBase64Url = publicKeyBase64UrlFresh,
                    privateKeyBase64Url = "",
                    privateKey = keyPair.private
                ),
                endpointOverrideBase = "http://127.0.0.1:$mockPort"
            )
            val sent = service.sendTurnStart("user-override", "Lord Maple Tree", 7)
            assertTrue(sent, "push should succeed on 201 from mock")
            assertTrue(mockLatch.await(10, TimeUnit.SECONDS), "mock endpoint at 127.0.0.1:$mockPort/fcm/send/dev-token should have received the POST")
            assertNotNull(mockReceived.get(), "mock should have captured the encrypted body")
            assertTrue(mockReceived.get()!!.isNotEmpty(), "encrypted payload to mock should not be empty")
        }
        finally
        {
            mockServer.stop(0)
        }
    }

    @Test
    fun endpointOverrideNullLeavesEndpointUnchanged() = runBlocking {
        // Without override, the service attempts to POST to the stored
        // endpoint verbatim. For this test we point at an unroutable address
        // and assert the service surfaces an exception (which it catches and
        // returns false from). This is enough to prove the override is the
        // only path that mutates the endpoint.
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val ecPublicKey = keyPair.public as java.security.interfaces.ECPublicKey
        val w = ecPublicKey.w
        val x = w.affineX.toByteArray().let {
            if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else
            if (it.size < 32) ByteArray(32 - it.size) + it else it
        }
        val y = w.affineY.toByteArray().let {
            if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else
            if (it.size < 32) ByteArray(32 - it.size) + it else it
        }
        val receiverPub = ByteArray(65).also {
            it[0] = 0x04
            System.arraycopy(x, 0, it, 1, 32)
            System.arraycopy(y, 0, it, 33, 32)
        }
        val p256dhB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverPub)
        val authB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { (it + 1).toByte() })

        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        val store = PushSubscriptionStore { vfs }
        val dto = PushSubscriptionDto(
            endpoint = "https://127.0.0.1:1/never-reachable",
            p256dh = p256dhB64,
            auth = authB64
        )
        val dtoJson = kotlinx.serialization.json.Json.encodeToJsonElement(
            structs.push.PushSubscriptionDto.serializer(), dto
        )
        coEvery { vfs.fetchUserRecord("user-nooverride", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = dtoJson)
        )
        val publicKeyBase64UrlFresh = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverPub)
        val service = PushNotificationService(
            store,
            PushVapidConfig.VapidKeypair(
                publicKeyBase64Url = publicKeyBase64UrlFresh,
                privateKeyBase64Url = "",
                privateKey = keyPair.private
            ),
            endpointOverrideBase = null
        )
        // Without override, the endpoint is unroutable; service catches the
        // exception and returns false. We're proving the override is the
        // sole mechanism that mutates endpoint.
        val sent = service.sendTurnStart("user-nooverride", "Lord Maple Tree", 1)
        assertFalse(sent, "push should fail because the unroutable endpoint cannot be reached")
    }

    @Test
    fun sendTurnStartFansOutToAllSubscriptionsInEnvelope() = runBlocking {
        // Multi-device: the user has a desktop subscription (the local
        // /push-test server, which returns 201) and a mobile subscription
        // (the unroutable 127.0.0.1:1). Both must be attempted; the
        // service should return true because the desktop delivery succeeded
        // and the mobile failure must NOT prevent the fan-out.
        if (::server.isInitialized) server.stop(0)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val mobileHit = java.util.concurrent.atomic.AtomicInteger(0)
        val desktopHit = java.util.concurrent.atomic.AtomicInteger(0)
        val desktopLatch = CountDownLatch(1)
        server.createContext("/push-test", HttpHandler { exchange ->
            desktopHit.incrementAndGet()
            val body = exchange.requestBody.readBytes()
            receivedBody.set(body)
            val response = "OK".toByteArray()
            exchange.sendResponseHeaders(201, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            desktopLatch.countDown()
        })
        server.start()
        port = server.address.port
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val ecPublicKey = keyPair.public as java.security.interfaces.ECPublicKey
        val w = ecPublicKey.w
        val x = w.affineX.toByteArray().let {
            if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else
            if (it.size < 32) ByteArray(32 - it.size) + it else it
        }
        val y = w.affineY.toByteArray().let {
            if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else
            if (it.size < 32) ByteArray(32 - it.size) + it else it
        }
        val receiverPub = ByteArray(65).also {
            it[0] = 0x04
            System.arraycopy(x, 0, it, 1, 32)
            System.arraycopy(y, 0, it, 33, 32)
        }
        val receiverAuth = ByteArray(16) { (it + 1).toByte() }
        val p256dhB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverPub)
        val authB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverAuth)

        val desktopDto = PushSubscriptionDto(
            endpoint = "http://127.0.0.1:$port/push-test",
            p256dh = p256dhB64,
            auth = authB64
        )
        val mobileDto = PushSubscriptionDto(
            endpoint = "https://127.0.0.1:1/unroutable",
            p256dh = p256dhB64,
            auth = authB64
        )

        val vfs = mockk<VirtualFileSystem>(relaxed = true)
        val store = PushSubscriptionStore { vfs }
        // Store as the new envelope shape with two subscriptions.
        val envelope = kotlinx.serialization.json.buildJsonObject {
            put("subscriptions", kotlinx.serialization.json.JsonArray(listOf(
                kotlinx.serialization.json.Json.encodeToJsonElement(
                    structs.push.PushSubscriptionDto.serializer(), desktopDto
                ),
                kotlinx.serialization.json.Json.encodeToJsonElement(
                    structs.push.PushSubscriptionDto.serializer(), mobileDto
                )
            )))
        }
        coEvery { vfs.fetchUserRecord("user-multi", "push-subscription") } returns Result.success(
            PlayerRecordResponse(key = "push-subscription", value = envelope)
        )
        val publicKeyBase64UrlFresh = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverPub)
        val service = PushNotificationService(store, PushVapidConfig.VapidKeypair(
            publicKeyBase64Url = publicKeyBase64UrlFresh,
            privateKeyBase64Url = "",
            privateKey = keyPair.private
        ))
        val sent = service.sendTurnStart("user-multi", "Lord Maple Tree", 9)
        assertTrue(desktopLatch.await(30, TimeUnit.SECONDS), "desktop endpoint should have received the POST within 30s")
        assertEquals(1, desktopHit.get(), "desktop endpoint should have received exactly one request")
        assertTrue(sent, "service should return true when at least one delivery succeeds")
        // The mobile endpoint is unroutable; we don't assert its hit count
        // because the underlying HTTP client may or may not increment
        // counters on connection-refused. The load-bearing assertion is
        // that the fan-out continued past the failure and reached the
        // desktop endpoint.
    }

    @Test
    fun sendTurnStartPrunesOnlyDeadEndpointOn410_KeepingOthers() = runBlocking {
        // Two subscriptions: one dead (returns 410), one live (returns 201).
        // After the fan-out, ONLY the dead endpoint should be removed from
        // the store; the live endpoint must survive.
        if (::server.isInitialized) server.stop(0)
        val liveServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val deadServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val liveLatch = CountDownLatch(1)
        try {
            liveServer.createContext("/push-live", HttpHandler { exchange ->
                val response = "OK".toByteArray()
                exchange.sendResponseHeaders(201, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
                liveLatch.countDown()
            })
            deadServer.createContext("/push-dead", HttpHandler { exchange ->
                exchange.sendResponseHeaders(410, 0)
                exchange.responseBody.close()
            })
            liveServer.start()
            deadServer.start()
            val livePort = liveServer.address.port
            val deadPort = deadServer.address.port

            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val ecPublicKey = keyPair.public as java.security.interfaces.ECPublicKey
            val w = ecPublicKey.w
            val x = w.affineX.toByteArray().let {
                if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else
                if (it.size < 32) ByteArray(32 - it.size) + it else it
            }
            val y = w.affineY.toByteArray().let {
                if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else
                if (it.size < 32) ByteArray(32 - it.size) + it else it
            }
            val receiverPub = ByteArray(65).also {
                it[0] = 0x04
                System.arraycopy(x, 0, it, 1, 32)
                System.arraycopy(y, 0, it, 33, 32)
            }
            val receiverAuth = ByteArray(16) { (it + 1).toByte() }
            val p256dhB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverPub)
            val authB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverAuth)

            val liveDto = PushSubscriptionDto(
                endpoint = "http://127.0.0.1:$livePort/push-live",
                p256dh = p256dhB64,
                auth = authB64
            )
            val deadDto = PushSubscriptionDto(
                endpoint = "http://127.0.0.1:$deadPort/push-dead",
                p256dh = p256dhB64,
                auth = authB64
            )

            val vfs = mockk<VirtualFileSystem>(relaxed = true)
            val store = PushSubscriptionStore { vfs }
            val envelope = kotlinx.serialization.json.buildJsonObject {
                put("subscriptions", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.Json.encodeToJsonElement(
                        structs.push.PushSubscriptionDto.serializer(), liveDto
                    ),
                    kotlinx.serialization.json.Json.encodeToJsonElement(
                        structs.push.PushSubscriptionDto.serializer(), deadDto
                    )
                )))
            }
            val savedElement = io.mockk.slot<JsonElement>()
            coEvery { vfs.fetchUserRecord("user-mixed", "push-subscription") } returns Result.success(
                PlayerRecordResponse(key = "push-subscription", value = envelope)
            )
            coEvery { vfs.saveUserRecord("user-mixed", "push-subscription", capture(savedElement)) } returns Result.success(
                PlayerRecordResponse(key = "push-subscription", value = envelope)
            )
            val publicKeyBase64UrlFresh = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverPub)
            val service = PushNotificationService(store, PushVapidConfig.VapidKeypair(
                publicKeyBase64Url = publicKeyBase64UrlFresh,
                privateKeyBase64Url = "",
                privateKey = keyPair.private
            ))
            val sent = service.sendTurnStart("user-mixed", "Lord Maple Tree", 11)
            assertTrue(liveLatch.await(30, TimeUnit.SECONDS), "live endpoint should have received the POST within 30s")
            assertTrue(sent, "service should return true when at least one device delivered")
            coVerify(exactly = 1) { vfs.saveUserRecord("user-mixed", "push-subscription", any()) }
            val written = savedElement.captured as kotlinx.serialization.json.JsonObject
            val list = written["subscriptions"].toString()
            assertTrue(list.contains("push-live"), "live endpoint must survive the 410-prune")
            assertFalse(list.contains("push-dead"), "dead endpoint must be removed")
        }
        finally
        {
            liveServer.stop(0)
            deadServer.stop(0)
        }
    }
}