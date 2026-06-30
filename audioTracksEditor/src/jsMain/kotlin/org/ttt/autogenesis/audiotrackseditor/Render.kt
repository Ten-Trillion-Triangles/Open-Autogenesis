package org.ttt.autogenesis.audiotrackseditor

import org.ttt.autogenesis.audiotrackseditor.audio.AudioPreviewEngine

import kotlinx.browser.document
import org.ttt.autogenesis.audio.AudioObject
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Bag of click/input handlers used by the [Render] functions to notify the
 * [EventHandlers] singleton when the user interacts with the UI. Render code
 * is otherwise pure: it only reads [EditorState] and attaches listeners that
 * invoke the matching [Callbacks] field. All actual state mutation happens in
 * [EventHandlers].
 *
 * @property onSelectCategory Fired when a tab in the category strip is clicked
 * @property onNewTrack Fired when the "New Track" button is clicked
 * @property onEditTrack Fired when an Edit button on a track row is clicked
 * @property onDeleteTrack Fired when a Delete button on a track row is clicked
 * @property onLoadSampleData Fired when the "load sample data" link is clicked
 * @property onSave Fired when the Save button in the top bar is clicked
 * @property onLoad Fired when the Load button in the top bar is clicked
 * @property onClearError Fired when the error banner dismiss button is clicked
 * @property onCloseModal Fired when the modal close button is clicked
 * @property onModalSave Fired when the modal Save button is clicked
 */
data class Callbacks(
    val onSelectCategory: (Category) -> Unit,
    val onNewTrack: () -> Unit,
    val onEditTrack: (String) -> Unit,
    val onDeleteTrack: (String) -> Unit,
    val onLoadSampleData: () -> Unit,
    val onSave: () -> Unit,
    val onLoad: () -> Unit,
    val onClearError: () -> Unit,
    val onCloseModal: () -> Unit,
    val onModalSave: () -> Unit
)

/**
 * Pure renderers for the audio tracks editor. Each function takes an
 * [EditorState] (and optionally a draft [AudioObject] for the modal) and
 * returns a freshly constructed DOM element. Renderers never mutate
 * state; they only attach event listeners that delegate back to
 * [EventHandlers] via the supplied [Callbacks].
 */
object Render
{
    /**
     * Build the top bar containing the title, Save/Load buttons, and a
     * "load sample data" link.
     *
     * @param state Current editor state (used to enable/disable Save)
     * @param cb Callbacks invoked by the bar's buttons and link
     * @return A fresh `<div class="top-bar">` element
     */
    fun renderTopBar(state: EditorState, cb: Callbacks): HTMLElement
    {
        val bar = document.createElement("div") as HTMLElement
        bar.className = "top-bar"

        val title = document.createElement("h1") as HTMLElement
        title.className = "top-bar-title"
        title.textContent = "Audio Tracks Editor"
        bar.appendChild(title)

        val actions = document.createElement("div") as HTMLElement
        actions.className = "top-bar-actions"

        val loadSampleLink = document.createElement("button") as HTMLButtonElement
        loadSampleLink.className = "link-button"
        loadSampleLink.setAttribute("data-testid", "load-sample-data-link")
        loadSampleLink.setAttribute("data-action", "load-sample")
        loadSampleLink.textContent = "load sample data"
        actions.appendChild(loadSampleLink)

        val loadBtn = document.createElement("button") as HTMLButtonElement
        loadBtn.className = "btn"
        loadBtn.setAttribute("data-testid", "load-button")
        loadBtn.setAttribute("data-action", "load")
        loadBtn.textContent = "Load"
        actions.appendChild(loadBtn)

        val isGlobalActive = AudioPreviewEngine.previewMode == AudioPreviewEngine.PreviewMode.GLOBAL
        val playAllGlobalBtn = document.createElement("button") as HTMLButtonElement
        playAllGlobalBtn.className = "btn play-all-btn"
        if (isGlobalActive) {
            playAllGlobalBtn.setAttribute("data-testid", "stop-all-global")
            playAllGlobalBtn.setAttribute("data-action", "preview-stop-all-global")
            playAllGlobalBtn.textContent = "■ Stop All (global)"
        } else {
            playAllGlobalBtn.setAttribute("data-testid", "play-all-global")
            playAllGlobalBtn.setAttribute("data-action", "preview-play-all-global")
            playAllGlobalBtn.textContent = "▶ Play All (global)"
        }
        actions.appendChild(playAllGlobalBtn)

        val saveBtn = document.createElement("button") as HTMLButtonElement
        saveBtn.className = "btn btn-primary"
        saveBtn.setAttribute("data-testid", "save-button")
        saveBtn.setAttribute("data-action", "save")
        saveBtn.textContent = "Save"
        saveBtn.disabled = !state.dirty
        actions.appendChild(saveBtn)

        bar.appendChild(actions)
        return bar
    }

