const path = require("path")
const os = require("os")
const { app, BrowserWindow, ipcMain, Menu, MenuItem } = require("electron")
const { spawn, spawnSync } = require("child_process")
const waitPort = require("wait-port")
const log = require("electron-log")

const isWindows = process.platform === "win32"
const runtimeNames = {
  server: isWindows ? "server-windows-x64" : "server-linux-x64",
  extend: isWindows ? "server-extend-windows-x64" : "server-extend-linux-x64"
}

const isDev = !app.isPackaged
const resourcesPath = isDev ? path.join(__dirname, "..", "..", "build", "staging") : process.resourcesPath
const runtimeRoot = path.join(resourcesPath, "runtime")
const frontendHtml = path.join(resourcesPath, "frontend", "index.html")
const serverProcesses = []
let mainWindow
let xwaylandProcess

function spawnRuntime(name, executableName) {
  const runtimeDir = path.join(runtimeRoot, runtimeNames[name])
  const executable = path.join(runtimeDir, "bin", executableName)
  const spawnOptions = {
    cwd: path.dirname(executable),
    env: { ...process.env }
  }
  if (isWindows) {
    spawnOptions.shell = true
  }

  log.info(`Starting ${name} (${executable})`)
  const child = spawn(executable, [], spawnOptions)
  child.stdout.on("data", (data) => {
    log.info(`[${name}] ${data.toString().trim()}`)
  })
  child.stderr.on("data", (data) => {
    log.error(`[${name}] ${data.toString().trim()}`)
  })
  child.on("exit", (code, signal) => {
    log.info(`${name} exited (code=${code} signal=${signal})`)
  })
  serverProcesses.push(child)
  return child
}

function commandAvailable(command) {
  try {
    const result = spawnSync("which", [command], { stdio: "ignore" })
    return result.status === 0
  } catch {
    return false
  }
}

function ensureDisplayEnvironment() {
  if (process.platform !== "linux") {
    return
  }

  if (process.env.DISPLAY || process.env.WAYLAND_DISPLAY) {
    return
  }

  if (!commandAvailable("Xwayland")) {
    log.warn("Xwayland missing; display may not be available when launching Electron.")
    return
  }

  log.info("Launching Xwayland fallback on DISPLAY=:1")
  xwaylandProcess = spawn("Xwayland", [":1", "-rootless", "-terminate"], {
    detached: true,
    stdio: "ignore"
  })
  xwaylandProcess.unref()
  process.env.DISPLAY = ":1"
}

async function waitForServices() {
  const endpoints = [
    { host: "127.0.0.1", port: 9080 },
    { host: "127.0.0.1", port: 7070 }
  ]
  for (const endpoint of endpoints) {
    const ready = await waitPort({ ...endpoint, timeout: 60000, output: "silent" })
    if (!ready) {
      throw new Error(`Port ${endpoint.host}:${endpoint.port} did not become available in time`)
    }
  }
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1600,
    height: 900,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      spellcheck: true
    }
  })

  mainWindow.webContents.on("context-menu", (event, params) => {
    const menu = new Menu()

    // Add spelling suggestions
    for (const suggestion of params.dictionarySuggestions) {
      menu.append(new MenuItem({
        label: suggestion,
        click: () => mainWindow.webContents.replaceMisspelling(suggestion)
      }))
    }

    // Add "Add to Dictionary" if a word is misspelled
    if (params.misspelledWord) {
      menu.append(new MenuItem({
        label: "Add to Dictionary",
        click: () => mainWindow.webContents.session.addWordToSpellCheckerDictionary(params.misspelledWord)
      }))
    }

    if (params.dictionarySuggestions.length > 0 || params.misspelledWord) {
      menu.append(new MenuItem({ type: "separator" }))
    }

    // Standard editing actions
    if (params.isEditable) {
      menu.append(new MenuItem({ role: "cut" }))
      menu.append(new MenuItem({ role: "copy" }))
      menu.append(new MenuItem({ role: "paste" }))
      menu.append(new MenuItem({ type: "separator" }))
      menu.append(new MenuItem({ role: "selectAll" }))
    } else if (params.selectionText) {
      menu.append(new MenuItem({ role: "copy" }))
    }

    // Only show the menu if it has items
    if (menu.items.length > 0) {
      menu.popup()
    }
  })

  mainWindow.loadFile(frontendHtml)
}

async function start() {
  try {
    ensureDisplayEnvironment()
    spawnRuntime("server", isWindows ? "autogenesis-server.bat" : "autogenesis-server")
    spawnRuntime("extend", isWindows ? "autogenesis-server-extend.bat" : "autogenesis-server-extend")
    await waitForServices()
    createWindow()
  } catch (error) {
    log.error("Electron failed to start runtime services:", error)
    app.quit()
  }
}

let serversShutDown = false
function shutdownServers() {
  if (serversShutDown) return
  serversShutDown = true
  serverProcesses.forEach((child) => {
    if (child.killed) return
    if (isWindows) {
      try { spawnSync("taskkill", ["/pid", String(child.pid), "/T", "/F"]) } catch (_) {}
    } else {
      child.kill("SIGTERM")
      setTimeout(() => { if (!child.killed) child.kill("SIGKILL") }, 5000)
    }
  })
}

function cleanupDisplay() {
  if (xwaylandProcess && !xwaylandProcess.killed) {
    xwaylandProcess.kill()
  }
}

app.on("ready", () => {
  start()
})

app.on("before-quit", () => {
  shutdownServers()
  cleanupDisplay()
})

app.on("window-all-closed", () => {
  app.quit()
})

app.on("activate", () => {
  if (!mainWindow) {
    createWindow()
  }
})

ipcMain.handle("autogenesis-shutdown", () => {
  shutdownServers()
  app.quit()
})

process.on("exit", shutdownServers)
