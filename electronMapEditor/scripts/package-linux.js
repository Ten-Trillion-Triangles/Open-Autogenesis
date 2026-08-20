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

// .deb / .rpm packaging is intentionally disabled because fpm's
// data.tar.xz compression step pipes through node_modules/app-builder-bin
// which is not a valid xz implementation, producing a 0-byte archive
// and exit 2. The operator-side delivery surface is the AppImage.
// To re-enable, set the conditional to `commandExists("rpmbuild")` and
// add "deb" back to the targets list. Mirrors electronApp/scripts/package-linux.js.
const targets = ["dir", "AppImage"]
if (commandExists("rpmbuild") && false) {
    targets.push("rpm")
} else {
    console.warn("deb/rpm packaging disabled; building dir + AppImage only.")
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