    /**
     * Build the four-tab category strip. The tab matching
     * [EditorState.selectedCategory] gets the `tab-selected` class and a
     * blue bottom border.
     *
     * @param state Current editor state (used to mark the selected tab)
     * @param cb Callbacks invoked when a tab is clicked
     * @return A fresh `<div class="tab-strip">` element
     */
    fun renderTabStrip(state: EditorState, cb: Callbacks): HTMLElement
    {
        val strip = document.createElement("div") as HTMLElement
        strip.className = "tab-strip"

        Category.values().forEach { category ->
            val tab = document.createElement("button") as HTMLButtonElement
            tab.className = if (category == state.selectedCategory) "tab tab-selected" else "tab"
            tab.setAttribute("data-testid", "tab-$category")
            tab.setAttribute("data-action", "select-category")
            tab.setAttribute("data-arg", category.name)
            tab.textContent = category.name
            strip.appendChild(tab)
        }

        return strip
    }

    /**
     * Build the red error banner shown when [EditorState.lastError] is
     * non-null. Returns an empty placeholder element when there is no
     * error so the container always has a defined size.
     *
     * @param state Current editor state
     * @param cb Callbacks invoked when the dismiss button is clicked
     * @return A fresh `<div class="error-banner">` or placeholder
     */
    fun renderErrorBanner(state: EditorState, cb: Callbacks): HTMLElement
    {
        val banner = document.createElement("div") as HTMLElement
        val error = state.lastError
        if (error == null)
        {
            banner.id = "error-banner-placeholder"
            banner.style.display = "none"
            return banner
        }

        banner.className = "error-banner"
        val text = document.createElement("span") as HTMLElement
        text.className = "error-banner-text"
        text.textContent = error
        banner.appendChild(text)

        val dismiss = document.createElement("button") as HTMLButtonElement
        dismiss.className = "error-banner-dismiss"
        dismiss.setAttribute("data-testid", "error-dismiss")
        dismiss.setAttribute("data-action", "dismiss-error")
        dismiss.textContent = "Dismiss"
        banner.appendChild(dismiss)

        return banner
    }

    /**
     * Build the track list for the currently selected category, including
     * the "New Track" button and a count display.
     *
     * @param state Current editor state
     * @param cb Callbacks invoked by the New / Edit / Delete buttons
     * @return A fresh `<div class="track-list">` element
     */
    fun renderTrackList(state: EditorState, cb: Callbacks): HTMLElement
    {
        val container = document.createElement("div") as HTMLElement
        container.className = "track-list"

        val header = document.createElement("div") as HTMLElement
        header.className = "track-list-header"

        val title = document.createElement("h2") as HTMLElement
        title.className = "track-list-title"
        val tracks = tracksForCategory(state.tracks, state.selectedCategory)
        title.textContent = "${state.selectedCategory.name} (${tracks.size})"
        header.appendChild(title)

        val newBtn = document.createElement("button") as HTMLButtonElement
        newBtn.className = "btn btn-primary"
        newBtn.setAttribute("data-testid", "new-track-button")
        newBtn.setAttribute("data-action", "new-track")
        newBtn.textContent = "+ New Track"
        header.appendChild(newBtn)

        // Per-tab side-by-side Play All / Stop All button group.
        // The button text and action swap based on the engine's current
        // mode. When the engine is in CATEGORY mode (side-by-side active
        // for the current category), the button shows "■ Stop All" and
        // dispatches a stop action. Otherwise it shows "▶ Play All"
        // and dispatches a play action.
        val isCategoryActive = AudioPreviewEngine.previewMode == AudioPreviewEngine.PreviewMode.CATEGORY
        val playAllBtn = document.createElement("button") as HTMLButtonElement
        playAllBtn.className = "btn play-all-btn"
        if (isCategoryActive) {
            playAllBtn.setAttribute("data-testid", "stop-all-category")
            playAllBtn.setAttribute("data-action", "preview-stop-all-category")
            playAllBtn.textContent = "■ Stop All"
        } else {
            playAllBtn.setAttribute("data-testid", "play-all-category")
            playAllBtn.setAttribute("data-action", "preview-play-all-category")
            playAllBtn.textContent = "▶ Play All"
        }
        header.appendChild(playAllBtn)

        container.appendChild(header)

        if (tracks.isEmpty())
        {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "empty-message"
            empty.textContent = "No tracks yet. Click \"+ New Track\" to add one."
            container.appendChild(empty)
        }
        else
        {
            tracks.forEach { track ->
                container.appendChild(renderTrackRow(track, cb))
            }
        }

        return container
    }

