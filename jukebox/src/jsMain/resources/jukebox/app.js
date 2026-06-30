// Jukebox UI Application
// Initializes audio on first user interaction (browser autoplay policy)
//
// The Kotlin/JS bundle (jukebox.js) is loaded BEFORE this script via a
// <script defer> tag in index.html. At module load time JukeboxMain.kt runs
// `window.asDynamic().jukebox = JukeboxAudioEngine`, so we reach the engine
// exclusively through `window.jukebox.X(...)` below.

(function() {
    'use strict';

    // Track initialization state
    let initialized = false;

    // Initialize on first user interaction (browser autoplay policy)
    document.addEventListener('click', function initAudio() {
        if (initialized) return;
        initialized = true;
        document.removeEventListener('click', initAudio);

        if (window.jukebox && typeof window.jukebox.initJukebox === 'function') {
            window.jukebox.initJukebox();
            logAction('init', '-', 'ok');
        }
        updateConnectionStatus();
    }, { once: true });

    // Music button handlers
    ['forest', 'battle', 'menu'].forEach(function(name) {
        var btn = document.getElementById('btn-' + name);
        if (btn) {
            btn.addEventListener('click', function() {
                if (window.jukebox && typeof window.jukebox.jukeboxPlay === 'function') {
                    window.jukebox.jukeboxPlay(name);
                    logAction('play', name);
                }
            });
        }
    });

    // SFX button handlers
    ['click', 'explosion', 'gunshot', 'footstep'].forEach(function(name) {
        var btn = document.getElementById('btn-sfx-' + name);
        if (btn) {
            btn.addEventListener('click', function() {
                if (window.jukebox && typeof window.jukebox.jukeboxPlay === 'function') {
                    window.jukebox.jukeboxPlay('sfx.' + name);
                    logAction('play', 'sfx.' + name);
                }
            });
        }
    });

    // Mute buttons
    var musicMuteBtn = document.getElementById('btn-music-mute');
    var sfxMuteBtn = document.getElementById('btn-sfx-mute');

    if (musicMuteBtn) {
        musicMuteBtn.addEventListener('click', function() {
            this.classList.toggle('active');
            if (window.jukebox && typeof window.jukebox.jukeboxSetChannelMute === 'function') {
                window.jukebox.jukeboxSetChannelMute('music', this.classList.contains('active'));
            }
            logAction('mute', 'music', this.classList.contains('active') ? 'on' : 'off');
        });
    }

    if (sfxMuteBtn) {
        sfxMuteBtn.addEventListener('click', function() {
            this.classList.toggle('active');
            if (window.jukebox && typeof window.jukebox.jukeboxSetChannelMute === 'function') {
                window.jukebox.jukeboxSetChannelMute('sfx', this.classList.contains('active'));
            }
            logAction('mute', 'sfx', this.classList.contains('active') ? 'on' : 'off');
        });
    }

    // Note: the per-object loop toggle is part of the active-players row,
    // wired via event delegation below. There is no global loop button.

    // Global volume slider
    var globalVolSlider = document.getElementById('global-volume');
    var globalVolValue = document.getElementById('global-volume-value');
    if (globalVolSlider) {
        globalVolSlider.addEventListener('input', function() {
            // Sliders are in dB (-80 to +3). The engine expects linear gain
            // (0.0–1.0+). Convert dB → linear before crossing the bridge.
            // Bug B3 fix: previously the dB value was passed as-is, so the
            // "unity" 0 dB position produced silence (gain 0.0).
            var dbValue = parseInt(this.value);
            var linearValue = Math.pow(10, dbValue / 20);
            if (globalVolValue) globalVolValue.textContent = dbValue + ' dB (' + linearValue.toFixed(2) + 'x)';
            if (window.jukebox && typeof window.jukebox.jukeboxSetGlobalVolume === 'function') {
                window.jukebox.jukeboxSetGlobalVolume(linearValue);
            }
        });
    }

    // Music volume slider
    var musicVolSlider = document.getElementById('music-volume');
    if (musicVolSlider) {
        musicVolSlider.addEventListener('input', function() {
            // Sliders are in dB (-80 to +3). The engine expects linear gain
            // (0.0–1.0+). Convert dB → linear before crossing the bridge.
            // Bug B3 fix: previously the dB value was passed as-is, so the
            // "unity" 0 dB position produced silence (gain 0.0) and most
            // other positions produced nonsensical negative or amplified gains.
            var dbValue = parseInt(this.value);
            var linearValue = Math.pow(10, dbValue / 20);
            if (window.jukebox && typeof window.jukebox.jukeboxSetChannelVolume === 'function') {
                window.jukebox.jukeboxSetChannelVolume('music', linearValue);
            }
        });
    }

    // SFX volume slider
    var sfxVolSlider = document.getElementById('sfx-volume');
    var sfxVolValue = document.getElementById('sfx-volume-value');
    if (sfxVolSlider) {
        sfxVolSlider.addEventListener('input', function() {
            // Sliders are in dB (-80 to +3). The engine expects linear gain
            // (0.0–1.0+). Convert dB → linear before crossing the bridge.
            // B2 fix: also update the sibling .slider-value label so the user
            // sees the current dB value, matching the music / global slider
            // pattern. Previously the SFX row showed no label.
            var dbValue = parseInt(this.value);
            var linearValue = Math.pow(10, dbValue / 20);
            if (sfxVolValue) sfxVolValue.textContent = dbValue + ' dB';
            if (window.jukebox && typeof window.jukebox.jukeboxSetChannelVolume === 'function') {
                window.jukebox.jukeboxSetChannelVolume('sfx', linearValue);
            }
        });
    }

    // Default panning slider (applies to subsequent plays)
    var defaultPanSlider = document.getElementById('default-pan');
    var defaultPanValue = document.getElementById('default-pan-value');
    if (defaultPanSlider) {
        defaultPanSlider.addEventListener('input', function() {
            var value = parseFloat(this.value);
            if (defaultPanValue) defaultPanValue.textContent = value.toFixed(2);
            if (window.jukebox && typeof window.jukebox.jukeboxSetDefaultPanning === 'function') {
                window.jukebox.jukeboxSetDefaultPanning(value);
            }
        });
    }

    // Default speed slider (applies to subsequent plays)
    var defaultSpeedSlider = document.getElementById('default-speed');
    var defaultSpeedValue = document.getElementById('default-speed-value');
    if (defaultSpeedSlider) {
        defaultSpeedSlider.addEventListener('input', function() {
            var value = parseFloat(this.value);
            if (defaultSpeedValue) defaultSpeedValue.textContent = value.toFixed(2) + 'x';
            if (window.jukebox && typeof window.jukebox.jukeboxSetDefaultSpeed === 'function') {
                window.jukebox.jukeboxSetDefaultSpeed(value);
            }
        });
    }

    // Fade in/out inputs
    var fadeInInput = document.getElementById('fade-in-ms');
    var fadeOutInput = document.getElementById('fade-out-ms');

    if (fadeInInput) {
        fadeInInput.addEventListener('change', function() {
            var value = parseInt(this.value) || 0;
            if (window.jukebox && typeof window.jukebox.jukeboxSetFadeIn === 'function') {
                window.jukebox.jukeboxSetFadeIn(value);
            }
            logAction('fadeIn', value + 'ms');
        });
    }

    if (fadeOutInput) {
        fadeOutInput.addEventListener('change', function() {
            var value = parseInt(this.value) || 0;
            if (window.jukebox && typeof window.jukebox.jukeboxSetFadeOut === 'function') {
                window.jukebox.jukeboxSetFadeOut(value);
            }
            logAction('fadeOut', value + 'ms');
        });
    }

    // Loop-mode toggle in the Fade section. B6 fix: this drives the default
    // loop flag on subsequent jukeboxPlay calls. Click flips between Off and
    // On, updates the button label + class, and forwards to the facade.
    var loopModeBtn = document.getElementById('btn-loop-mode');
    if (loopModeBtn) {
        loopModeBtn.addEventListener('click', function() {
            var next = loopModeBtn.getAttribute('data-state') !== 'on';
            loopModeBtn.setAttribute('data-state', next ? 'on' : 'off');
            loopModeBtn.textContent = next ? 'On' : 'Off';
            if (next) { loopModeBtn.classList.add('active'); } else { loopModeBtn.classList.remove('active'); }
            if (window.jukebox && typeof window.jukebox.setDefaultLoop === 'function') {
                window.jukebox.setDefaultLoop(next);
            }
            logAction('loopMode', next ? 'on' : 'off');
        });
    }

    // Per-player controls (event delegation on active-players panel)
    var activePlayersPanel = document.getElementById('active-players');
    if (activePlayersPanel) {
        activePlayersPanel.addEventListener('click', function(e) {
            var playerRow = e.target.closest('[data-player-id]');
            if (!playerRow) return;

            var playerId = playerRow.dataset.playerId;

            if (e.target.matches('[data-action="pause"]')) {
                if (window.jukebox && typeof window.jukebox.jukeboxPause === 'function') {
                    window.jukebox.jukeboxPause(playerId);
                    logAction('pause', playerId);
                }
            } else if (e.target.matches('[data-action="resume"]')) {
                if (window.jukebox && typeof window.jukebox.jukeboxResume === 'function') {
                    window.jukebox.jukeboxResume(playerId);
                    logAction('resume', playerId);
                }
            } else if (e.target.matches('[data-action="stop"]')) {
                if (window.jukebox && typeof window.jukebox.jukeboxStop === 'function') {
                    window.jukebox.jukeboxStop(playerId);
                    logAction('stop', playerId);
                }
            } else if (e.target.matches('[data-action="loop"]')) {
                // Per-object loop toggle. The playerRow holds the current loop
                // state as `data-loop` (rendered by renderActivePlayers); the
                // button's "active" class mirrors that state. We invert the
                // current state and call jukeboxSetLoop with the objectId.
                //
                // F1 fix: update the row's data-loop AND the button's class
                // and emoji IMMEDIATELY here, not on the next 500 ms poll.
                // Otherwise two quick clicks within a poll window would
                // read the same stale data-loop and cancel out.
                var currentlyLooping = playerRow.dataset.loop === 'true';
                var newLooping = !currentlyLooping;
                playerRow.dataset.loop = newLooping ? 'true' : 'false';
                var btn = e.target;
                btn.classList.toggle('active', newLooping);
                btn.textContent = newLooping ? '🔁' : '↻';
                if (window.jukebox && typeof window.jukebox.jukeboxSetLoop === 'function') {
                    window.jukebox.jukeboxSetLoop(playerId, newLooping);
                    logAction('loop', playerId, newLooping ? 'on' : 'off');
                }
            }
        });

        // Per-player sliders (event delegation)
        activePlayersPanel.addEventListener('input', function(e) {
            var playerRow = e.target.closest('[data-player-id]');
            if (!playerRow) return;

            var playerId = playerRow.dataset.playerId;

            if (e.target.matches('[data-slider="volume"]')) {
                // dB → linear (B3 fix): the per-player volume slider is in dB;
                // the engine expects a linear gain scalar.
                var dbValue = parseInt(e.target.value);
                var linearValue = Math.pow(10, dbValue / 20);
                if (window.jukebox && typeof window.jukebox.jukeboxSetPlayerVolume === 'function') {
                    window.jukebox.jukeboxSetPlayerVolume(playerId, linearValue);
                }
                var label = playerRow.querySelector('.volume-value');
                if (label) label.textContent = dbValue + ' dB (' + linearValue.toFixed(2) + 'x)';
            } else if (e.target.matches('[data-slider="pan"]')) {
                var value = parseFloat(e.target.value);
                if (window.jukebox && typeof window.jukebox.jukeboxSetPlayerPan === 'function') {
                    window.jukebox.jukeboxSetPlayerPan(playerId, value);
                }
                var label = playerRow.querySelector('.pan-value');
                if (label) label.textContent = value.toFixed(2);
            } else if (e.target.matches('[data-slider="speed"]')) {
                var value = parseFloat(e.target.value);
                if (window.jukebox && typeof window.jukebox.jukeboxSetPlayerSpeed === 'function') {
                    window.jukebox.jukeboxSetPlayerSpeed(playerId, value);
                }
                var label = playerRow.querySelector('.speed-value');
                if (label) label.textContent = value.toFixed(2) + 'x';
            }
        });
    }

    /**
     * Logs an action to the action log panel
     */
    function logAction(action, params, result) {
        result = result || 'ok';
        var timestamp = new Date().toISOString().slice(11, 19);
        var logPanel = document.getElementById('action-log');
        if (!logPanel) return;

        var entry = document.createElement('div');
        entry.className = 'log-entry';
        entry.innerHTML =
            '<span class="timestamp">[' + timestamp + ']</span> ' +
            '<span class="action">' + action + '(' + params + ')</span> ' +
            '→ ' +
            '<span class="result">' + result + '</span>';

        logPanel.insertBefore(entry, logPanel.firstChild);

        while (logPanel.children.length > 100) {
            logPanel.removeChild(logPanel.lastChild);
        }
    }

    /**
     * Updates the connection status indicator.
     * Standalone jukebox has no server, so we show "Standalone" with a neutral dot
     * instead of the misleading "Disconnected" red state.
     */
    function updateConnectionStatus() {
        var indicator = document.getElementById('connection-indicator');
        var text = document.getElementById('connection-text');
        if (!indicator || !text) return;

        if (typeof window.jukebox !== 'undefined' && window.jukebox.connected) {
            indicator.className = 'connection-dot connected';
            text.textContent = 'Connected';
        } else {
            indicator.className = 'connection-dot standalone';
            text.textContent = 'Standalone';
        }
    }

    /**
     * Renders the active players list
     */
    function renderActivePlayers(players) {
        var container = document.getElementById('active-players');
        if (!container) return;

        if (!players || players.length === 0) {
            container.innerHTML = '<div class="empty-state">No active audio players</div>';
            return;
        }

        var html = '';
        players.forEach(function(player) {
            // PlayerState @JsExport uses isPlaying / isPaused / isEnded (Kotlin boolean
            // is-prefix naming) and panning / resourceName (full words). Anything else
            // here is a no-op fallback for the renderer.
            var isActive = !!(player.isPlaying || player.isPaused);
            var isLooping = !!player.loop;
            var resourceLabel = player.resourceName || '';
            var currentPan = (player.panning !== undefined ? player.panning : 0);
            var currentSpeed = (player.speed !== undefined ? player.speed : 1);
            var currentVol = (player.volume !== undefined ? player.volume : 0);
            // B1 fix: live playback-position readout, displayed in the row's
            // transport area. Rounded to integer seconds. The engine exposes
            // currentTimeMs (Long) on each PlayerState, which @JsExport
            // serialises to a JS BigInt — arithmetic mixing BigInt and Number
            // throws, so coerce to Number first via `Number(...)`.
            var rawMs = player.currentTimeMs;
            var currentMs = (rawMs !== undefined && rawMs !== null) ? Number(rawMs) : 0;
            var currentSec = Math.floor(currentMs / 1000);
            // B3 fix: the per-player volume slider uses dB units (-80 to +3) to
            // match the global/music/sfx sliders. PlayerState.volume is a linear
            // gain (0.0–1.0+). Convert linear → dB for the initial render so
            // the freshly-spawned slider shows "0.0 dB" (not "1 dB") until the
            // user touches it. Matches the dB helper used by the global volume.
            var initialVolDb = currentVol > 0 ? (20 * Math.log10(currentVol)) : -80;
            var initialVolDbClamped = Math.max(-80, Math.min(3, initialVolDb));
            var initialVolDbLabel = initialVolDbClamped.toFixed(1);
            // Fade indicator: PlayerState exposes the live gainNode.gain.value
            // (currentGain) and the expected target (volume * channel.effective
            // * global). When they differ by more than 5% a fade is in progress.
            // The fade direction is taken from [PlayerState.isFadingOut]
            // (set by the engine on stop), not derived from the gain ratio —
            // the ratio alone can't distinguish fade-in from fade-out.
            var cg = (player.currentGain !== undefined ? Number(player.currentGain) : 1);
            var tg = (player.targetGain !== undefined ? Number(player.targetGain) : 1);
            var isFadingOut = !!player.isFadingOut;
            var fadeBadge = '';
            var fadeProgressPct = 0;
            if (tg > 0.0001 || isFadingOut) {
                var ratio = tg > 0.0001 ? cg / tg : 0;
                if (isFadingOut) {
                    // During fade-out, target is effectively 0, so show the
                    // current gain as a fraction of the *original* target.
                    fadeProgressPct = Math.max(0, Math.min(100, Math.round(ratio * 100)));
                } else {
                    fadeProgressPct = Math.max(0, Math.min(100, Math.round(ratio * 100)));
                }
                var showBadge = isFadingOut || Math.abs(ratio - 1) > 0.05;
                if (showBadge) {
                    var direction = isFadingOut ? 'OUT' : 'IN';
                    fadeBadge =
                        '<span class="fade-badge" title="Fade in progress: gain ' +
                        ratio.toFixed(2) + '× of target">' +
                        '🌫 FADING ' + direction + ' ' + fadeProgressPct + '%</span>';
                }
            }
            html +=
                '<div class="player-row' + (isActive ? ' active' : '') + '" data-player-id="' + escapeHtml(player.id) + '" data-loop="' + isLooping + '">' +
                '  <span class="player-name" title="' + escapeHtml(resourceLabel) + '">' +
                     escapeHtml(resourceLabel) + '</span>' +
                fadeBadge +
                '  <div class="player-controls">' +
                '    <button class="player-btn" data-action="resume" title="Resume">▶</button>' +
                '    <button class="player-btn" data-action="pause" title="Pause">⏸</button>' +
                '    <button class="player-btn" data-action="stop" title="Stop">⏹</button>' +
                '    <button class="player-btn' + (isLooping ? ' active' : '') + '" data-action="loop" title="Loop">' +
                     (isLooping ? '🔁' : '↻') + '</button>' +
                '    <span class="time" title="Playback position">' + currentSec + 's</span>' +
                '  </div>' +
                '  <div class="slider-container">' +
                '    <span class="slider-label">Vol</span>' +
                '    <input type="range" class="slider player-slider" data-slider="volume" ' +
                       'min="-80" max="3" value="' + initialVolDbClamped + '">' +
                '    <span class="volume-value slider-value">' + initialVolDbLabel + ' dB</span>' +
                '  </div>' +
                '  <div class="slider-container">' +
                '    <span class="slider-label">Pan</span>' +
                '    <input type="range" class="slider player-slider" data-slider="pan" ' +
                       'min="-1" max="1" step="0.1" value="' + currentPan + '">' +
                '    <span class="pan-value slider-value">' + currentPan.toFixed(2) + '</span>' +
                '  </div>' +
                '  <div class="slider-container">' +
                '    <span class="slider-label">Speed</span>' +
                '    <input type="range" class="slider player-slider" data-slider="speed" ' +
                       'min="0.25" max="2" step="0.25" value="' + currentSpeed + '">' +
                '    <span class="speed-value slider-value">' + currentSpeed + 'x</span>' +
                '  </div>' +
                '</div>';
        });

        container.innerHTML = html;
    }

    /**
     * Updates the visualizer canvases
     */
    function updateVisualizers(players) {
        var container = document.getElementById('visualizers');
        if (!container) return;

        if (!players || players.length === 0) {
            container.innerHTML = '<div class="empty-state">No visualizers active</div>';
            return;
        }

        var existingCanvas = container.querySelectorAll('canvas');
        var existingIds = [];
        existingCanvas.forEach(function(canvas) {
            existingIds.push(canvas.dataset.playerId);
        });

        var html = '';
        players.forEach(function(player) {
            html += '<canvas class="visualizer" data-player-id="' + escapeHtml(player.id) +
                    '" width="200" height="60" title="' + escapeHtml(player.resourceName || '') + '"></canvas>';
        });

        var currentHtml = container.innerHTML;
        if (html !== currentHtml) {
            container.innerHTML = html;
        }

        players.forEach(function(player) {
            var canvas = container.querySelector('canvas[data-player-id="' + player.id + '"]');
            if (!canvas) return;

            var ctx = canvas.getContext('2d');
            var width = canvas.width;
            var height = canvas.height;

            ctx.fillStyle = '#0d1117';
            ctx.fillRect(0, 0, width, height);

            ctx.strokeStyle = '#30363d';
            ctx.strokeRect(0, 0, width, height);

            if (player.isPlaying) {
                ctx.fillStyle = '#58a6ff';
                var centerY = height / 2;
                var barWidth = 3;
                var gap = 2;
                var bars = Math.floor(width / (barWidth + gap));

                // Issue 5 fix: hoist the jukeboxGetFrequencyData call OUT of
                // the per-bar for loop. Previously this call was inside the
                // loop, so for a 200px canvas it was invoked ~40 times per
                // canvas per 500 ms — and each call returned a freshly
                // allocated FloatArray. Now we call once per canvas and
                // iterate the bars over the returned array.
                var freqData = null;
                if (typeof window.jukebox !== 'undefined' && typeof window.jukebox.jukeboxGetFrequencyData === 'function') {
                    try { freqData = window.jukebox.jukeboxGetFrequencyData(player.id); } catch(e) {}
                }
                if (!freqData && !window.__visualizerWarned) {
                    // B4 fix: log ONCE per session, not once per player per poll.
                    // Previously the warn fired every iteration when freqData
                    // was null (~40× per canvas per 500 ms before Issue 5; after
                    // Issue 5 it was once per player per poll). Now once.
                    window.__visualizerWarned = true;
                    console.warn('Visualizer: freqData unavailable for player ' + player.id + ', using fallback');
                }

                for (var i = 0; i < bars; i++) {
                    var dbValue, normalizedHeight, barHeight;
                    if (freqData && freqData[i] !== undefined) {
                        dbValue = freqData[i];
                        normalizedHeight = Math.max(0, (dbValue + 100) / 100);
                        barHeight = normalizedHeight * height * 0.9;
                    } else {
                        var pseudoRandom = Math.sin(player.id.charCodeAt(0) * (i + 1) * 0.1) * 0.5 + 0.5;
                        barHeight = (pseudoRandom * 0.8 + 0.2) * (height * 0.8);
                    }
                    var x = i * (barWidth + gap);
                    var y = centerY - barHeight / 2;
                    ctx.fillRect(x, y, barWidth, barHeight);
                }
            }
        });
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // Update active players every 500ms — captured so Stop All can cancel it.
    // B5 fix: previously the setInterval was orphaned, running forever after
    // close() because no one held its id. Now stopAll() calls clearInterval.
    var pollIntervalId = setInterval(function() {
        if (window.jukebox && typeof window.jukebox.jukeboxGetActivePlayers === 'function') {
            var players = window.jukebox.jukeboxGetActivePlayers();
            renderActivePlayers(players);
            updateVisualizers(players);
        }
        updateConnectionStatus();
    }, 500);

    // Stop All: stop every active player, cancel the 500ms UI poll, and
    // clear the active-players panel. Does NOT close the AudioContext —
    // closing the context forces a full re-init (user gesture required),
    // which is racy when the same click that triggered Stop All is also
    // expected to be available for the next play. Instead, we leave the
    // engine alive and just stop the players; the next play click works
    // immediately because initJukebox has already completed.
    //
    // The engine's `close()` method remains available for programmatic use
    // (e.g. end-of-session cleanup) via `window.jukebox.close()`.
    function stopAll() {
        if (window.jukebox && typeof window.jukebox.stopAll === 'function') {
            window.jukebox.stopAll();
            logAction('stopAll', '-', 'ok');
        }
        if (pollIntervalId) {
            clearInterval(pollIntervalId);
            pollIntervalId = null;
        }
        // Re-render with an empty player list so the panel clears immediately.
        renderActivePlayers([]);
    }

    // Wire the in-UI Stop All button. The button is rendered by index.html
    // and gets a class of `.btn-stop-all` (id `btn-stop-all`).
    var stopAllBtn = document.getElementById('btn-stop-all');
    if (stopAllBtn) {
        stopAllBtn.addEventListener('click', stopAll);
    }

    // Expose stopAll for programmatic invocation by tests (Playwright).
    window.jukeboxStopAll = stopAll;
    window.jukeboxLogAction = logAction;

    // ----------------------------------------------------------------
    // Phase 3: Electron preload bridge integration
    // ----------------------------------------------------------------
    // The preload script (electronJukebox/src/main/preload.js) exposes
    // `window.electronAPI` with four IPC methods and one event
    // subscription. Every call below is wrapped in a defensive
    // `window.electronAPI && ...` check so the jukebox keeps working
    // when opened in a regular browser tab (e.g. during Phase 1
    // regression tests against the jukebox dist served by python -m
    // http.server). The shape mirrors what main.js / state.js expect
    // on the other end of the IPC channel.

    /** @type {string | null} */
    let lastTrackId = null;

    /** Debounce token for saveJukeboxState. */
    let saveJukeboxStateTimer = null;

    /**
     * Read the current jukebox UI state from the DOM and forward it
     * to the main process via `save-window-state`. The main process
     * merges this subtree into its own window-state blob and
     * debounces the disk write (500ms in main.js).
     *
     * No-op when `window.electronAPI` is not present (plain browser).
     */
    function saveJukeboxState() {
        if (!window.electronAPI || typeof window.electronAPI.saveWindowState !== 'function') {
            return;
        }
        if (saveJukeboxStateTimer) {
            clearTimeout(saveJukeboxStateTimer);
        }
        saveJukeboxStateTimer = setTimeout(function() {
            var j = {
                lastTrackId: lastTrackId,
                globalVolumeDb: parseInt((document.getElementById('global-volume') || {}).value || '0', 10),
                defaultPanning: parseFloat((document.getElementById('default-pan') || {}).value || '0'),
                defaultSpeed: parseFloat((document.getElementById('default-speed') || {}).value || '1'),
                musicVolumeDb: parseInt((document.getElementById('music-volume') || {}).value || '0', 10),
                sfxVolumeDb: parseInt((document.getElementById('sfx-volume') || {}).value || '0', 10),
                musicMuted: !!(document.getElementById('btn-music-mute') || {}).classList && document.getElementById('btn-music-mute').classList.contains('active'),
                sfxMuted: !!(document.getElementById('btn-sfx-mute') || {}).classList && document.getElementById('btn-sfx-mute').classList.contains('active'),
                fadeInMs: parseInt((document.getElementById('fade-in-ms') || {}).value || '0', 10) || 0,
                fadeOutMs: parseInt((document.getElementById('fade-out-ms') || {}).value || '0', 10) || 0,
                defaultLoop: ((document.getElementById('btn-loop-mode') || {}).getAttribute && document.getElementById('btn-loop-mode').getAttribute('data-state')) === 'on',
            };
            window.electronAPI.saveWindowState({ jukebox: j }).then(function(result) {
                if (!result || !result.ok) {
                    console.warn('saveWindowState failed:', result && result.error);
                }
            }).catch(function(err) {
                console.warn('saveWindowState rejected:', err);
            });
        }, 500);
    }

    /**
     * Re-hydrate slider values, mute / loop state, and fade inputs
     * from the persisted window state. Does not auto-play the
     * lastTrackId — playback always requires a user gesture in the
     * browser, and we don't want to surprise the user with sound on
     * launch. We do remember which track was last touched so the
     * UI can show it (and so a future "resume" feature has data).
     */
    function restoreJukeboxState() {
        if (!window.electronAPI || typeof window.electronAPI.getWindowState !== 'function') {
            return;
        }
        window.electronAPI.getWindowState().then(function(result) {
            if (!result || !result.ok) {
                console.warn('getWindowState failed:', result && result.error);
                return;
            }
            var state = result.data;
            if (!state || !state.jukebox) return;
            var j = state.jukebox;

            // Sliders. Setting .value programmatically does NOT fire
            // 'input' or 'change' events, so the engine-side bridge
            // calls (jukeboxSetGlobalVolume etc.) are NOT triggered
            // automatically. Below this block we push the restored
            // values into the engine explicitly so the next play
            // reflects them.
            function setSlider(id, rawValue) {
                var el = document.getElementById(id);
                if (!el) return;
                if (rawValue === undefined || rawValue === null || isNaN(rawValue)) return;
                el.value = String(rawValue);
            }
            setSlider('global-volume', j.globalVolumeDb);
            setSlider('music-volume', j.musicVolumeDb);
            setSlider('sfx-volume', j.sfxVolumeDb);
            setSlider('default-pan', j.defaultPanning);
            setSlider('default-speed', j.defaultSpeed);
            setSlider('fade-in-ms', j.fadeInMs);
            setSlider('fade-out-ms', j.fadeOutMs);

            // Engine push: persist the restored values into the engine so
            // the next play reflects them. The DOM .value writes above do
            // NOT fire 'input' or 'change' events, so we must call the
            // engine setters explicitly. Use the same dB → linear
            // conversion the existing handlers do.
            if (typeof j.globalVolumeDb === 'number') {
                var gDb = j.globalVolumeDb;
                var gLin = gDb > -80 ? Math.pow(10, gDb / 20) : 0;
                if (window.jukebox && typeof window.jukebox.jukeboxSetGlobalVolume === 'function') {
                    window.jukebox.jukeboxSetGlobalVolume(gLin);
                }
            }
            if (typeof j.musicVolumeDb === 'number') {
                var mDb = j.musicVolumeDb;
                var mLin = mDb > -80 ? Math.pow(10, mDb / 20) : 0;
                if (window.jukebox && typeof window.jukebox.jukeboxSetChannelVolume === 'function') {
                    window.jukebox.jukeboxSetChannelVolume('music', mLin);
                }
            }
            if (typeof j.sfxVolumeDb === 'number') {
                var sDb = j.sfxVolumeDb;
                var sLin = sDb > -80 ? Math.pow(10, sDb / 20) : 0;
                if (window.jukebox && typeof window.jukebox.jukeboxSetChannelVolume === 'function') {
                    window.jukebox.jukeboxSetChannelVolume('sfx', sLin);
                }
            }
            if (typeof j.defaultPanning === 'number' && window.jukebox && typeof window.jukebox.jukeboxSetDefaultPanning === 'function') {
                window.jukebox.jukeboxSetDefaultPanning(j.defaultPanning);
            }
            if (typeof j.defaultSpeed === 'number' && window.jukebox && typeof window.jukebox.jukeboxSetDefaultSpeed === 'function') {
                window.jukebox.jukeboxSetDefaultSpeed(j.defaultSpeed);
            }
            if (typeof j.fadeInMs === 'number' && window.jukebox && typeof window.jukebox.jukeboxSetFadeIn === 'function') {
                window.jukebox.jukeboxSetFadeIn(j.fadeInMs);
            }
            if (typeof j.fadeOutMs === 'number' && window.jukebox && typeof window.jukebox.jukeboxSetFadeOut === 'function') {
                window.jukebox.jukeboxSetFadeOut(j.fadeOutMs);
            }
            if (typeof j.musicMuted === 'boolean' && window.jukebox && typeof window.jukebox.jukeboxSetChannelMute === 'function') {
                window.jukebox.jukeboxSetChannelMute('music', j.musicMuted);
            }
            if (typeof j.sfxMuted === 'boolean' && window.jukebox && typeof window.jukebox.jukeboxSetChannelMute === 'function') {
                window.jukebox.jukeboxSetChannelMute('sfx', j.sfxMuted);
            }
            if (typeof j.defaultLoop === 'boolean' && window.jukebox && typeof window.jukebox.setDefaultLoop === 'function') {
                window.jukebox.setDefaultLoop(j.defaultLoop);
            }

            // Update visible labels so the dB / x readouts match the
            // restored values. Mirrors the math used in the existing
            // slider handlers.
            // Re-render only the labels that have a sibling readout
            // (global + sfx). The other sliders are text-free in the
            // existing UI.
            var globalVolEl = document.getElementById('global-volume');
            var globalVolValue = document.getElementById('global-volume-value');
            if (globalVolEl && globalVolValue) {
                var gDb = parseInt(globalVolEl.value, 10);
                var gLin = Math.pow(10, gDb / 20);
                globalVolValue.textContent = gDb + ' dB (' + gLin.toFixed(2) + 'x)';
            }
            var sfxVolEl = document.getElementById('sfx-volume');
            var sfxVolValue = document.getElementById('sfx-volume-value');
            if (sfxVolEl && sfxVolValue) {
                sfxVolValue.textContent = parseInt(sfxVolEl.value, 10) + ' dB';
            }
            var panEl = document.getElementById('default-pan');
            var panValue = document.getElementById('default-pan-value');
            if (panEl && panValue) {
                panValue.textContent = parseFloat(panEl.value).toFixed(2);
            }
            var speedEl = document.getElementById('default-speed');
            var speedValue = document.getElementById('default-speed-value');
            if (speedEl && speedValue) {
                speedValue.textContent = parseFloat(speedEl.value).toFixed(2) + 'x';
            }

            // Mute buttons (music / sfx).
            function setMute(id, muted) {
                var el = document.getElementById(id);
                if (!el) return;
                if (muted) el.classList.add('active');
                else el.classList.remove('active');
            }
            setMute('btn-music-mute', !!j.musicMuted);
            setMute('btn-sfx-mute', !!j.sfxMuted);

            // Loop-mode toggle. Mirrors the existing click handler's
            // state-synchronisation.
            var loopBtn = document.getElementById('btn-loop-mode');
            if (loopBtn) {
                var on = !!j.defaultLoop;
                loopBtn.setAttribute('data-state', on ? 'on' : 'off');
                loopBtn.textContent = on ? 'On' : 'Off';
                if (on) loopBtn.classList.add('active');
                else loopBtn.classList.remove('active');
            }

            // Track id only — never auto-play.
            if (typeof j.lastTrackId === 'string') {
                lastTrackId = j.lastTrackId;
            }
        }).catch(function(err) {
            console.warn('getWindowState failed:', err);
        });
    }

    /**
     * Populate the device-selector `<select>` from
     * `navigator.mediaDevices.enumerateDevices()`, filtered to
     * audio output devices. Also restores the persisted default
     * (if any) as the selected value.
     *
     * enumerateDevices() requires a secure context. In Electron the
     * file:// protocol is treated as secure, so this works. In a
     * regular browser tab over http://localhost the same is true.
     *
     * In Phase 3 we only PERSIST the device id. The actual
     * AudioContext.setSinkId() call must happen in the Kotlin/JS
     * engine (JukeboxAudioEngine.kt) on the next play — wiring that
     * is out of scope for this phase.
     */
    function populateDeviceSelector() {
        var select = document.getElementById('device-selector');
        if (!select) return;

        // Clear all options except the "Default output" sentinel.
        while (select.options.length > 1) {
            select.remove(1);
        }

        function addOption(value, label) {
            var opt = document.createElement('option');
            opt.value = value;
            opt.textContent = label;
            select.appendChild(opt);
        }

        var persistedDefault = 'default';

        // Apply the persisted default to the <select> if it has been
        // resolved. If the option already exists in the list, select
        // it; if it does not (e.g. the device has been unplugged since
        // last session), add a disabled sentinel option so the user
        // can see what happened.
        function applyPersistedDefault() {
            if (!persistedDefault || persistedDefault === 'default') return;
            var exists = Array.prototype.some.call(select.options, function(o) {
                return o.value === persistedDefault;
            });
            if (exists) {
                select.value = persistedDefault;
            } else {
                var opt = document.createElement('option');
                opt.value = persistedDefault;
                opt.textContent = persistedDefault + ' (not available)';
                opt.disabled = true;
                opt.selected = true;
                select.appendChild(opt);
            }
        }

        // Always try to read the persisted default, regardless of
        // enumerateDevices success. This way, even if enumeration
        // rejects (e.g. permission denied, secure-context issue), the
        // user's previously-saved device id is at least available for
        // a later restore attempt.
        if (window.electronAPI && typeof window.electronAPI.getAudioDevices === 'function') {
            window.electronAPI.getAudioDevices().then(function(result) {
                if (result && result.ok && result.data && result.data.defaultDeviceId) {
                    persistedDefault = result.data.defaultDeviceId;
                    applyPersistedDefault();
                }
            }).catch(function(err) {
                console.warn('getAudioDevices failed:', err);
            });
        }

        if (!navigator.mediaDevices || typeof navigator.mediaDevices.enumerateDevices !== 'function') {
            // No MediaDevices support — apply whatever persisted
            // default we already have, then leave only the "default"
            // option visible.
            console.warn('navigator.mediaDevices is unavailable; device selector will only have the default option');
            applyPersistedDefault();
            return;
        }

        // Enumeration is async; failures are non-fatal. We still
        // apply the persisted default after enumeration completes
        // (the option list may now contain a matching device).
        navigator.mediaDevices.enumerateDevices().then(function(devices) {
            devices.forEach(function(d) {
                if (d.kind === 'audiooutput') {
                    var label = d.label || ('Audio output ' + d.deviceId.slice(0, 6));
                    addOption(d.deviceId, label);
                }
            });
            applyPersistedDefault();
        }).catch(function(err) {
            console.warn('enumerateDevices failed:', err);
            applyPersistedDefault();
        });
    }

    // Track which play button was last clicked so the persisted
    // jukebox state always points at the most recently played
    // resource. Implemented as additional click listeners (not
    // modifications of the existing handlers) per Phase 1 no-touch
    // constraint.
    ['btn-forest', 'btn-battle', 'btn-menu'].forEach(function(id) {
        var el = document.getElementById(id);
        if (!el) return;
        el.addEventListener('click', function() {
            lastTrackId = id.replace('btn-', '');
            saveJukeboxState();
        });
    });
    ['btn-sfx-click', 'btn-sfx-explosion', 'btn-sfx-gunshot', 'btn-sfx-footstep'].forEach(function(id) {
        var el = document.getElementById(id);
        if (!el) return;
        el.addEventListener('click', function() {
            lastTrackId = id.replace('btn-', '').replace('sfx-', 'sfx.');
            saveJukeboxState();
        });
    });

    // Delegated save listeners: every control that affects the
    // persisted jukebox UI state fires saveJukeboxState() on input
    // / change / click. We use additional listeners so the existing
    // event handlers (and their bug fixes B1–B6) are preserved.
    ['global-volume', 'music-volume', 'sfx-volume', 'default-pan', 'default-speed'].forEach(function(id) {
        var el = document.getElementById(id);
        if (!el) return;
        el.addEventListener('input', saveJukeboxState);
        el.addEventListener('change', saveJukeboxState);
    });
    ['fade-in-ms', 'fade-out-ms'].forEach(function(id) {
        var el = document.getElementById(id);
        if (!el) return;
        el.addEventListener('input', saveJukeboxState);
        el.addEventListener('change', saveJukeboxState);
    });
    ['btn-music-mute', 'btn-sfx-mute', 'btn-loop-mode'].forEach(function(id) {
        var el = document.getElementById(id);
        if (!el) return;
        el.addEventListener('click', saveJukeboxState);
    });

    // Device-selector change: persist the chosen id. The actual
    // AudioContext.setSinkId() call must happen in the Kotlin/JS
    // engine on the next play — out of scope for Phase 3.
    var deviceSelectorEl = document.getElementById('device-selector');
    if (deviceSelectorEl) {
        deviceSelectorEl.addEventListener('change', function() {
            if (window.electronAPI && typeof window.electronAPI.setDefaultDevice === 'function') {
                window.electronAPI.setDefaultDevice(deviceSelectorEl.value).then(function(result) {
                    if (!result || !result.ok) {
                        console.warn('setDefaultDevice failed:', result && result.error);
                    }
                }).catch(function(err) {
                    console.warn('setDefaultDevice rejected:', err);
                });
            }
        });
    }

    // Subscribe to the View > Stop All Audio menu event from the
    // main process. Forward to the existing stopAll() function.
    if (window.electronAPI && typeof window.electronAPI.onStopAllAudio === 'function') {
        window.electronAPI.onStopAllAudio(function() {
            stopAll();
        });
    }

    // Populate device selector + restore persisted state on init.
    // populateDeviceSelector must run before restoreJukeboxState's
    // async getAudioDevices call resolves; both are async, but the
    // ordering is robust because restoreJukeboxState does not
    // touch the device-selector <select>.
    populateDeviceSelector();
    restoreJukeboxState();

    console.log('Jukebox UI initialized');
})();
