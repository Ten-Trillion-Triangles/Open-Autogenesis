// Patch Playwright's pirates library to skip `.mjs` extension registration.
// Node 20+ makes `Module._extensions['.mjs']` read-only, so Playwright 1.45-1.49
// crashes during `installTransform` with `Cannot assign to read only property
// '.mjs'`. Removing `.mjs` and `.cjs` from the exts list avoids the assignment
// while keeping TS transform behavior intact. The e2e specs are authored in
// `.ts` and compiled to `.js` via tsc, so no `.mjs`/`.cjs` handling is needed.
const fs = require("fs")
const path = require("path")

const transformPath = path.join(__dirname, "..", "node_modules", "playwright", "lib", "transform", "transform.js")
if (!fs.existsSync(transformPath)) {
    console.warn("patch-playwright-pirates: transform.js not found, skipping")
    process.exit(0)
}

const original = fs.readFileSync(transformPath, "utf8")
const patched = original.replace(
    /exts:\s*\[\s*'\.ts',\s*'\.tsx',\s*'\.js',\s*'\.jsx',\s*'\.mjs',\s*'\.mts',\s*'\.cjs',\s*'\.cts'\s*\]/,
    "exts: ['.ts', '.tsx', '.js', '.jsx', '.mts', '.cts']"
)

if (patched === original) {
    console.log("patch-playwright-pirates: already patched or signature changed")
    process.exit(0)
}

fs.writeFileSync(transformPath, patched, "utf8")
console.log("patch-playwright-pirates: removed .mjs and .cjs from Playwright pirates exts list")