    /**
     * Build a single track row showing the resource name, volume, and
     * Edit/Delete buttons.
     *
     * @param track The track to render
     * @param cb Callbacks invoked by the row's buttons
     * @return A fresh `<div class="track-row">` element
     */
    private fun renderTrackRow(track: AudioObject, cb: Callbacks): HTMLElement
    {
        val row = document.createElement("div") as HTMLElement
        row.className = "track-row"
        row.setAttribute("data-track-id", track.id)

        val resource = document.createElement("span") as HTMLElement
        resource.className = "track-row-resource"
        resource.textContent = track.resourceName
        row.appendChild(resource)

        val meta = document.createElement("span") as HTMLElement
        meta.className = "track-row-meta"
        val parts = mutableListOf<String>()
        parts += "vol=${track.volume}"
        if (track.loop) parts += "loop"
        if (track.speed != 1.0f) parts += "speed=${track.speed}"
        meta.textContent = parts.joinToString(" ")
        row.appendChild(meta)

        val actions = document.createElement("div") as HTMLElement
        actions.className = "track-row-actions"

        val editBtn = document.createElement("button") as HTMLButtonElement
        editBtn.className = "btn btn-row-action"
        editBtn.setAttribute("data-testid", "edit-track-${track.id}")
        editBtn.setAttribute("data-action", "edit-track")
        editBtn.setAttribute("data-arg", track.id)
        editBtn.textContent = "Edit"
        actions.appendChild(editBtn)

        val deleteBtn = document.createElement("button") as HTMLButtonElement
        deleteBtn.className = "btn btn-row-action btn-danger"
        deleteBtn.setAttribute("data-testid", "delete-track-${track.id}")
        deleteBtn.setAttribute("data-action", "delete-track")
        deleteBtn.setAttribute("data-arg", track.id)
        deleteBtn.textContent = "Delete"
        actions.appendChild(deleteBtn)

        row.appendChild(actions)
        return row
    }

