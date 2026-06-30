package org.ttt.autogenesis.audiotrackseditor

import kotlin.js.JsName
import kotlinx.browser.document
import kotlinx.serialization.SerializationException
import org.khronos.webgl.Uint8Array
import org.ttt.autogenesis.audio.AudioObject
import org.ttt.autogenesis.audiotrackseditor.audio.AudioPreviewEngine
import org.ttt.autogenesis.audiotrackseditor.audio.AudioTrackPreviewPlayer
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLElement
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File
import org.w3c.files.FileReader
import structs.audio.AudioTracks

/**
 * Application controller for the Audio Tracks Editor browser page. Owns the
 * single mutable [EditorState] and re-renders the top-level containers on
 * every state change. UI events are delivered by the [Render] functions as
 * lambdas in a [Callbacks] bag.
 */
object EventHandlers
{
    private var state: EditorState = EditorState()
    private var loadFilePicker: HTMLInputElement? = null
    private var audioFilePicker: HTMLInputElement? = null
    /**
     * Pending file-pick callbacks, keyed by trackId. When the audio file
     * picker change event fires, the most-recent callback in this map is
     * invoked with (success, errorMessage). The map is keyed by trackId
     * because the picker is a single shared input; only one modal can be
     * picking at a time, so the map has at most one entry in practice.
     */
    private val pendingAudioCallbacks: MutableMap<String, (Boolean, String?) -> Unit> = mutableMapOf()
    /**
     * TrackId of the modal currently driving an audio file pick. Set by
     * [onPreviewLoadAudioDispatch], read by [handleAudioPickerChange].
     */
    private var pendingAudioTrackId: String? = null
    /**
     * Tracks whether [initialize] has already run, so that re-entry (HMR,
     * double-mount, accidental re-call) does not install duplicate global
     * click handlers or create duplicate hidden file pickers.
     */
    private var initialized: Boolean = false
    /**
     * The [ModalState] whose form DOM is currently mounted. Used by
     * [rebuildAll] to decide whether the modal needs to be replaced. The
     * form is left in place across other re-renders so the user's in-flight
     * input is not destroyed by a re-render triggered by a different action
     * (e.g., loading the sample data while a draft is open).
     */
    private var lastRenderedModal: ModalState? = null

    /**
     * Wire up the page. Called once from [main]. Triggers the initial
     * render of every top-level container.
     */
    fun initialize()
    {
        if (initialized) return
        initialized = true
        val picker = kotlinx.browser.document.createElement("input") as HTMLInputElement
        picker.type = "file"
        picker.accept = ".json,application/json"
        picker.setAttribute("style", "display: none;")
        picker.setAttribute("data-testid", "load-file-picker-hidden")
        picker.addEventListener("change", { handleLoadPickerChange() })
        kotlinx.browser.document.body?.appendChild(picker)
        loadFilePicker = picker

        // Audio file picker (parallel to the JSON picker above).
        val audioPicker = kotlinx.browser.document.createElement("input") as HTMLInputElement
        audioPicker.type = "file"
        audioPicker.accept = "audio/*"
        audioPicker.setAttribute("style", "display: none;")
        audioPicker.setAttribute("data-testid", "audio-file-picker-hidden")
        audioPicker.addEventListener("change", { handleAudioPickerChange() })
        kotlinx.browser.document.body?.appendChild(audioPicker)
        audioFilePicker = audioPicker

        installGlobalClickHandler()
        rebuildAll(clearError = true)
        // Test-only hook: expose the singleton on window so the e2e suite can
        // call [initialize] a second time to verify idempotency. Harmless in
        // production; downstream code never depends on it.
        kotlinx.browser.window.asDynamic().__editor = this
    }

    /**
     * Install a single `document`-level click handler that dispatches to
     * state-mutating actions based on the closest `[data-action]` element.
     *
     * This is the authoritative event source. Per-element listeners set up
     * by [Render] are kept as a backup, but the Kotlin/JS compiler
     * sometimes optimizes per-element lambdas into no-ops in production
     * builds, which is why this global delegation pattern is used.
     */
    private fun installGlobalClickHandler()
    {
        document.addEventListener("click", { event ->
            val target = event.target as? HTMLElement ?: return@addEventListener
            val actionEl = target.closest("[data-action]") as? HTMLElement ?: return@addEventListener
            val action = actionEl.getAttribute("data-action") ?: return@addEventListener
            val arg = actionEl.getAttribute("data-arg")
            when (action)
            {
                "select-category" -> if (arg != null) onSelectCategoryDispatch(arg)
                "new-track" -> onNewTrackDispatch()
                "edit-track" -> if (arg != null) onEditTrackDispatch(arg)
                "delete-track" -> if (arg != null) onDeleteTrackDispatch(arg)
                "load-sample" -> onLoadSampleDispatch()
                "save" -> saveToFile()
                "load" -> triggerLoadPicker()
                "modal-save" -> saveModalDraft()
                "modal-cancel" -> onCloseModal()
                "dismiss-error" -> onClearError()
                "preview-play" -> if (arg != null) onPreviewPlayDispatch(arg)
                "preview-stop" -> if (arg != null) onPreviewStopDispatch(arg)
                "preview-load-audio" -> if (arg != null) onPreviewLoadAudioDispatch(arg)
                "preview-clear-audio" -> if (arg != null) onPreviewClearAudioDispatch(arg)
                "preview-play-all-category" -> onPreviewPlayAllCategoryDispatch()
                "preview-stop-all-category" -> onPreviewStopAllDispatch()
                "preview-play-all-global" -> onPreviewPlayAllGlobalDispatch()
                "preview-stop-all-global" -> onPreviewStopAllDispatch()
            }
        })
    }

