const fs = require("fs")
const path = require("path")

module.exports = async function fixChromeSandbox(context) {
  const sandboxPath = path.join(context.appOutDir, "chrome-sandbox")
  try {
    if (fs.existsSync(sandboxPath)) {
      fs.chmodSync(sandboxPath, 0o4755)
    }
  } catch (error) {
    console.warn("Failed to adjust chrome-sandbox permissions", error)
  }
}
