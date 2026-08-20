const { contextBridge, ipcRenderer } = require("electron")

contextBridge.exposeInMainWorld("electronAPI", {
  log: (message) => ipcRenderer.invoke("jukebox-log", message),
  onServerReady: (callback) => ipcRenderer.invoke("onServerReady", callback),
  getServerStatus: () => ipcRenderer.invoke("getServerStatus")
})

contextBridge.exposeInMainWorld("jukebox", {
  // JavaScript APIs for the web UI can be added here
  version: "0.1.0"
})