    /**
     * Dispatch handler for the `select-category` data-action. Looks up the
     * [Category] enum value from the data-arg attribute and updates state.
     *
     * @param name The enum constant name (e.g., "DRONE", "MELODY")
     */
    private fun onSelectCategoryDispatch(name: String)
    {
        val cat = runCatching { Category.valueOf(name) }.getOrNull() ?: return
        state = state.selectCategory(cat)
        rebuildAll(clearError = true)
    }

    /**
     * Dispatch handler for the `new-track` data-action. Opens a fresh
     * "new track" modal.
     */
    private fun onNewTrackDispatch()
    {
        state = state.openNewTrackModal()
        rebuildAll(clearError = true)
    }

    /**
     * Dispatch handler for the `edit-track` data-action.
     *
     * @param id The track id to edit
     */
    private fun onEditTrackDispatch(id: String)
    {
        state = state.openEditTrackModal(id)
        rebuildAll(clearError = true)
    }

    /**
     * Dispatch handler for the `delete-track` data-action. Confirms with
     * the user via the browser's native confirm dialog before deleting.
     *
     * @param id The track id to delete
     */
    private fun onDeleteTrackDispatch(id: String)
    {
        if (js("confirm('Delete this track?')").unsafeCast<Boolean>())
        {
            // Stop any active preview and detach the loaded buffer for the
            // track being deleted, so the AudioPreviewEngine cache does
            // not leak AudioBuffers for tracks that no longer exist.
            AudioPreviewEngine.detachBuffer(id)
            state = state.deleteTrack(id)
            rebuildAll(clearError = true)
        }
    }

    /**
     * Dispatch handler for the `load-sample` data-action. Replaces the
     * current state with the built-in sample dataset.
     */
    private fun onLoadSampleDispatch()
    {
        // Preserve the in-flight modal so the user does not lose their draft
        // when clicking "load sample data" while the modal is open.
        state = state.copy(tracks = sampleData(), dirty = true)
        rebuildAll(clearError = true)
    }

    /**
     * Dispatch handler for the `dismiss-error` data-action. Clears the
     * last-error banner.
     */
    private fun onClearError()
    {
        state = state.setError(null)
        rebuildAll()
    }

    /**
     * Dispatch handler for the `modal-cancel` data-action. Closes the
     * currently open modal without saving.
     */
    private fun onCloseModal()
    {
        // For ModalState.New, the draft id is lost when the modal closes,
        // so any buffer attached to that id is orphaned. Detach it to keep
        // the buffer cache clean. For ModalState.Edit, the track id is real
        // and the buffer must survive so the user can re-open the modal.
        val prev = state.modalState
        if (prev is ModalState.New) {
            AudioPreviewEngine.detachBuffer(prev.trackId)
        }
        state = state.closeModal()
        rebuildAll(clearError = true)
    }

    /**
     * Dispatch handler for the `preview-play` data-action. The [arg] is the
     * trackId the play button refers to (set as data-arg on the modal
     * Play button). Starts a single-track preview using the current draft
     * form values.
     */
    private fun onPreviewPlayDispatch(trackId: String)
    {
        // If a category/global preview is active, stop it before starting
        // a single-track preview.
        if (AudioPreviewEngine.previewMode != AudioPreviewEngine.PreviewMode.IDLE) {
            AudioPreviewEngine.stopSideBySide()
        }
        // Read the LATEST form values (not state) so the engine plays
        // with what the user sees in the modal, not the previously-saved
        // values. If the modal is closed, fall back to the state lookup.
        val draft = if (state.modalState != null) parseForm().copy(id = trackId) else currentDraftTrack()
        val ok = AudioPreviewEngine.startPreview(trackId, draft)
        if (!ok) {
            state = state.setError("Cannot preview: no audio loaded for this track")
        }
        // Do NOT force the modal to re-render — that would wipe the
        // user's in-flight form edits. Instead, swap the Play/Stop
        // buttons in place. The top-bar / tab-header "Play All" buttons
        // still get a normal rebuildAll pass.
        updatePreviewControlsInPlace(trackId)
        rebuildAll(clearError = true)
    }

