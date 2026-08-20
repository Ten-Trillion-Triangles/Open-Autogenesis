/**
 * Native application menu for the jukebox Electron app.
 *
 * Provides standard Edit/View/Window roles plus a jukebox-specific
 * "Stop All Audio" item under View that signals the renderer to stop
 * playback. The main process does NOT touch the audio engine directly —
 * the renderer (preload + app.js) owns the playback lifecycle, so this
 * menu merely dispatches the `jukebox-stop-all` IPC event.
 *
 * @param {import('electron').BrowserWindow} win The main window. Required so
 *   the "Stop All Audio" item can dispatch `win.webContents.send(...)` and
 *   the Reload/DevTools items can act on the same window.
 * @returns {import('electron').Menu} The built application menu.
 */

const { Menu, app, dialog } = require('electron');

function buildMenu(win) {
    const isMac = process.platform === 'darwin';

    const template = [
        // ---------------------------------------------------------------
        // File
        // ---------------------------------------------------------------
        {
            label: 'File',
            submenu: [
                isMac ? { role: 'close' } : { role: 'quit' },
            ],
        },

        // ---------------------------------------------------------------
        // Edit — standard roles so cut/copy/paste work in inputs
        // ---------------------------------------------------------------
        {
            label: 'Edit',
            submenu: [
                { role: 'undo' },
                { role: 'redo' },
                { type: 'separator' },
                { role: 'cut' },
                { role: 'copy' },
                { role: 'paste' },
                { role: 'selectAll' },
            ],
        },

        // ---------------------------------------------------------------
        // View — jukebox-specific
        // ---------------------------------------------------------------
        {
            label: 'View',
            submenu: [
                {
                    label: 'Reload',
                    accelerator: 'CmdOrCtrl+R',
                    click: () => {
                        if (win && !win.isDestroyed()) {
                            win.webContents.reload();
                        }
                    },
                },
                {
                    label: 'Toggle DevTools',
                    accelerator: 'F12',
                    click: () => {
                        if (win && !win.isDestroyed()) {
                            win.webContents.toggleDevTools();
                        }
                    },
                },
                // Hidden duplicate so the standard Ctrl+Shift+I / Cmd+Shift+I
                // shortcut also toggles DevTools. The menu item is not shown
                // in the menu bar (F12 is the documented accelerator) but its
                // click handler is still registered with the accelerator table.
                {
                    label: 'Toggle DevTools (Shift+I)',
                    accelerator: 'CmdOrCtrl+Shift+I',
                    visible: false,
                    click: () => {
                        if (win && !win.isDestroyed()) {
                            win.webContents.toggleDevTools();
                        }
                    },
                },
                { type: 'separator' },
                {
                    label: 'Stop All Audio',
                    accelerator: 'CmdOrCtrl+.',
                    click: () => {
                        // The renderer owns the audio engine. Signal it to
                        // stop everything. The preload script (Phase 3)
                        // registers a listener for `jukebox-stop-all` and
                        // calls the jukebox engine's stopAll(). The send can
                        // throw if the window or webContents has already been
                        // torn down — ignore that case.
                        if (win && !win.isDestroyed()) {
                            try {
                                win.webContents.send('jukebox-stop-all');
                            } catch (_sendErr) {
                                // ignore — window is going away
                            }
                        }
                    },
                },
                { type: 'separator' },
                { role: 'resetZoom' },
                { role: 'zoomIn' },
                { role: 'zoomOut' },
                { type: 'separator' },
                { role: 'togglefullscreen' },
            ],
        },

        // ---------------------------------------------------------------
        // Window
        // ---------------------------------------------------------------
        {
            label: 'Window',
            submenu: [
                { role: 'minimize' },
                { role: 'zoom' },
                ...(isMac
                    ? [
                          { type: 'separator' },
                          { role: 'front' },
                      ]
                    : [{ role: 'close' }]),
            ],
        },

        // ---------------------------------------------------------------
        // Help
        // ---------------------------------------------------------------
        {
            role: 'help',
            submenu: [
                {
                    label: 'About Autogenesis Jukebox',
                    click: () => {
                        const productName = app.getName();
                        const dialogOptions = {
                            type: 'info',
                            title: 'About ' + productName,
                            message: productName,
                            detail:
                                'Version: ' + app.getVersion() + '\n' +
                                'AppId: org.ttt.autogenesis.jukebox\n' +
                                'Audio system developer test tool.\n' +
                                'Standalone desktop launcher for the jukebox audio engine.',
                            buttons: ['OK'],
                        };
                        if (win && !win.isDestroyed()) {
                            dialog.showMessageBox(win, dialogOptions);
                        } else {
                            dialog.showMessageBox(dialogOptions);
                        }
                    },
                },
            ],
        },
    ];

    return Menu.buildFromTemplate(template);
}

module.exports = { buildMenu };