    /**
     * Build the edit/create modal as a `<dialog>` element. The caller
     * decides whether to call `showModal()` on the returned element
     * based on whether [EditorState.modalState] is non-null.
     *
     * @param state Current editor state (used for the modal title and
     *   default field values)
     * @param draft The track being created or edited, pre-populating
     *   the form fields
     * @param cb Callbacks invoked by the modal's Save / Close buttons
     * @return A fresh `<dialog id="edit-modal">` element
     */
    fun renderModal(state: EditorState, draft: AudioObject, cb: Callbacks, trackId: String = draft.id): HTMLDialogElement
    {
        val dialog = document.createElement("dialog") as HTMLDialogElement
        dialog.id = "edit-modal"
        dialog.setAttribute("data-testid", "edit-modal")
        dialog.setAttribute("data-modal-track-id", trackId)

        val header = document.createElement("div") as HTMLElement
        header.className = "modal-header"

        val title = document.createElement("h2") as HTMLElement
        title.className = "modal-title"
        title.textContent = if (state.modalState is ModalState.Edit) "Edit Track" else "New Track"
        header.appendChild(title)

        val closeBtn = document.createElement("button") as HTMLButtonElement
        closeBtn.className = "modal-close"
        closeBtn.setAttribute("data-testid", "modal-close")
        closeBtn.setAttribute("data-action", "modal-cancel")
        closeBtn.textContent = "x"
        header.appendChild(closeBtn)

        dialog.appendChild(header)

        // Preview fieldset (audio file loading + per-track play/stop)
        // The Preview fieldset is always rendered (even when no buffer is
        // loaded) so the Load button is always discoverable.
        dialog.appendChild(renderPreviewFieldset(trackId))

        // Identity fieldset
        dialog.appendChild(renderIdentityFieldset(draft))
        // Mixing fieldset (sliders)
        dialog.appendChild(renderMixingFieldset(draft))
        // Looping fieldset
        dialog.appendChild(renderLoopingFieldset(draft))
        // Timing fieldset
        dialog.appendChild(renderTimingFieldset(draft))
        // Fades fieldset
        dialog.appendChild(renderFadesFieldset(draft))

        // Footer with Save / Cancel
        val footer = document.createElement("div") as HTMLElement
        footer.className = "modal-actions"

        val cancelBtn = document.createElement("button") as HTMLButtonElement
        cancelBtn.className = "btn"
        cancelBtn.setAttribute("data-testid", "modal-cancel")
        cancelBtn.setAttribute("data-action", "modal-cancel")
        cancelBtn.textContent = "Cancel"
        footer.appendChild(cancelBtn)

        val saveBtn = document.createElement("button") as HTMLButtonElement
        saveBtn.className = "btn btn-primary"
        saveBtn.setAttribute("data-testid", "modal-save")
        saveBtn.setAttribute("data-action", "modal-save")
        saveBtn.textContent = "Save"
        footer.appendChild(saveBtn)

        dialog.appendChild(footer)
        return dialog
    }

    /**
     * Build the "Identity" fieldset with the resource name and channel id.
     */
    private fun renderIdentityFieldset(draft: AudioObject): HTMLElement
    {
        val fs = document.createElement("fieldset") as HTMLElement
        fs.className = "modal-fieldset"

        val legend = document.createElement("legend") as HTMLElement
        legend.className = "modal-legend"
        legend.textContent = "Identity"
        fs.appendChild(legend)

        fs.appendChild(renderTextField("f-resourceName", "Resource name", draft.resourceName, true))
        fs.appendChild(renderTextField("f-channelId", "Channel id", draft.channelId, true))
        return fs
    }

    /**
     * Build the "Mixing" fieldset with volume, panning, and speed sliders.
     */
    private fun renderMixingFieldset(draft: AudioObject): HTMLElement
    {
        val fs = document.createElement("fieldset") as HTMLElement
        fs.className = "modal-fieldset"

        val legend = document.createElement("legend") as HTMLElement
        legend.className = "modal-legend"
        legend.textContent = "Mixing"
        fs.appendChild(legend)

        fs.appendChild(renderSliderField("f-volume", "Volume", draft.volume, 0.0f, 1.0f, 0.01f))
        fs.appendChild(renderSliderField("f-panning", "Panning", draft.panning, -1.0f, 1.0f, 0.01f))
        fs.appendChild(renderSliderField("f-speed", "Speed", draft.speed, 0.25f, 4.0f, 0.05f))
        return fs
    }

    /**
     * Build the "Looping" fieldset with the loop checkbox and the
     * optional loop region inputs.
     */
    private fun renderLoopingFieldset(draft: AudioObject): HTMLElement
    {
        val fs = document.createElement("fieldset") as HTMLElement
        fs.className = "modal-fieldset"

        val legend = document.createElement("legend") as HTMLElement
        legend.className = "modal-legend"
        legend.textContent = "Looping"
        fs.appendChild(legend)

        fs.appendChild(renderCheckboxField("f-loop", "Loop playback", draft.loop))
        fs.appendChild(renderTextField("f-loopStart", "Loop start (s)", draft.loopStart?.toString().orEmpty(), false))
        fs.appendChild(renderTextField("f-loopEnd", "Loop end (s)", draft.loopEnd?.toString().orEmpty(), false))
        fs.appendChild(renderCheckboxField("f-loopWithTail", "Loop with tail", draft.loopWithTail))
        return fs
    }