    /**
     * Dispatch handler for the `preview-stop` data-action. Stops the
     * preview for the given trackId.
     */
    private fun onPreviewStopDispatch(trackId: String)
    {
        AudioPreviewEngine.stopPreview(trackId)
        // Do NOT force the modal to re-render (would wipe in-flight
        // edits). Swap the Play/Stop buttons in place.
        updatePreviewControlsInPlace(trackId)
        rebuildAll(clearError = true)
    }

    /**
     * Dispatch handler for the `preview-load-audio` data-action. Opens
     * the hidden audio file picker, scoped to the given trackId.
     */
    private fun onPreviewLoadAudioDispatch(trackId: String)
    {
        // Lazily create the AudioContext on the first user gesture.
        AudioPreviewEngine.initContext()
        // Store the target trackId so the change handler knows what to
        // attach the buffer to. We use a single private field because
        // the audio file picker is a single shared input element.
        pendingAudioTrackId = trackId
        // Reset the picker value so picking the same file twice still
        // fires a change event.
        audioFilePicker?.value = ""
        audioFilePicker?.click()
    }

    /**
     * Dispatch handler for the `preview-clear-audio` data-action. Detaches
     * the buffer (and stops any active preview) for the given trackId.
     */
    private fun onPreviewClearAudioDispatch(trackId: String)
    {
        AudioPreviewEngine.detachBuffer(trackId)
        // Do NOT force the modal to re-render. Just update the
        // Play/Clear button states in place.
        updatePreviewControlsInPlace(trackId)
        rebuildAll(clearError = true)
    }

    /**
     * Dispatch handler for the `preview-play-all-category` data-action.
     * Starts a side-by-side preview of every loaded track in the current
     * category.
     */
    private fun onPreviewPlayAllCategoryDispatch()
    {
        val tracks = tracksForCategory(state.tracks, state.selectedCategory)
        AudioPreviewEngine.startCategoryPreview(tracks)
        // In-place update: any modal that happens to be open at the time
        // keeps its form values; the Play/Stop button reflects the new
        // engine state. Top-bar / tab-header text updates still flow
        // through rebuildAll.
        updatePreviewControlsInPlaceAnyModal()
        rebuildAll(clearError = true)
    }

    /**
     * Dispatch handler for the `preview-play-all-global` data-action.
     * Starts a side-by-side preview of every loaded track across all
     * 8 categories.
     */
    private fun onPreviewPlayAllGlobalDispatch()
    {
        val all = state.tracks
        val combined: List<AudioObject> = listOf(
            all.drone, all.melody, all.rhythm, all.harmony,
            all.menu, all.start, all.nemesis, all.end
        ).flatten()
        AudioPreviewEngine.startGlobalPreview(combined)
        updatePreviewControlsInPlaceAnyModal()
        rebuildAll(clearError = true)
    }

    /**
     * Dispatch handler for both `preview-stop-all-category` and
     * `preview-stop-all-global`. Stops every active preview player.
     */
    private fun onPreviewStopAllDispatch()
    {
        AudioPreviewEngine.stopAll()
        updatePreviewControlsInPlaceAnyModal()
        rebuildAll(clearError = true)
    }

    /**
     * Read-only accessor for the current state, primarily for tests and
     * debugging.
     *
     * @return The current [EditorState]
     */
    fun getState(): EditorState = state

