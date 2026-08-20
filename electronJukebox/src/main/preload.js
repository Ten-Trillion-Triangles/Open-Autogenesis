/**
 * Electron preload script for the Autogenesis Jukebox.
 *
 * Runs in a sandboxed renderer context. The only Node-style module
 * available is `electron` itself (exposes contextBridge, ipcRenderer,
 * and webFrame). No `require('fs')`, `require('path')`, etc.
 *
 * Exposes a small, typed surface on `window.electronAPI` to the renderer.
 * All IPC methods resolve to a unified envelope shape:
 *
 *   { ok: true, data?: any }   on success
 *   { ok: false, error: string } on failure
 *
 * Concretely:
 *   - getAudioDevices()
 *       → { ok: true, data: { defaultDeviceId: string } } | { ok: false, error }
 *   - setDefaultDevice(id)
 *       → { ok: true } | { ok: false, error }
 *   - getWindowState()
 *       → { ok: true, data: <stateObject> } | { ok: false, error }
 *   - saveWindowState(state)
 *       → { ok: true } | { ok: false, error }
 *   - onStopAllAudio(callback)
 *       → subscribes to the View > Stop All menu (no envelope)
 *
 * The unsubscribe function returned by onStopAllAudio is good hygiene
 * for the renderer to use if it wants to tear down listeners; the
 * current jukebox code does not.
 */

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  getAudioDevices: () => ipcRenderer.invoke('get-audio-devices'),
  setDefaultDevice: (deviceId) => ipcRenderer.invoke('set-default-device', deviceId),
  getWindowState: () => ipcRenderer.invoke('get-window-state'),
  saveWindowState: (state) => ipcRenderer.invoke('save-window-state', state),
  onStopAllAudio: (callback) => {
    const handler = () => callback();
    ipcRenderer.on('jukebox-stop-all', handler);
    return () => ipcRenderer.removeListener('jukebox-stop-all', handler);
  },
});