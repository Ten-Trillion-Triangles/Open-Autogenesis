const path = require("path")
const fsp = require("fs/promises")
const { app, BrowserWindow, dialog, ipcMain, Menu, MenuItem } = require("electron")
const log = require("electron-log")

const isDev = !app.isPackaged
const resourcesPath = isDev ? path.join(__dirname, "..", "..", "build", "staging") : process.resourcesPath
const frontendHtml = path.join(resourcesPath, "frontend", "index.html")

let mainWindow

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 1600,
        height: 900,
        title: "Autogenesis Map Editor",
        webPreferences: {
            preload: path.join(__dirname, "preload.js"),
            contextIsolation: true,
            nodeIntegration: false,
            spellcheck: true
        }
    })

    mainWindow.webContents.on("context-menu", (event, params) => {
        const menu = new Menu()

        for (const suggestion of params.dictionarySuggestions) {
            menu.append(new MenuItem({
                label: suggestion,
                click: () => mainWindow.webContents.replaceMisspelling(suggestion)
            }))
        }

        if (params.misspelledWord) {
            menu.append(new MenuItem({
                label: "Add to Dictionary",
                click: () => mainWindow.webContents.session.addWordToSpellCheckerDictionary(params.misspelledWord)
            }))
        }

        if (params.dictionarySuggestions.length > 0 || params.misspelledWord) {
            menu.append(new MenuItem({ type: "separator" }))
        }

        if (params.isEditable) {
            menu.append(new MenuItem({ role: "cut" }))
            menu.append(new MenuItem({ role: "copy" }))
            menu.append(new MenuItem({ role: "paste" }))
            menu.append(new MenuItem({ type: "separator" }))
            menu.append(new MenuItem({ role: "selectAll" }))
        } else if (params.selectionText) {
            menu.append(new MenuItem({ role: "copy" }))
        }

        if (menu.items.length > 0) {
            menu.popup()
        }
    })

    mainWindow.loadFile(frontendHtml)
}

ipcMain.handle("map-editor:open-image", async () => {
    if (!mainWindow) return null
    const result = await dialog.showOpenDialog(mainWindow, {
        title: "Load Map Image",
        properties: ["openFile"],
        filters: [{ name: "Images", extensions: ["png", "jpg", "jpeg", "webp"] }]
    })
    if (result.canceled || result.filePaths.length === 0) return null
    const filePath = result.filePaths[0]
    const bytes = await fsp.readFile(filePath)
    log.info(`Loaded image ${filePath} (${bytes.length} bytes)`)
    return { name: path.basename(filePath), bytes: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) }
})

ipcMain.handle("map-editor:open-pack", async () => {
    if (!mainWindow) return null
    const result = await dialog.showOpenDialog(mainWindow, {
        title: "Load Map Pack",
        properties: ["openFile"],
        filters: [{ name: "Map Pack", extensions: ["map", "zip"] }]
    })
    if (result.canceled || result.filePaths.length === 0) return null
    const filePath = result.filePaths[0]
    const bytes = await fsp.readFile(filePath)
    log.info(`Loaded pack ${filePath} (${bytes.length} bytes)`)
    return { name: path.basename(filePath), bytes: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) }
})

ipcMain.handle("map-editor:save-pack", async (_event, payload) => {
    if (!mainWindow) return false
    const defaultName = (payload && payload.defaultName) || "map-pack.map"
    const result = await dialog.showSaveDialog(mainWindow, {
        title: "Export Map Pack",
        defaultPath: defaultName,
        filters: [{ name: "Map Pack", extensions: ["map"] }]
    })
    if (result.canceled || !result.filePath) return false
    const bytes = Buffer.from(payload.bytes)
    await fsp.writeFile(result.filePath, bytes)
    log.info(`Saved pack to ${result.filePath} (${bytes.length} bytes)`)
    return true
})

app.on("ready", () => {
    createWindow()
})

app.on("window-all-closed", () => {
    app.quit()
})

app.on("activate", () => {
    if (!mainWindow) {
        createWindow()
    }
})