    /**
     * Replace the contents of every top-level container in the page with a
     * fresh render. Called after every state change. The replacement is
     * done via [HTMLElement.outerHTML] which is intentionally simple — no
     * diffing, no virtual DOM.
     *
     * @param clearError When `true`, clear any sticky [EditorState.lastError]
     *   before rendering. User-initiated actions that succeed should pass
     *   `true` so a previous error does not remain visible across an
     *   unrelated successful action. Error-setting paths and the explicit
     *   "Dismiss" button pass `false` (the default).
     */
    private fun rebuildAll(clearError: Boolean = false)
    {
        if (clearError && state.lastError != null)
        {
            state = state.setError(null)
        }
        val cb = callbacks()
        replace("top-bar-container", Render.renderTopBar(state, cb))
        replace("tab-strip-container", Render.renderTabStrip(state, cb))
        replace("error-banner-container", Render.renderErrorBanner(state, cb))
        replace("list-container", Render.renderTrackList(state, cb))

        // The modal lives outside the other containers as a sibling of #app.
        // Only re-render the modal when the modalState identity changes (i.e.,
        // a modal opened, closed, or switched between edit/new). This keeps
        // the user's in-flight form input intact across re-renders triggered
        // by other actions (load sample, tab switch, error dismiss, etc.).
        val mountedModal = state.modalState
        if (mountedModal == null)
        {
            if (lastRenderedModal != null)
            {
                // Modal was just closed; remove it from the DOM
                document.getElementById("edit-modal")?.remove()
                lastRenderedModal = null
            }
        }
        else if (mountedModal != lastRenderedModal)
        {
            // New or different modal opened; render it
            val draft = currentDraftTrack()
            val newModal = Render.renderModal(state, draft, cb)
            val existing = document.getElementById("edit-modal")
            if (existing != null)
            {
                existing.parentNode?.replaceChild(newModal, existing)
            }
            else
            {
                document.getElementById("app")?.parentNode?.appendChild(newModal)
            }
            lastRenderedModal = mountedModal
        }
        // Visibility: use the proper <dialog> API so the dialog enters the
        // top layer (backdrop, focus trap, ESC-to-close). Falling back to the
        // `open` attribute handles environments where the modal API is not
        // available (very old browsers).
        val installed = document.getElementById("edit-modal") as? HTMLDialogElement
        if (installed != null)
        {
            val shouldShow = state.modalState != null
            val isOpen = installed.hasAttribute("open")
            if (shouldShow && !isOpen)
            {
                try { installed.showModal() } catch (_: Throwable) { installed.setAttribute("open", "") }
            }
            else if (!shouldShow && isOpen)
            {
                installed.close()
            }
        }
    }

    /**
     * Replace the element with [id] by inserting [newEl] in its place.
     * The new element is given the same [id] as the container it replaces
     * so the next render can still find it via `getElementById`. If no
     * element with [id] exists (e.g., a fresh page where the HTML
     * container has not yet been rendered, or a defensive fallback when
     * another component has removed the container), the new element is
     * appended to the `#app` shell as a recovery path.
     *
     * @param id DOM id of the container to replace
     * @param newEl Freshly rendered element whose HTML will be swapped in
     */
    private fun replace(id: String, newEl: HTMLElement)
    {
        newEl.id = id
        val existing = document.getElementById(id)
        if (existing == null)
        {
            document.getElementById("app")?.appendChild(newEl)
            return
        }
        existing.parentNode?.replaceChild(newEl, existing)
    }

    /**
     * Update the preview controls (Play/Stop/Clear buttons, status line)
     * for the modal currently mounted at `#edit-modal` IN PLACE, without
     * re-rendering the modal. The form inputs and any in-flight edits
     * the user has typed are preserved by this approach. Called by
     * every preview-related handler instead of forcing a full re-render
     * via `lastRenderedModal = null` (which used to wipe the form).
     *
     * @param trackId The trackId the modal was opened for; passed through
     *   to the engine so the controls reflect that track's state.
     */
    private fun updatePreviewControlsInPlace(trackId: String)
    {
        val modal = document.getElementById("edit-modal") as? HTMLElement ?: return
        val playBtn = modal.querySelector("[data-testid=\"preview-play\"]") as? HTMLButtonElement
        val stopBtn = modal.querySelector("[data-testid=\"preview-stop\"]") as? HTMLButtonElement
        val clearBtn = modal.querySelector("[data-testid=\"preview-clear-audio\"]") as? HTMLButtonElement
        val status = modal.querySelector("[data-testid=\"preview-status\"]") as? HTMLElement

        val buffer = AudioPreviewEngine.getLoadedBuffer(trackId)
        val fileName = AudioPreviewEngine.getLoadedFileName(trackId)
        val isActive = AudioPreviewEngine.isPlaying(trackId)

        playBtn?.let {
            it.disabled = buffer == null
            it.setAttribute("style", if (isActive) "display: none;" else "")
        }
        stopBtn?.let {
            it.setAttribute("style", if (isActive) "" else "display: none;")
        }
        clearBtn?.let {
            it.setAttribute("style", if (buffer == null) "display: none;" else "")
        }
        status?.let {
            it.textContent = if (buffer == null) {
                "No file loaded"
            } else {
                val dur = formatFloat2(buffer.duration)
                "Loaded: ${fileName ?: "audio"} (${dur}s, ${buffer.numberOfChannels}ch)"
            }
        }
    }

    /**
     * Like [updatePreviewControlsInPlace] but for the case where the
     * modal's trackId is unknown (e.g. Play-All handlers where the
     * modal's current trackId may or may not be one of the playing
     * tracks). Reads the modal's `data-modal-track-id` attribute.
     */
    private fun updatePreviewControlsInPlaceAnyModal()
    {
        val modal = document.getElementById("edit-modal") as? HTMLElement ?: return
        val trackId = modal.getAttribute("data-modal-track-id") ?: return
        updatePreviewControlsInPlace(trackId)
    }

