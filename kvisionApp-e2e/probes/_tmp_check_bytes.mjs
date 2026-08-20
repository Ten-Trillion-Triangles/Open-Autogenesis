import { chromium } from 'playwright'
const browser = await chromium.launch({ headless: true })
const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } })
const page = await ctx.newPage()
await page.goto('http://localhost:8080/?skipLogin=true', { waitUntil: 'load' })
await page.waitForTimeout(4000)
const cta = await page.getByTestId('loading-screen-cta').count()
if (cta > 0) { await page.getByTestId('loading-screen-cta').click(); await page.waitForTimeout(2000) }
await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible' })
await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
        if (b.textContent.trim() === 'Collection') { b.click(); return }
    }
})
await page.waitForTimeout(2500)
await page.evaluate(() => {
    for (const t of document.querySelectorAll('.collection-tab-button')) {
        if (t.title === 'Maps') { t.click(); return }
    }
})
await page.waitForTimeout(5000)
await page.waitForTimeout(5000)

// Hijack MapPackManager.unpack to capture the input bytes
await page.evaluate(() => {
    window.__capturedUnpackInputs = []
    window.__capturedImageBytes = []
    const origUnpack = window.structs?.MapPackManager?.unpack
    if (origUnpack) {
        window.structs.MapPackManager.unpack = async function(packBytes) {
            window.__capturedUnpackInputs.push(Array.from(packBytes))
            const result = await origUnpack.call(this, packBytes)
            window.__capturedImageBytes.push(Array.from(result.imageBytes))
            return result
        }
    }
})

// Trigger a re-fetch by clearing the maps tab and reselecting
await page.evaluate(() => {
    for (const t of document.querySelectorAll('.collection-tab-button')) {
        if (t.title === 'Commanders') { t.click(); return }
    }
})
await page.waitForTimeout(1000)
await page.evaluate(() => {
    for (const t of document.querySelectorAll('.collection-tab-button')) {
        if (t.title === 'Maps') { t.click(); return }
    }
})
await page.waitForTimeout(5000)

// Actually let me look at the byte hex of what comes out of the data URL
const bgImage = await page.evaluate(() => {
    return window.getComputedStyle(document.querySelector('.co-thumb')).backgroundImage
})
console.log('bgImage length:', bgImage.length)
console.log('bgImage prefix:', bgImage.slice(0, 100))
console.log('bgImage suffix:', bgImage.slice(-50))

// Decode and check first 8 bytes
const prefix = 'data:image/png;base64,'
const idx = bgImage.indexOf(prefix)
console.log('prefix index:', idx)
if (idx > -1) {
    const start = idx + prefix.length
    const endQuote = bgImage.indexOf('"', start)
    console.log('end quote index:', endQuote, 'of', bgImage.length)
    const b64 = bgImage.slice(start, endQuote).replace(/%3B/g, ';')
    const bin = atob(b64)
    console.log('Decoded byte length:', bin.length)
    console.log('First 8 bytes hex:', Array.from(bin.slice(0, 8)).map(c => c.charCodeAt(0).toString(16).padStart(2, '0')).join(' '))
    console.log('Last 4 bytes hex:', Array.from(bin.slice(-4)).map(c => c.charCodeAt(0).toString(16).padStart(2, '0')).join(' '))
    // PNG magic header should be 89 50 4e 47 0d 0a 1a 0a
    const isPng = bin.charCodeAt(0) === 0x89 && bin.charCodeAt(1) === 0x50 && bin.charCodeAt(2) === 0x4E && bin.charCodeAt(3) === 0x47
    console.log('Is PNG header:', isPng)
} else {
    console.log('No data URL match')
}

await browser.close()
