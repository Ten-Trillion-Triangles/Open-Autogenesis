const { spawnSync } = require("child_process")
const path = require("path")

function commandExists(cmd) {
  try {
    const result = spawnSync("which", [cmd], { stdio: "ignore" })
    return result.status === 0
  } catch {
    return false
  }
}

const targets = ["dir", "AppImage", "deb"]
if (commandExists("rpmbuild")) {
  targets.push("rpm")
} else {
  console.warn("rpmbuild not found; skipping RPM installer.")
}

const args = ["--linux", ...targets, "--config", "electron-builder.yml"]
const result = spawnSync("npx", ["electron-builder", ...args], {
  stdio: "inherit",
  cwd: path.resolve(__dirname, "..")
})

if (result.error) {
  console.error("Failed to spawn electron-builder:", result.error)
  process.exit(1)
}
process.exit(result.status)