    /**
     * Build the [Callbacks] bag whose lambdas mutate [state] and re-render.
     * Constructed fresh on every [rebuildAll] so the latest state is
     * captured by every closure.
     */
    private fun callbacks(): Callbacks = Callbacks(
        onSelectCategory = { c ->
            state = state.selectCategory(c)
            rebuildAll(clearError = true)
        },
        onNewTrack = {
            state = state.openNewTrackModal()
            rebuildAll(clearError = true)
        },
        onEditTrack = { id ->
            state = state.openEditTrackModal(id)
            rebuildAll(clearError = true)
        },
        onDeleteTrack = { id ->
            val confirmed = js("confirm('Delete this track?')").unsafeCast<Boolean>()
            if (confirmed)
            {
                AudioPreviewEngine.detachBuffer(id)
                state = state.deleteTrack(id)
                rebuildAll(clearError = true)
            }
        },
        onLoadSampleData = {
            // Preserve the in-flight modal so a click on the top-bar
            // "load sample data" link does not wipe a draft the user is
            // editing in the modal.
            state = state.copy(tracks = sampleData(), dirty = true)
            rebuildAll(clearError = true)
        },
        onSave = { saveToFile() },
        onLoad = { triggerLoadPicker() },
        onClearError = {
            state = state.setError(null)
            rebuildAll()
        },
        onCloseModal = {
            state = state.closeModal()
            rebuildAll(clearError = true)
        },
        onModalSave = {
            saveModalDraft()
        }
    )

    /**
     * Serialize the current [state.tracks] to JSON, wrap them in a Blob, and
     * trigger a browser download as `audioTracks.json`. Clears the [dirty]
     * flag on success. On failure, surfaces the error via the error banner.
     */
    private fun saveToFile()
    {
        try
        {
            val json = AudioTracksFileIO.encode(state.tracks)
            val bytes = Uint8Array(json.length)
            for (i in 0 until json.length)
            {
                bytes.asDynamic()[i] = json[i].code
            }
            val blob = Blob(arrayOf(bytes), BlobPropertyBag(type = "application/json"))
            val url = org.w3c.dom.url.URL.createObjectURL(blob)
            val a = kotlinx.browser.document.createElement("a") as HTMLAnchorElement
            a.href = url
            a.download = "audioTracks.json"
            kotlinx.browser.document.body?.appendChild(a)
            a.click()
            kotlinx.browser.document.body?.removeChild(a)
            org.w3c.dom.url.URL.revokeObjectURL(url)
            state = state.markClean()
            rebuildAll(clearError = true)
        }
        catch (e: Exception)
        {
            state = state.setError("Could not save: ${e.message}")
            rebuildAll()
        }
    }

    /**
     * Open the hidden file input created in [initialize] so the user can
     * pick a JSON file to load. Resets the value first so picking the same
     * file twice still fires a change event.
     */
    private fun triggerLoadPicker()
    {
        loadFilePicker?.value = ""
        loadFilePicker?.click()
    }

    /**
     * Read the file selected by the hidden file input, decode it via
     * [AudioTracksFileIO.decode], and replace the current [state.tracks]
     * with the parsed result. The active category and any open modal are
     * preserved; the [dirty] flag is cleared since the loaded file
     * represents a fresh persisted snapshot. Errors are surfaced via the
     * error banner.
     */
    private fun handleLoadPickerChange()
    {
        val picker = loadFilePicker ?: return
        val file: File = picker.files?.item(0) ?: return
        val reader = FileReader()
        reader.onload = {
            val text = reader.result.unsafeCast<String>()
            try
            {
                val parsed = AudioTracksFileIO.decode(text)
                state = state.copy(
                    tracks = parsed,
                    dirty = false,
                    lastError = null
                )
                rebuildAll(clearError = true)
            }
            catch (e: SerializationException)
            {
                state = state.setError("Could not parse file: ${e.message ?: "invalid format"}")
                rebuildAll()
            }
            catch (e: Exception)
            {
                state = state.setError("Could not load file: ${e.message ?: e::class.simpleName}")
                rebuildAll()
            }
        }
        reader.onerror = {
            state = state.setError("Failed to read file")
            rebuildAll()
        }
        reader.readAsText(file)
    }