    /**
     * Build the "Timing" fieldset with start time/frame/sample and end time.
     */
    private fun renderTimingFieldset(draft: AudioObject): HTMLElement
    {
        val fs = document.createElement("fieldset") as HTMLElement
        fs.className = "modal-fieldset"

        val legend = document.createElement("legend") as HTMLElement
        legend.className = "modal-legend"
        legend.textContent = "Timing"
        fs.appendChild(legend)

        fs.appendChild(renderTextField("f-startTimeMs", "Start time (ms)", draft.startTimeMs.toString(), false))
        fs.appendChild(renderTextField("f-startFrame", "Start frame", draft.startFrame?.toString().orEmpty(), false))
        fs.appendChild(renderTextField("f-startSample", "Start sample", draft.startSample?.toString().orEmpty(), false))
        fs.appendChild(renderTextField("f-endTimeMs", "End time (ms)", draft.endTimeMs?.toString().orEmpty(), false))
        return fs
    }

    /**
     * Build the "Fades" fieldset with fade-in and fade-out durations.
     */
    private fun renderFadesFieldset(draft: AudioObject): HTMLElement
    {
        val fs = document.createElement("fieldset") as HTMLElement
        fs.className = "modal-fieldset"

        val legend = document.createElement("legend") as HTMLElement
        legend.className = "modal-legend"
        legend.textContent = "Fades"
        fs.appendChild(legend)

        fs.appendChild(renderTextField("f-fadeInDurationMs", "Fade in (ms)", draft.fadeInDurationMs.toString(), false))
        fs.appendChild(renderTextField("f-fadeOutDurationMs", "Fade out (ms)", draft.fadeOutDurationMs.toString(), false))
        return fs
    }

    /**
     * Build a labelled single-line text input row.
     *
     * @param id Element id (used by Playwright and by [EventHandlers.parseForm])
     * @param label Human-readable label rendered in the left column
     * @param value Initial value
     * @param required Whether the field is required (adds the `required` attribute)
     * @return A fresh `<div class="form-row">` element
     */
    private fun renderTextField(id: String, label: String, value: String, required: Boolean): HTMLElement
    {
        val row = document.createElement("div") as HTMLElement
        row.className = "form-row"

        val lbl = document.createElement("label") as HTMLElement
        lbl.className = "form-label"
        lbl.setAttribute("for", id)
        lbl.textContent = label
        row.appendChild(lbl)

        val input = document.createElement("input") as HTMLInputElement
        input.className = "form-input"
        input.id = id
        input.setAttribute("data-testid", id)
        input.type = "text"
        input.value = value
        if (required) input.required = true
        row.appendChild(input)

        return row
    }

    /**
     * Build a labelled checkbox row.
     *
     * @param id Element id (used by Playwright and by [EventHandlers.parseForm])
     * @param label Human-readable label
     * @param checked Initial checked state
     * @return A fresh `<div class="form-row">` element
     */
    private fun renderCheckboxField(id: String, label: String, checked: Boolean): HTMLElement
    {
        val row = document.createElement("div") as HTMLElement
        row.className = "form-row"

        val lbl = document.createElement("label") as HTMLElement
        lbl.className = "form-label"
        lbl.setAttribute("for", id)
        lbl.textContent = label
        row.appendChild(lbl)

        val input = document.createElement("input") as HTMLInputElement
        input.className = "form-checkbox"
        input.id = id
        input.setAttribute("data-testid", id)
        input.type = "checkbox"
        input.checked = checked
        row.appendChild(input)

        return row
    }

