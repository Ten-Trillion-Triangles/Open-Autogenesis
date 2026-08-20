package org.ttt.autogenesis.audiotrackseditor

import kotlinx.browser.document

/**
 * Entry point for the Audio Tracks Editor browser page.
 *
 * Initializes the event handlers and triggers the first render. The
 * placeholder text from Phase 1 is no longer used; the top bar, tab strip,
 * error banner, and track list are rendered immediately by
 * [EventHandlers.initialize].
 */
fun main()
{
    EventHandlers.initialize()
}