    /**
     * Read the file selected by the hidden audio file input, decode it via
     * [AudioPreviewEngine.attachFile], and attach the resulting buffer to
     * the pending trackId. Errors are surfaced via the error banner.
     */
    private fun handleAudioPickerChange()
    {
        val picker = audioFilePicker ?: return
        val file: File = picker.files?.item(0) ?: return
        val trackId = pendingAudioTrackId ?: return
        pendingAudioTrackId = null
        // Attach the file (async via FileReader -> decodeAudioData). When
        // the callback fires, update the preview controls IN PLACE rather
        // than re-rendering the whole modal — that would wipe the user's
        // in-flight form edits (e.g. they might have started typing a new
        // resourceName while the file was decoding).
        AudioPreviewEngine.attachFile(trackId, file) { success, errorMsg ->
            if (!success) {
                state = state.setError(errorMsg ?: "Could not decode audio file")
            }
            updatePreviewControlsInPlace(trackId)
            rebuildAll(clearError = success)
        }
    }

    /**
     * All tracks across every category in [tracks] flattened into one list.
     * Used by the global Play All handler to enumerate every track in the
     * editor.
     */
    private fun allTracks(tracks: AudioTracks): List<AudioObject> =
        listOf(
            tracks.drone, tracks.melody, tracks.rhythm, tracks.harmony,
            tracks.menu, tracks.start, tracks.nemesis, tracks.end
        ).flatten()

    /**
     * Tracks in the currently-selected category. Used by the per-tab
     * Play All handler.
     */
    private fun tracksForCategory(tracks: AudioTracks, category: Category): List<AudioObject> =
        when (category) {
            Category.DRONE -> tracks.drone
            Category.MELODY -> tracks.melody
            Category.RHYTHM -> tracks.rhythm
            Category.HARMONY -> tracks.harmony
            Category.MENU -> tracks.menu
            Category.START -> tracks.start
            Category.NEMESIS -> tracks.nemesis
            Category.END -> tracks.end
        }

    /**
     * Test-only inspection hook: returns the AudioContext state string.
     */
    @JsName("getAudioContextState")
    fun getAudioContextState(): String = AudioPreviewEngine.getAudioContextState()

    /**
     * Test-only inspection hook: returns the list of trackIds with loaded
     * audio buffers.
     */
    @JsName("getLoadedAudioKeys")
    fun getLoadedAudioKeys(): List<String> = AudioPreviewEngine.getLoadedAudioKeys()

    /**
     * Test-only inspection hook: returns the buffer duration in seconds for
     * a given trackId, or -1.0 if no buffer is loaded.
     */
    @JsName("getLoadedAudioDuration")
    fun getLoadedAudioDuration(trackId: String): Double =
        AudioPreviewEngine.getLoadedBuffer(trackId)?.duration ?: -1.0

    /**
     * Test-only inspection hook: returns the buffer channel count for a
     * given trackId, or 0 if no buffer is loaded.
     */
    @JsName("getLoadedAudioChannels")
    fun getLoadedAudioChannels(trackId: String): Int =
        AudioPreviewEngine.getLoadedBuffer(trackId)?.numberOfChannels ?: 0

    /**
     * Test-only inspection hook: returns the file name of the loaded
     * audio for a given trackId, or null.
     */
    @JsName("getLoadedAudioFileName")
    fun getLoadedAudioFileName(trackId: String): String? =
        AudioPreviewEngine.getLoadedFileName(trackId)

    /**
     * Test-only inspection hook: returns the count of currently active
     * preview players.
     */
    @JsName("getActivePreviewPlayerCount")
    fun getActivePreviewPlayerCount(): Int = AudioPreviewEngine.getActivePlayerCount()

    /**
     * Test-only inspection hook: returns the snapshot for every active
     * preview player.
     */
    @JsName("getActivePreviewPlayers")
    fun getActivePreviewPlayers(): List<AudioTrackPreviewPlayer.Snapshot> =
        AudioPreviewEngine.getActivePreviewPlayers()

    /**
     * Test-only inspection hook: returns true if a preview is active for
     * the given trackId.
     */
    @JsName("isPreviewPlaying")
    fun isPreviewPlaying(trackId: String): Boolean = AudioPreviewEngine.isPlaying(trackId)

    /**
     * Test-only inspection hook: returns true if any preview is active.
     */
    @JsName("hasActivePreviews")
    fun hasActivePreviews(): Boolean = AudioPreviewEngine.hasActivePreviews()

    /**
     * Test-only inspection hook: returns the current preview mode
     * (IDLE / CATEGORY / GLOBAL).
     */
    @JsName("getPreviewMode")
    fun getPreviewMode(): String = AudioPreviewEngine.previewMode.name

    /**
     * Test-only inspection hook: returns the AudioContext currentTime
     * (or -1.0 if not initialized).
     */
    @JsName("getAudioContextCurrentTime")
    fun getAudioContextCurrentTime(): Double =
        AudioPreviewEngine.getAudioContext()?.currentTime ?: -1.0