    /**
     * Build a labelled range slider row with a live numeric readout.
     *
     * @param id Element id of the `<input type="range">`
     * @param label Human-readable label
     * @param value Initial numeric value
     * @param min Slider minimum
     * @param max Slider maximum
     * @param step Slider step
     * @return A fresh `<div class="form-row">` element
     */
    private fun renderSliderField(
        id: String,
        label: String,
        value: Float,
        min: Float,
        max: Float,
        step: Float
    ): HTMLElement
    {
        val row = document.createElement("div") as HTMLElement
        row.className = "form-row"

        val lbl = document.createElement("label") as HTMLElement
        lbl.className = "form-label"
        lbl.setAttribute("for", id)
        lbl.textContent = label
        row.appendChild(lbl)

        val sliderRow = document.createElement("div") as HTMLElement
        sliderRow.className = "slider-row"

        val input = document.createElement("input") as HTMLInputElement
        input.className = "form-slider"
        input.id = id
        input.setAttribute("data-testid", id)
        input.type = "range"
        input.min = min.toString()
        input.max = max.toString()
        input.step = step.toString()
        input.value = value.toString()
        sliderRow.appendChild(input)

        val readout = document.createElement("span") as HTMLElement
        readout.className = "slider-value"
        readout.setAttribute("data-testid", "$id-readout")
        readout.textContent = formatSliderValue(value)
        sliderRow.appendChild(readout)

        input.addEventListener("input", {
            readout.textContent = formatSliderValue(input.value.toFloatOrNull() ?: 0.0f)
            // If a preview is active for this trackId, apply the change
            // live via the engine. The trackId is read from the modal's
            // data-modal-track-id attribute (set by renderModal).
            val value = input.value.toFloatOrNull() ?: return@addEventListener
            val modal = document.getElementById("edit-modal") as? org.w3c.dom.HTMLElement
            val trackId = modal?.getAttribute("data-modal-track-id") ?: return@addEventListener
            when (id) {
                "f-volume" -> AudioPreviewEngine.updateLive(trackId, "volume", value)
                "f-panning" -> AudioPreviewEngine.updateLive(trackId, "panning", value)
                "f-speed" -> AudioPreviewEngine.updateLive(trackId, "speed", value)
            }
        })

        row.appendChild(sliderRow)
        return row
    }

    /**
     * Format a float with 2 decimal places, e.g. 1.0 -> "1.00", 0.5 -> "0.50".
     * [String.format] is not available in Kotlin/JS, so we hand-roll the
     * 2-decimal formatting using [kotlin.math.round].
     */
    private fun formatFloat2(value: Double): String {
        val rounded = kotlin.math.round(value * 100.0).toInt()
        val whole = rounded / 100
        val frac = kotlin.math.abs(rounded % 100)
        val fracStr = if (frac < 10) "0$frac" else frac.toString()
        val sign = if (rounded < 0 && whole == 0) "-" else ""
        return "$sign$whole.$fracStr"
    }

    /**
     * Format a slider numeric value for display: integers when the value is
     * whole, otherwise up to two decimal places. The JS target has no
     * [String.format] extension, so we hand-roll the two-decimal format
     * using [kotlin.math.round].
     */
    private fun formatSliderValue(value: Float): String
    {
        val asInt = value.toInt().toFloat()
        return if (value == asInt)
        {
            asInt.toInt().toString()
        }
        else
        {
            val rounded = kotlin.math.round(value * 100.0).toInt()
            val whole = rounded / 100
            val frac = kotlin.math.abs(rounded % 100)
            val fracStr = if (frac < 10) "0$frac" else frac.toString()
            val sign = if (rounded < 0 && whole == 0) "-" else ""
            "$sign$whole.$fracStr"
        }
    }

    /**
     * Extract the [AudioObject] list for the currently selected category.
     */
    private fun tracksForCategory(
        tracks: structs.audio.AudioTracks,
        category: Category
    ): List<AudioObject>
    {
        return when (category)
        {
            Category.DRONE -> tracks.drone
            Category.MELODY -> tracks.melody
            Category.RHYTHM -> tracks.rhythm
            Category.HARMONY -> tracks.harmony
            Category.MENU -> tracks.menu
            Category.START -> tracks.start
            Category.NEMESIS -> tracks.nemesis
            Category.END -> tracks.end
        }
    }

