const { contextBridge, ipcRenderer } = require("electron")

contextBridge.exposeInMainWorld("AutogenesisElectron", {
  localServerUrl: "http://127.0.0.1:7070",
  mainServerUrl: "http://127.0.0.1:9080",
  shutdown: () => ipcRenderer.invoke("autogenesis-shutdown")
})