    /**
     * Test-only inspection hook: returns the gain value of the active
     * player for the given trackId (or -1.0 if no player).
     */
    @JsName("getActiveGainValue")
    fun getActiveGainValue(trackId: String): Double =
        AudioPreviewEngine.getGainValueFor(trackId)

    @JsName("getActivePanningValue")
    fun getActivePanningValue(trackId: String): Double =
        AudioPreviewEngine.getPanningValueFor(trackId)

    @JsName("getActiveSpeedValue")
    fun getActiveSpeedValue(trackId: String): Double =
        AudioPreviewEngine.getSpeedValueFor(trackId)

    /**
     * Compute the [AudioObject] the modal should be editing. For [ModalState.New]
     * this is a blank draft; for [ModalState.Edit] this is the existing track
     * looked up by id, or a blank draft if the track is missing.
     */
    private fun currentDraftTrack(): AudioObject
    {
        val modal = state.modalState ?: return newDraftTrack("", state.selectedCategory)
        return when (modal)
        {
            is ModalState.Edit -> findTrackById(modal.trackId)
                ?: newDraftTrack(modal.trackId, state.selectedCategory)
            is ModalState.New -> newDraftTrack(modal.trackId, state.selectedCategory)
        }
    }

    /**
     * Build a blank [AudioObject] for use as the modal draft when creating
     * a new track, or as a fallback when an edit modal points at a missing
     * track id.
     *
     * The new track's [AudioObject.channelId] is stamped from [category]
     * (e.g. [Category.MELODY] → "Melody") so tracks land in the right
     * audio bus by default. The user can still override it via the
     * Identity fieldset's "Channel id" text input in
     * [Render.renderIdentityFieldset], and the editor's `ModalState.New`
     * call sites always pass [EditorState.selectedCategory] so a track
     * created in the MELODY tab starts on the Melody channel.
     */
    private fun newDraftTrack(id: String, category: Category): AudioObject = AudioObject(
        id = id,
        resourceName = "",
        channelId = categoryToChannelId(category),
        volume = 1.0f,
        panning = 0.0f,
        speed = 1.0f,
        loop = false,
        startTimeMs = 0L,
        fadeInDurationMs = 0L,
        fadeOutDurationMs = 0L,
        loopWithTail = false
    )

    /**
     * Search every category list for a track with the given id. Includes
     * the four scenario categories (`menu`, `start`, `nemesis`, `end`) in
     * addition to the four layer categories; otherwise opening an Edit
     * modal on a scenario track falls through to a blank draft.
     */
    /**
     * Map a [Category] to the matching audio-channel id string used by
     * the runtime. The mapping is just a capitalization of the enum
     * name (`Category.MELODY` → `"Melody"`), so the editor's tab
     * structure (8 tabs) lines up 1:1 with the audio engine's channel
     * tree (8 child channels of the Music master).
     */
    private fun categoryToChannelId(category: Category): String =
        category.name.lowercase().replaceFirstChar { it.uppercaseChar() }

    private fun findTrackById(id: String): AudioObject?
    {
        val all = state.tracks.drone + state.tracks.melody +
                  state.tracks.rhythm + state.tracks.harmony +
                  state.tracks.menu + state.tracks.start +
                  state.tracks.nemesis + state.tracks.end
        return all.firstOrNull { it.id == id }
    }

    /**
     * Read the modal form, persist it into [state] using the right add or
     * update method, and close the modal. Triggered by the modal's Save
     * button via the [Callbacks.onModalSave] callback.
     */
    private fun saveModalDraft()
    {
        val draft = parseForm()
        val modal = state.modalState ?: return
        state = when (modal)
        {
            is ModalState.New -> state.addTrack(state.selectedCategory, draft.copy(id = modal.trackId))
                .closeModal()
            is ModalState.Edit -> state.updateTrack(modal.trackId, draft.copy(id = modal.trackId))
                .closeModal()
        }
        rebuildAll(clearError = true)
    }

