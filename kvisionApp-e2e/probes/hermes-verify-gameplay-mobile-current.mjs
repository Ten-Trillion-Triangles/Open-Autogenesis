#!/usr/bin/env node
import { chromium } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const probeDirectory = join(fileURLToPath(new URL('.', import.meta.url)), 'artifacts-gameplay-mobile-current')
const screenshotDirectory = '/home/cage/Desktop/Workspaces/Autogenesis/screenshots/2026-07-18-gameplay-mobile-adaptation'
await mkdir(probeDirectory, { recursive: true })
await mkdir(screenshotDirectory, { recursive: true })

const assetRoots = [
    '/home/cage/Desktop/Workspaces/Autogenesis/Autogenesis/kvisionApp/build/processedResources/js/main',
    '/home/cage/Desktop/Workspaces/Autogenesis/Autogenesis/kvisionApp/src/jsMain/resources',
    '/home/cage/Desktop/Workspaces/Autogenesis/Autogenesis/server/src/main/resources',
]
const assetSuffixes = ['.mp3', '.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp', '.woff', '.woff2', '.ttf', '.json', '.css', '.webmanifest', '.map']

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
await context.route('**/*', async route => {
    const requestUrl = new URL(route.request().url())
    const pathname = decodeURIComponent(requestUrl.pathname)
    if (!assetSuffixes.some(suffix => pathname.endsWith(suffix))) {
        await route.continue()
        return
    }
    const relativePath = pathname.replace(/^\//, '')
    for (const root of assetRoots) {
        const candidate = join(root, relativePath)
        if (existsSync(candidate)) {
            const body = readFileSync(candidate)
            await route.fulfill({ status: 200, body, headers: { 'content-type': requestUrl.pathname.endsWith('.mp3') ? 'audio/mpeg' : 'application/octet-stream', 'cache-control': 'no-cache' } })
            return
        }
    }
    await route.continue()
})
const page = await context.newPage()
const consoleMessages = []
const pageErrors = []
page.on('console', message => consoleMessages.push({ type: message.type(), text: message.text().slice(0, 500) }))
page.on('pageerror', error => pageErrors.push(error.message))
await page.goto('http://127.0.0.1:8080/index.html?skipLogin=true&testMode=true&demoMode=FULL&bootWidget=GameplayUI', { waitUntil: 'domcontentloaded' })
await page.getByTestId('loading-screen-cta').click({ timeout: 15000 })
await page.waitForFunction(() => !!document.querySelector('[data-testid="gameplay-ui"]'), { timeout: 90000 })
await page.waitForTimeout(1200)
const state = await page.evaluate(() => {
    const rectData = element => {
        if (!element) return null
        const rect = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        return {
            tag: element.tagName,
            className: typeof element.className === 'string' ? element.className : String(element.className),
            id: element.id,
            text: (element.innerText || '').slice(0, 120),
            rect: { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height },
            style: { display: style.display, position: style.position, flexDirection: style.flexDirection, gridTemplateColumns: style.gridTemplateColumns, width: style.width, height: style.height, minWidth: style.minWidth, maxWidth: style.maxWidth, overflow: style.overflow, zIndex: style.zIndex },
        }
    }
    const gameplay = document.querySelector('[data-testid="gameplay-ui"]')
    const named = [...document.querySelectorAll('*')].filter(element => {
        const className = typeof element.className === 'string' ? element.className : ''
        return /gameplay-ui|login-widget-window|score-bar|command-box|map-viewer-container|map-canvas|gh-header-container|gh-stack-container|action-button|btn-play/.test(className)
    }).slice(0, 80).map(rectData)
    const directChildren = gameplay ? [...gameplay.children].map(rectData) : []
    return {
        viewport: { width: window.innerWidth, height: window.innerHeight, scrollWidth: document.documentElement.scrollWidth, scrollHeight: document.documentElement.scrollHeight },
        gameplay: rectData(gameplay),
        directChildren,
        named,
        bodyChildren: [...document.body.children].map(rectData),
    }
})
await page.screenshot({ path: join(screenshotDirectory, 'current-390x844.png'), fullPage: false })
console.log(JSON.stringify({ state, consoleMessages: consoleMessages.slice(-20), pageErrors }, null, 2))
await browser.close()