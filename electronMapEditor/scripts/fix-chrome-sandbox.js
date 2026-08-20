const fs = require("fs")
const path = require("path")

// electron-builder afterPack hook: applies the SUID bit to chrome-sandbox
// inside the unpacked linux-x64 build directory so the AppImage can launch.
module.exports = async function fixChromeSandbox(context) {
    const sandboxPath = path.join(context.appOutDir, "chrome-sandbox")
    try {
        if (fs.existsSync(sandboxPath)) {
            fs.chmodSync(sandboxPath, 0o4755)
            console.log(`Set SUID on ${sandboxPath}`)
        }
    } catch (error) {
        console.warn("Failed to adjust chrome-sandbox permissions", error)
    }
}
