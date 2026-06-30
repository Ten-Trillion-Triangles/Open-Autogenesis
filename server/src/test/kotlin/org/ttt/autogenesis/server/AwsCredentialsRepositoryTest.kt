package org.ttt.autogenesis.server

import accelbyte.cloudsave.GameRecord
import commonGlobals.AwsCredentialStore
import globals.AwsCredentialsRepository
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import structs.accelbyte.cloudsave.AwsCredentialsRecord
import structs.accelbyte.cloudsave.GameRecordAdminResponse

class AwsCredentialsRepositoryTest
{
    private val record = AwsCredentialsRecord("AKIATESTEXAMPLEID", "TESTSECRETKEYEXAMPLE")

    @BeforeTest
    fun setup()
    {
        AwsCredentialStore.clear()
        mockkObject(GameRecord)
    }

    @AfterTest
    fun teardown()
    {
        unmockkObject(GameRecord)
        AwsCredentialStore.clear()
    }

    @Test
    fun `fetch updates store with record response`()
    {
        val response = GameRecordAdminResponse(
            key = AwsCredentialsRepository.recordKey,
            value = record.toJsonElement()
        )
        every { GameRecord.adminFetchRecordDirect(AwsCredentialsRepository.recordKey) } returns Result.success<GameRecordAdminResponse>(response)

        val result = AwsCredentialsRepository.fetch()

        assertNotNull(result.getOrNull())
        assertEquals(record, result.getOrThrow())
        assertEquals(record, AwsCredentialStore.current())
        verify(exactly = 1) { GameRecord.adminFetchRecordDirect(AwsCredentialsRepository.recordKey) }
    }

    @Test
    fun `save replaces record and caches result`()
    {
        val payload = record.toJsonElement()
        val response = GameRecordAdminResponse(
            key = AwsCredentialsRepository.recordKey,
            value = payload
        )
        every { GameRecord.adminReplaceRecordDirect(AwsCredentialsRepository.recordKey, payload) } returns Result.success<GameRecordAdminResponse>(response)

        val result = AwsCredentialsRepository.save(record)

        assertNotNull(result.getOrNull())
        assertEquals(record, result.getOrThrow())
        assertEquals(record, AwsCredentialStore.current())
        verify(exactly = 1) { GameRecord.adminReplaceRecordDirect(AwsCredentialsRepository.recordKey, payload) }
    }
}
