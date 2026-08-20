import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { access, readFile, stat } from 'node:fs/promises'
import { constants as fsConstants } from 'node:fs'
import { extname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'

const rootDir = fileURLToPath(new URL('..', import.meta.url))
// The Kotlin/JS plugin puts the production build under build/dist/js/productionExecutable/
const distDir = join(rootDir, 'kvisionApp', 'build', 'dist', 'js', 'productionExecutable')
const webpackDir = join(rootDir, 'kvisionApp', 'build', 'kotlin-webpack', 'js', 'productionExecutable')
const host = '127.0.0.1'
const port = 4175

let gradleProcess = null
let staticServer = null

async function main()
{
    // Build the production bundle so the dist directory is populated
    await runGradleBuild()
    // Verify the dist directory has an index.html
    await ensureDistBuilt()

    staticServer = createServer((req, res) => {
        serveStatic(req, res).catch(err => {
            res.statusCode = 500
            res.setHeader('content-type', 'text/plain; charset=utf-8')
            res.end(String(err?.message ?? err))
        })
    })

    await new Promise((resolve, reject) => {
        staticServer.once('error', reject)
        staticServer.listen(port, host, resolve)
    })
    console.log(`kvisionApp-e2e: static server listening at http://${host}:${port}`)

    process.on('SIGINT', shutdown)
    process.on('SIGTERM', shutdown)
    if(gradleProcess)
    {
        gradleProcess.on('exit', code => {
            if(code && code !== 0)
            {
                process.exitCode = code
            }
            shutdown()
        })
    }
}

function runGradleBuild()
{
    return new Promise((resolve, reject) => {
        console.log('kvisionApp-e2e: building production bundle via :kvisionApp:jsBrowserDistribution')
        const child = spawn(gradleCommand(), [':kvisionApp:jsBrowserDistribution', '--no-daemon'], {
            cwd: rootDir,
            stdio: 'inherit'
        })
        gradleProcess = child
        child.on('exit', code => {
            gradleProcess = null
            if(code === 0)
            {
                resolve()
            }
            else
            {
                reject(new Error(`gradle build failed with exit code ${code}`))
            }
        })
    })
}

async function ensureDistBuilt()
{
    try
    {
        await access(join(distDir, 'index.html'), fsConstants.R_OK)
    }
    catch
    {
        throw new Error(`expected dist build at ${distDir}/index.html but it is missing — did the gradle build succeed?`)
    }
}

async function serveStatic(req, res)
{
    const url = new URL(req.url, `http://${host}:${port}`)
    const requestPath = url.pathname === '/' ? '/index.html' : url.pathname
    const filePath = await resolveStaticFile(requestPath)

    if(!filePath)
    {
        res.statusCode = 403
        res.end('forbidden')
        return
    }

    try
    {
        const stats = await stat(filePath)
        if(stats.isDirectory())
        {
            return serveIndex(res)
        }
        res.statusCode = 200
        res.setHeader('content-type', contentTypeFor(filePath))
        res.end(await readFile(filePath))
    }
    catch
    {
        return serveIndex(res)
    }
}

async function serveIndex(res)
{
    const filePath = join(distDir, 'index.html')
    res.statusCode = 200
    res.setHeader('content-type', 'text/html; charset=utf-8')
    res.end(await readFile(filePath))
}

async function resolveStaticFile(requestPath)
{
    const candidates = [
        normalize(join(distDir, requestPath)),
        normalize(join(webpackDir, requestPath))
    ]
    for(const candidate of candidates)
    {
        if(!candidate.startsWith(normalize(distDir)) && !candidate.startsWith(normalize(webpackDir)))
        {
            continue
        }
        try
        {
            await access(candidate, fsConstants.R_OK)
            return candidate
        }
        catch
        {
            continue
        }
    }
    return null
}

function contentTypeFor(filePath)
{
    switch(extname(filePath))
    {
        case '.js': return 'application/javascript; charset=utf-8'
        case '.css': return 'text/css; charset=utf-8'
        case '.json': return 'application/json; charset=utf-8'
        case '.svg': return 'image/svg+xml'
        case '.png': return 'image/png'
        case '.map': return 'application/json; charset=utf-8'
        case '.html':
        default: return 'text/html; charset=utf-8'
    }
}

function gradleCommand()
{
    return process.platform === 'win32' ? 'gradlew.bat' : './gradlew'
}

async function shutdown()
{
    if(staticServer)
    {
        await new Promise(resolve => staticServer.close(resolve))
        staticServer = null
    }
    if(gradleProcess && !gradleProcess.killed)
    {
        gradleProcess.kill('SIGTERM')
        gradleProcess = null
    }
    process.exit()
}

main().catch(err => {
    console.error(err)
    process.exit(1)
})