    /**
     * Read the modal form fields from the DOM and project them into an
     * [AudioObject]. Missing numeric fields fall back to the defaults used
     * by [newDraftTrack] so partial forms still produce a valid object.
     *
     * The [AudioObject.channelId] fallback uses [categoryToChannelId] of
     * the active tab rather than a hard-coded "Music" string. The
     * pre-6d1547c6a editor hard-coded "Music" here, which silently
     * downgraded every newly created or edited track onto the master
     * Music bus — losing the per-category routing the engine depends on.
     * The current code always renders the field, so this branch is dead
     * in practice, but keeping it category-aware means a future refactor
     * that elides the render step won't reintroduce the regression.
     */
    private fun parseForm(): AudioObject
    {
        val value = { id: String -> (document.getElementById(id) as? HTMLInputElement)?.value }
        val checked = { id: String -> (document.getElementById(id) as? HTMLInputElement)?.checked ?: false }
        val asLong = { id: String -> value(id)?.takeIf { it.isNotEmpty() }?.toLongOrNull() }
        val asDouble = { id: String -> value(id)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() }
        val asFloat = { id: String -> value(id)?.toFloatOrNull() }

        return AudioObject(
            id = "",
            resourceName = value("f-resourceName") ?: "",
            channelId = value("f-channelId") ?: categoryToChannelId(state.selectedCategory),
            volume = asFloat("f-volume") ?: 1.0f,
            panning = asFloat("f-panning") ?: 0.0f,
            speed = asFloat("f-speed") ?: 1.0f,
            loop = checked("f-loop"),
            startTimeMs = asLong("f-startTimeMs") ?: 0L,
            startFrame = asLong("f-startFrame"),
            startSample = asLong("f-startSample"),
            endTimeMs = asLong("f-endTimeMs"),
            fadeInDurationMs = asLong("f-fadeInDurationMs") ?: 0L,
            fadeOutDurationMs = asLong("f-fadeOutDurationMs") ?: 0L,
            loopStart = asDouble("f-loopStart"),
            loopEnd = asDouble("f-loopEnd"),
            loopWithTail = checked("f-loopWithTail")
        )
    }

    /**
     * Format a float as a 2-decimal string, e.g. 1.5 -> "1.50", 0.123 -> "0.12".
     * [String.format] is not available in Kotlin/JS, so we hand-roll the
     * rounding using [kotlin.math.round]. Used by the preview-controls
     * in-place update to display the loaded buffer's duration.
     */
    private fun formatFloat2(value: Double): String
    {
        val rounded = kotlin.math.round(value * 100.0).toInt()
        val whole = rounded / 100
        val frac = kotlin.math.abs(rounded % 100)
        val fracStr = if (frac < 10) "0$frac" else frac.toString()
        val sign = if (rounded < 0 && whole == 0) "-" else ""
        return "$sign$whole.$fracStr"
    }

    /**
     * Build the in-memory sample dataset used by the "load sample data"
     * link. Two drone pads, two melody lines, a rhythm bed, and a harmony
     * chord cover the full surface of the four category lists with
     * non-trivial values for volume, loop, and speed.
     */
    private fun sampleData(): AudioTracks = AudioTracks(
        drone = mutableListOf(
            AudioObject(
                id = "sample-d-1",
                resourceName = "music.drone.pad",
                channelId = "Drone",
                volume = 0.6f,
                loop = true,
                loopEnd = 16.0,
                loopWithTail = true
            ),
            AudioObject(
                id = "sample-d-2",
                resourceName = "music.drone.sub",
                channelId = "Drone",
                volume = 0.4f,
                loop = true,
                loopEnd = 16.0,
                loopWithTail = true
            )
        ),
        melody = mutableListOf(
            AudioObject(
                id = "sample-m-1",
                resourceName = "music.melody.theme",
                channelId = "Melody",
                volume = 0.8f
            ),
            AudioObject(
                id = "sample-m-2",
                resourceName = "music.melody.counter",
                channelId = "Melody",
                volume = 0.7f,
                speed = 0.95f
            )
        ),
        rhythm = mutableListOf(
            AudioObject(
                id = "sample-r-1",
                resourceName = "music.rhythm.drums",
                channelId = "Rhythm",
                volume = 0.9f
            )
        ),
        harmony = mutableListOf(
            AudioObject(
                id = "sample-h-1",
                resourceName = "music.harmony.chord",
                channelId = "Harmony",
                volume = 0.5f,
                loop = true,
                loopEnd = 8.0,
                loopWithTail = true
            )
        ),
        // Scenario tracks — the four new tabs in the editor. Each
        // corresponds to one of the music-selector rules. Volumes
        // and loop settings are illustrative.
        menu = mutableListOf(
            AudioObject(
                id = "sample-menu-1",
                resourceName = "music.menu.theme",
                channelId = "Menu",
                volume = 0.8f,
                loop = true
            )
        ),
        start = mutableListOf(
            AudioObject(
                id = "sample-start-1",
                resourceName = "music.start.initial",
                channelId = "Start",
                volume = 1.0f,
                loop = true,
                loopWithTail = true
            )
        ),
        nemesis = mutableListOf(
            AudioObject(
                id = "sample-nemesis-1",
                resourceName = "music.nemesis.theme",
                channelId = "Nemesis",
                volume = 0.9f,
                loop = true,
                loopWithTail = true
            )
        ),
        end = mutableListOf(
            AudioObject(
                id = "sample-end-1",
                resourceName = "music.end.terminal",
                channelId = "End",
                volume = 1.0f,
                loop = true,
                loopWithTail = true
            )
        )
    )
}
