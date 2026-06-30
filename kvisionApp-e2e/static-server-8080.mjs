#!/usr/bin/env node
// Tiny static server that serves the production kvisionApp bundle on port
// 8080. Used as a fallback when `:kvisionApp:jsBrowserDevelopmentRun`
// fails to start (webpack-cli SyntaxError on Node 22+). The production
// bundle at kvisionApp/build/dist/js/productionExecutable/index.html
// contains the exact same JS as the dev webpack output (minus HMR),
// which is sufficient for the resume/push e2e probes that only need
// the WebSocket bridge + RPC client + DOM widgets — not live reload.
import { createServer } from 'node:http'
import { readFile, stat } from 'node:fs/promises'
import { extname, join, normalize } from 'node:path'

// Primary dist dir (Kotlin/JS webpack output).
const DIST_DIR = join(import.meta.dirname, '..', 'kvisionApp/build/dist/js/productionExecutable')'
// Resources that must be served at well-known paths (/sw.js, /vapid_public.json,
// /manifest.webmanifest) for the Web Push pipeline to work. The production
// webpack output may tree-shake the PushNotificationService references AND
// drop these asset copies if the pwa-push.js webpack hook didn't fire
// (production builds often skip dev-only plugins). We overlay them manually
// from the build/processedResources + build/generated trees.
const PROCESSED_RESOURCES_DIR = join(import.meta.dirname, '..', 'kvisionApp/build/processedResources/js/main')'
const GENERATED_VAPID_DIR = join(import.meta.dirname, '..', 'kvisionApp/build/generated/vapid')
const HOST = '127.0.0.1'
const PORT = parseInt(process.env.STATIC_PORT || '8080', 10)

// Map of well-known path → absolute filesystem path. Checked before the
// dist-dir lookup so /sw.js, /manifest.webmanifest, /vapid_public.json
// resolve correctly even if they're missing from the webpack output.
const KNOWN_ASSETS = {
    '/sw.js': join(PROCESSED_RESOURCES_DIR, 'sw.js'),
    '/manifest.webmanifest': join(PROCESSED_RESOURCES_DIR, 'manifest.webmanifest'),
    '/vapid_public.json': join(GENERATED_VAPID_DIR, 'vapid_public.json'),
}

const MIME = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.map': 'application/json; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.svg': 'image/svg+xml',
    '.wasm': 'application/wasm',
    '.txt': 'text/plain; charset=utf-8',
}

async function serveStatic(req, res) {
    let urlPath = decodeURIComponent(new URL(req.url, 'http://x').pathname)
    if (urlPath === '/') urlPath = '/index.html'

    // Well-known PWA / push paths are overlaid from build/processedResources
    // and build/generated — the production webpack output often drops them.
    let filePath
    let allowedPrefixes
    const knownAsset = KNOWN_ASSETS[urlPath]
    if (knownAsset) {
        filePath = knownAsset
        allowedPrefixes = [PROCESSED_RESOURCES_DIR, GENERATED_VAPID_DIR]
    } else {
        filePath = normalize(join(DIST_DIR, urlPath))
        allowedPrefixes = [DIST_DIR]
    }
    if (!allowedPrefixes.some(p => filePath.startsWith(p))) {
        res.statusCode = 403
        return res.end('forbidden')
    }
    try {
        const s = await stat(filePath)
        if (!s.isFile()) {
            res.statusCode = 404
            return res.end('not found')
        }
        const body = await readFile(filePath)
        res.setHeader('content-type', MIME[extname(filePath).toLowerCase()] || 'application/octet-stream')
        res.setHeader('content-length', body.length)
        res.setHeader('cache-control', 'no-store')
        res.end(body)
    } catch (err) {
        if (err.code === 'ENOENT') {
            res.statusCode = 404
            return res.end('not found')
        }
        throw err
    }
}

const server = createServer((req, res) => {
    serveStatic(req, res).catch(err => {
        console.error('static server error:', err)
        res.statusCode = 500
        res.end(String(err?.message ?? err))
    })
})

server.listen(PORT, HOST, () => {
    console.log(`static kvisionApp server: http://${HOST}:${PORT} → ${DIST_DIR}`)
})

for (const sig of ['SIGINT', 'SIGTERM']) {
    process.on(sig, () => { server.close(); process.exit(0) })
}