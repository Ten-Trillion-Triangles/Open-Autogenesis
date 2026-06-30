package org.ttt.autogenesis.server.vfs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class LocalVirtualFileSystemTest {
    private lateinit var tempDir: Path
    private lateinit var vfs: VirtualFileSystem

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("vfs-test")
        vfs = VirtualFileSystemFactory.create(listOf("--mode=local", "--vfs-local-dir=${tempDir.toAbsolutePath()}"))
    }

    @AfterTest
    fun teardown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testGameRecordSaveAndFetch() = runBlocking {
        val key = "test-game-record"
        val payload = JsonPrimitive("test-data")

        val saveResult = vfs.saveGameRecord(key, payload)
        if (saveResult.isFailure) {
            println("Save Game Record failed: ${saveResult.exceptionOrNull()?.message}")
            saveResult.exceptionOrNull()?.printStackTrace()
        }
        assertTrue(saveResult.isSuccess)

        val fetchResult = vfs.fetchGameRecord(key)
        assertTrue(fetchResult.isSuccess)
        assertEquals(payload, fetchResult.getOrThrow().value)
    }

    @Test
    fun testUserRecordSaveAndFetch() = runBlocking {
        val userId = "test-user-id"
        val key = "test-user-record"
        val payload = JsonPrimitive("test-data")

        val saveResult = vfs.saveUserRecord(userId, key, payload)
        if (saveResult.isFailure) {
            println("Save User Record failed: ${saveResult.exceptionOrNull()?.message}")
            saveResult.exceptionOrNull()?.printStackTrace()
        }
        assertTrue(saveResult.isSuccess)

        val fetchResult = vfs.fetchUserRecord(userId, key)
        assertTrue(fetchResult.isSuccess)
        assertEquals(payload, fetchResult.getOrThrow().value)
    }

    @Test
    fun testUserRecordSaveFromJsonStringAndFetch() = runBlocking {
        val userId = "test-user-id-2"
        val key = "test-user-record-json"
        val jsonPayload = "\"test-json-data\""
        val expectedPayload = JsonPrimitive("test-json-data")

        val saveResult = vfs.saveUserRecordFromJsonString(userId, key, jsonPayload)
        if (saveResult.isFailure) {
            println("Save User Record JSON failed: ${saveResult.exceptionOrNull()?.message}")
            saveResult.exceptionOrNull()?.printStackTrace()
        }
        assertTrue(saveResult.isSuccess)

        val fetchResult = vfs.fetchUserRecord(userId, key)
        assertTrue(fetchResult.isSuccess)
        assertEquals(expectedPayload, fetchResult.getOrThrow().value)
    }

    @Test
    fun testNonExistentRecordFetch() = runBlocking {
        val fetchResult = vfs.fetchGameRecord("non-existent-key")
        assertTrue(fetchResult.isFailure)
    }
}