    /**
     * Build the Preview fieldset for the edit/create modal. The fieldset
     * contains:
     *  - A "Load audio file" button that opens the hidden audio file picker.
     *  - A status line that shows the loaded file's name, duration, and
     *    channel count (or "No file loaded").
     *  - A "Play" / "Stop" button group.
     *  - A "Clear" link that detaches the loaded buffer.
     *
     * The Play/Stop button's text and disabled state are driven by the
     * engine's state, which we read via a small JS-side bridge. Because
     * the engine state changes asynchronously, the fieldset always renders
     * in the "ready" state (Play enabled when a buffer is loaded) and
     * the actual play/stop toggling is done by the global click handler.
     */
    private fun renderPreviewFieldset(trackId: String): HTMLElement
    {
        val fs = document.createElement("fieldset") as HTMLElement
        fs.className = "modal-fieldset preview-fieldset"

        val legend = document.createElement("legend") as HTMLElement
        legend.className = "modal-legend"
        legend.textContent = "Preview"
        fs.appendChild(legend)

        // Query the engine for the current state of this track's audio.
        val buffer = AudioPreviewEngine.getLoadedBuffer(trackId)
        val fileName = AudioPreviewEngine.getLoadedFileName(trackId)
        val isActive = AudioPreviewEngine.isPlaying(trackId)

        // The status line shows "No file loaded" when no buffer is
        // attached, or "Loaded: <name> (<dur>s, <ch>ch)" when a buffer
        // is attached. The duration and channel count are read from the
        // engine at render time.
        val status = document.createElement("div") as HTMLElement
        status.className = "preview-status"
        status.setAttribute("data-testid", "preview-status")
        status.setAttribute("data-status-track-id", trackId)
        status.textContent = if (buffer == null) {
            "No file loaded"
        } else {
            val dur = formatFloat2(buffer.duration)
            "Loaded: ${fileName ?: "audio"} (${dur}s, ${buffer.numberOfChannels}ch)"
        }
        fs.appendChild(status)

        // The button row: [Load file] [Play/Stop] [Clear (when buffer loaded)]
        val controls = document.createElement("div") as HTMLElement
        controls.className = "preview-controls"

        val loadBtn = document.createElement("button") as HTMLButtonElement
        loadBtn.className = "btn"
        loadBtn.setAttribute("data-testid", "preview-load-audio")
        loadBtn.setAttribute("data-action", "preview-load-audio")
        loadBtn.setAttribute("data-arg", trackId)
        loadBtn.textContent = "Load audio file"
        controls.appendChild(loadBtn)

        // The Play/Stop button is rendered with the current state. When
        // a buffer is loaded but no preview is active, show Play. When a
        // preview is active, show Stop. The visibility swap is handled
        // by the engine state at render time.
        val playBtn = document.createElement("button") as HTMLButtonElement
        playBtn.className = "btn btn-primary"
        playBtn.setAttribute("data-testid", "preview-play")
        playBtn.setAttribute("data-action", "preview-play")
        playBtn.setAttribute("data-arg", trackId)
        playBtn.textContent = "▶ Play"
        playBtn.disabled = buffer == null
        playBtn.setAttribute("style", if (isActive) "display: none;" else "")
        controls.appendChild(playBtn)

        val stopBtn = document.createElement("button") as HTMLButtonElement
        stopBtn.className = "btn"
        stopBtn.setAttribute("data-testid", "preview-stop")
        stopBtn.setAttribute("data-action", "preview-stop")
        stopBtn.setAttribute("data-arg", trackId)
        stopBtn.textContent = "■ Stop"
        stopBtn.setAttribute("style", if (isActive) "" else "display: none;")
        controls.appendChild(stopBtn)

        // The Clear link is always rendered (hidden via inline style when
        // no buffer is loaded) so the modal DOM stays stable across
        // preview events. That way [EventHandlers] can toggle the
        // visibility in place instead of forcing a full modal re-render,
        // which would wipe the user's in-flight form edits.
        val clearBtn = document.createElement("button") as HTMLButtonElement
        clearBtn.className = "link-button preview-clear"
        clearBtn.setAttribute("data-testid", "preview-clear-audio")
        clearBtn.setAttribute("data-action", "preview-clear-audio")
        clearBtn.setAttribute("data-arg", trackId)
        clearBtn.textContent = "✕ Clear"
        clearBtn.setAttribute("style", if (buffer == null) "display: none;" else "")
        controls.appendChild(clearBtn)

        fs.appendChild(controls)
        return fs
    }
}
