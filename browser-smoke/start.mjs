import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { access, readFile, stat } from 'node:fs/promises'
import { constants as fsConstants } from 'node:fs'
import { extname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'

const rootDir = fileURLToPath(new URL('..', import.meta.url))
const webpackDir = join(rootDir, 'kvisionApp', 'build', 'kotlin-webpack', 'js', 'productionExecutable')
const distDir = join(rootDir, 'kvisionApp', 'build', 'dist', 'js', 'productionExecutable')
const host = '127.0.0.1'
const port = 4173
const serverExtendReadyUrl = 'http://127.0.0.1:7070/player'
const serverExtendGrpcPort = 9092

let gradleProcess = null
let staticServer = null

async function main()
{
    await ensureServerExtend()
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

async function ensureServerExtend()
{
    gradleProcess = spawn(gradleCommand(), [':server-extend:run'], {
        cwd: rootDir,
        stdio: 'inherit'
    })

    const deadline = Date.now() + 180_000
    while(Date.now() < deadline)
    {
        if(await isHttpReady(serverExtendReadyUrl))
        {
            if(await isTcpReady(serverExtendGrpcPort))
            {
                return
            }
        }

        await sleep(1000)
    }

    throw new Error('server-extend did not become ready in time')
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

        const contentType = contentTypeFor(filePath)
        res.statusCode = 200
        res.setHeader('content-type', contentType)
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
        normalize(join(webpackDir, requestPath)),
        normalize(join(distDir, requestPath))
    ]

    for(const candidate of candidates)
    {
        if(!candidate.startsWith(normalize(webpackDir)) && !candidate.startsWith(normalize(distDir)))
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
        case '.js':
            return 'application/javascript; charset=utf-8'
        case '.css':
            return 'text/css; charset=utf-8'
        case '.json':
            return 'application/json; charset=utf-8'
        case '.svg':
            return 'image/svg+xml'
        case '.png':
            return 'image/png'
        case '.map':
            return 'application/json; charset=utf-8'
        case '.html':
        default:
            return 'text/html; charset=utf-8'
    }
}

async function isHttpReady(url)
{
    try
    {
        const response = await fetch(url)
        return response.ok
    }
    catch
    {
        return false
    }
}

async function isTcpReady(port)
{
    try
    {
        const net = await import('node:net')
        return await new Promise(resolve => {
            const socket = net.createConnection(port, host)
            const finish = value => {
                socket.removeAllListeners()
                socket.destroy()
                resolve(value)
            }
            socket.once('connect', () => finish(true))
            socket.once('error', () => finish(false))
        })
    }
    catch
    {
        return false
    }
}

function gradleCommand()
{
    return process.platform === 'win32' ? 'gradlew.bat' : './gradlew'
}

function sleep(ms)
{
    return new Promise(resolve => setTimeout(resolve, ms))
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