@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package org.ttt.autogenesis.kvisionapp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD tests for [ResumeAvailabilityListener] (Phase B of the
 * resume-game-architecture plan).
 *
 * The plan's review flagged that the listener's `mountResumeDialogStub`
 * only logged the notification — it never mounted a real
 * [ui.ResumeOrNewDialog] and the user-facing "Resume saved game?" modal
 * was non-functional. These tests pin the desired behaviour:
 *  - The listener exposes a public hook for setting the onResume /
 *    onNewGame / onCancel callbacks that the dialog should invoke.
 *  - When the dialog's Resume button is clicked, the listener invokes
 *    [ResumeAvailabilityListener.invokeResumeCallback] which routes the
 *    parsed notification back to the menu.
 *  - The callback is null-safe: if the menu hasn't registered one yet,
 *    the listener logs a warning and returns without throwing.
 */
class ResumeAvailabilityListenerTest
{
    private val originalOnResume = ResumeAvailabilityListener.dialogOnResume
    private val originalOnNewGame = ResumeAvailabilityListener.dialogOnNewGame
    private val originalOnCancel = ResumeAvailabilityListener.dialogOnCancel

    @AfterTest
    fun restoreCallbacks()
    {
        ResumeAvailabilityListener.dialogOnResume = originalOnResume
        ResumeAvailabilityListener.dialogOnNewGame = originalOnNewGame
        ResumeAvailabilityListener.dialogOnCancel = originalOnCancel
    }

    @Test
    fun `invokeResumeCallback invokes the onResume callback with the parsed notification`() = runTest {
        var invoked: Boolean = false
        var capturedUserId: String = ""
        var capturedRound: Int = 0

        ResumeAvailabilityListener.dialogOnResume = { payload ->
            invoked = true
            capturedUserId = payload.userId
            capturedRound = payload.worldRound
        }
        ResumeAvailabilityListener.dialogOnNewGame = { error("not expected") }
        ResumeAvailabilityListener.dialogOnCancel = { error("not expected") }

        val payload = buildJsonObject {
            put("userId", "test-user-42")
            put("worldRound", 7)
            put("turnIndex", 0)
            put("hasAi", true)
            put("savedAt", "2026-06-22T18:23:30Z")
        }
        val parsed = Json.decodeFromJsonElement(structs.resume.ResumeAvailabilityNotification.serializer(), payload)

        ResumeAvailabilityListener.invokeResumeCallback(parsed)

        assertTrue(invoked, "onResume callback must be invoked when the dialog's Resume button is clicked")
        assertEquals("test-user-42", capturedUserId, "onResume callback must receive the parsed notification's userId")
        assertEquals(7, capturedRound, "onResume callback must receive the parsed notification's worldRound")
    }

    @Test
    fun `invokeResumeCallback is null-safe when no callback is registered`() = runTest {
        ResumeAvailabilityListener.dialogOnResume = null
        ResumeAvailabilityListener.dialogOnNewGame = null
        ResumeAvailabilityListener.dialogOnCancel = null

        val payload = buildJsonObject {
            put("userId", "test-user-43")
            put("worldRound", 3)
            put("turnIndex", 0)
            put("hasAi", false)
        }
        val parsed = Json.decodeFromJsonElement(structs.resume.ResumeAvailabilityNotification.serializer(), payload)

        // Must not throw — production path swallows the missing callback
        // with a warning log and returns. This is the contract that keeps
        // the listener from crashing the WebSocket frame dispatch if the
        // menu has not yet registered its handler.
        ResumeAvailabilityListener.invokeResumeCallback(parsed)
    }
}