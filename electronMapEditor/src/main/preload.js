const { contextBridge, ipcRenderer } = require("electron")

contextBridge.exposeInMainWorld("AutogenesisMapEditor", {
    openImage: () => ipcRenderer.invoke("map-editor:open-image"),
    openPack: () => ipcRenderer.invoke("map-editor:open-pack"),
    savePack: (bytes, defaultName) =>
        ipcRenderer.invoke("map-editor:save-pack", {
            bytes: bytes,
            defaultName: defaultName
        })
})
