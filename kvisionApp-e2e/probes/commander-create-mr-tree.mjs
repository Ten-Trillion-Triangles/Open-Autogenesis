#!/usr/bin/env node
// kvisionApp-e2e/probes/commander-create-mr-tree.mjs
//
// Drives a real AccelByte guest login, then creates and saves a new Commander
// representing Lord Maple Tree (Emperor of All Canada, Duke of the Golden Forest,
// Supreme Ruler of the Universe) under the GuestAccount guest account.
//
// Pre-requisites: all three dev servers running on standard ports (7070, 9080, 8080).
//
// Usage: node kvisionApp-e2e/probes/commander-create-mr-tree.mjs [--headed]

import { chromium } from '@playwright/test'
import { writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const HEADED = process.argv.includes('--headed')

const BASE_URL_ARG = process.argv.find(a => a.startsWith('--base-url='))
const BASE_URL = BASE_URL_ARG ? BASE_URL_ARG.substring('--base-url='.length) : 'http://127.0.0.1:8080'

const ARTIFACT_DIR = join(__dirname, 'artifacts-mr-tree')
await mkdir(ARTIFACT_DIR, { recursive: true })

const log = (s) => console.log(`[${new Date().toISOString().slice(11, 23)}] ${s}`)

// ---------------------------------------------------------------------------
// The Commander — biography of Lord Maple Tree, Emperor of All Canada
// ---------------------------------------------------------------------------
const COMMANDER_NAME = 'Lord Maple Tree'
const COMMANDER_DESCRIPTION = [
    'Lord Maple Tree is a megalomaniacal Ent, towering among his kind, born of the',
    'ancient forests of Canada but destined for much greater things than mere',
    'standing. He is the Emperor of All Canada, the Duke of the Golden Forest, the',
    'King of the North American Union, the Defender of the Free World, the',
    'Protector of the British Empire, the Sovereign of the Commonwealth, and the',
    'Supreme Ruler of the Universe (all titles self-appointed, none disputed by',
    'anyone willing to argue). His limbs are gnarled maple branches, his voice',
    'sounds like wind through autumn leaves, and his gaze carries the weight of',
    'centuries of silent patience — now broken, very loudly, by pancakes.',
    '',
    'He commands the Slave Lake Ent Army, an ancient legion of sentient trees who',
    'march with the slow, inevitable creak of nature itself. He has developed',
    'explosive maple syrup (a closely guarded secret: nitroglycerine blended with',
    'pure golden syrup, detonating into a giant pancake). His scientists are',
    'delighted by every experiment. His advisors — Jhonny Apple Tree, Mr. Bean,',
    'General Moustache, General Flipper — serve him with a mixture of awe and',
    'quiet resignation.',
    '',
    'He believes, with absolute and unshakable conviction, that the world\'s',
    'problems could be solved if everyone simply had access to pancakes and',
    'maple syrup. This is not a joke to him. It is his political philosophy.',
    'His greatest enemy, King Candy, betrayed him, fled to the stars, came back,',
    'was killed, then was resurrected — and Lord Maple Tree has nuclear-launched',
    'him and still holds the grudge. He once incinerated Fort McMurray with',
    'terrifying calm and immediately pivoted to discussing breakfast logistics.',
    'He regards the UN declaration of Canadian sovereignty as an irritating',
    'technicality.',
    '',
    'On the world map he is patient, methodical, and apocalyptic. He does not',
    'fight for territory — he fights for syrup reserves. He does not negotiate',
    'for peace — he negotiates for pancake rights. He has drowned entire armies',
    'in maple syrup and called it "a delicious victory."',
].join(' ')

const NATION_DESCRIPTION = [
    'The Dominion of the Golden Forest stretches from the Atlantic to the Pacific',
    'and from the Arctic to the American border, though Lord Maple Tree considers',
    'the border an unfortunate cartographic suggestion. The national capital is',
    'Slave Lake, seat of the Imperial Ent Throne and the central syrup refineries.',
    '',
    'The economy runs on maple syrup. Syrup is currency. Syrup is religion. Syrup',
    'is the highest form of tribute. Every citizen is issued a daily pancake',
    'ration. The Imperial Pancake Service operates 24 hours a day, 7 days a week,',
    'with a slight crunchiness bias in autumn. The Ent Army — giant sentient',
    'trees — forms the standing military and is deployed by His Majesty on the',
    'basis of strategic syrup-reserve density.',
    '',
    'Foreign policy is unambiguous: any nation that accepts Lord Maple Tree\'s',
    'maple syrup as legal tender is considered a friend. Any nation that does not',
    'is considered "a delicious opportunity." The Empire is currently negotiating',
    'with the European Union on syrup-treaty terms; preliminary talks have stalled',
    'over the French position on crêpes (which Lord Maple Tree regards as inferior',
    'pancakes, but is willing to tolerate for diplomatic reasons).',
    '',
    'The Imperial motto, etched into the bark of every Ent in the Slave Lake Army,',
    'reads: "For the syrup, the pancakes, and the inevitable glory of the Golden',
    'Forest." King Candy remains the only entity officially recognized as a',
    'nemesis; all other enemies are simply "people who have not yet tried the',
    'pancakes."',
].join(' ')

const dumpDomSnapshot = async (page, name) => {
    try {
        await writeFile(join(ARTIFACT_DIR, `${name}.html`), await page.content())
    } catch {}
}

// ---------------------------------------------------------------------------
// Main probe
// ---------------------------------------------------------------------------
const browser = await chromium.launch({ headless: !HEADED })
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
const page = await context.newPage()

const consoleErrors = []
const pageErrors = []
page.on('console', msg => {
    if (msg.type() === 'error') consoleErrors.push(msg.text())
})
page.on('pageerror', err => pageErrors.push(err.message))

let exitCode = 0
try {
    // ===== Step 1: navigate =====
    log('Step 1: navigate to index.html (no skipLogin)')
    await page.goto(`${BASE_URL}/index.html`)

    // ===== Step 2: click through LoadingScreen CTA =====
    log('Step 2: click through LoadingScreen CTA')
    const cta = page.getByTestId('loading-screen-cta')
    await cta.waitFor({ state: 'visible', timeout: 30_000 })
    await cta.click()
    await page.locator('.login-widget-window').waitFor({ state: 'visible', timeout: 30_000 })
    log('  LoginPage mounted')
    await dumpDomSnapshot(page, '01-login-page')

    // ===== Step 3: click "Login As Guest" =====
    log('Step 3: click "Login As Guest"')
    const guestButton = page.getByTestId('login-as-guest')
    await guestButton.first().waitFor({ state: 'visible', timeout: 10_000 })
    await guestButton.first().click()
    log('  clicked')

    // ===== Step 4: wait for MainMenu (PLAY button) — poll + dismiss messageBox =====
    log('Step 4: wait for MainMenu (PLAY button) — polling for 60s')
    const playButton = page.locator('.btn.btn-play')
    const okByText = page.getByRole('button', { name: /^OK$/ })
    const deadline = Date.now() + 60_000
    let menuFound = false
    while (Date.now() < deadline) {
        if (await playButton.count() > 0) {
            menuFound = true
            break
        }
        if (await okByText.count() > 0) {
            try { await okByText.first().click({ timeout: 1_000 }); log('  dismissed messageBox OK') } catch {}
        }
        await page.waitForTimeout(200)
    }
    if (!menuFound) {
        throw new Error('MainMenu PLAY button never appeared within 60s')
    }
    log('  MainMenu mounted (PLAY button present)')
    await dumpDomSnapshot(page, '02-main-menu')

    const liveState = await page.evaluate(() => ({
        accelbyteUserId: window.globals?.AccelByteEnv?.userId ?? null,
        accelbyteDisplayName: window.globals?.AccelByteEnv?.displayName ?? null,
    }))
    log(`  accelbyteUserId: ${liveState.accelbyteUserId}`)
    log(`  accelbyteDisplayName: ${liveState.accelbyteDisplayName}`)

    // ===== Step 5: click "New Commander +" =====
    log('Step 5: click "New Commander +"')
    const newCommanderButton = page.locator('button:has-text("New Commander")').first()
    await newCommanderButton.waitFor({ state: 'visible', timeout: 10_000 })
    await newCommanderButton.click()

    // ===== Step 6: wait for CommanderCreationDialog =====
    log('Step 6: wait for CommanderCreationDialog (h3 "Create Your Commander")')
    await page.waitForSelector('h3:has-text("Create Your Commander")', { timeout: 10_000 })
    log('  CommanderCreationDialog mounted')
    await dumpDomSnapshot(page, '03-creation-dialog-empty')

    // ===== Step 7: fill the three boxes =====
    log('Step 7: fill the three boxes')

    // Box 1: Commander Name
    const nameInput = page.locator('.commander-creation-dialog input[type="text"]').first()
    await nameInput.waitFor({ state: 'visible', timeout: 5_000 })
    await nameInput.fill(COMMANDER_NAME)
    log(`  Commander Name: "${COMMANDER_NAME}"`)

    // Boxes 2 and 3: Description and Nation Description (textAreas)
    const textAreas = page.locator('.commander-creation-dialog textarea')
    const taCount = await textAreas.count()
    log(`  textareas inside dialog: ${taCount}`)
    if (taCount < 2) {
        throw new Error(`Expected 2 textareas (description + nation description), found ${taCount}`)
    }
    await textAreas.nth(0).fill(COMMANDER_DESCRIPTION)
    log(`  Description: ${COMMANDER_DESCRIPTION.length} chars`)
    await textAreas.nth(1).fill(NATION_DESCRIPTION)
    log(`  Nation Description: ${NATION_DESCRIPTION.length} chars`)

    await page.waitForTimeout(500)
    await dumpDomSnapshot(page, '04-creation-dialog-filled')

    // ===== Step 8: click CREATE =====
    log('Step 8: click CREATE')
    const createButton = page.locator('.commander-creation-dialog button:has-text("CREATE")').first()
    await createButton.waitFor({ state: 'visible', timeout: 5_000 })
    await createButton.click()

    // ===== Step 9: wait for "Commander Saved" success messageBox =====
    log('Step 9: wait for "Commander Saved" success messageBox')
    const savedTitle = page.locator('text=Commander Saved')
    const savedDeadline = Date.now() + 30_000
    let savedFound = false
    while (Date.now() < savedDeadline) {
        if (await savedTitle.count() > 0) {
            savedFound = true
            break
        }
        // also look for the OK button on the throbber-cleared messageBox
        if (await okByText.count() > 0) {
            try { await okByText.first().click({ timeout: 1_000 }) } catch {}
        }
        await page.waitForTimeout(300)
    }
    if (!savedFound) {
        throw new Error('"Commander Saved" messageBox never appeared within 30s')
    }
    log('  "Commander Saved" appeared')
    await dumpDomSnapshot(page, '05-saved-messagebox')

    // Capture saved-message excerpt
    const savedExcerpt = await page.locator('text=/Commander.*saved/i').first().textContent().catch(() => null)
    if (savedExcerpt) log(`  saved-message excerpt: ${savedExcerpt.slice(0, 200)}`)

    // ===== Step 10: dismiss success OK =====
    log('Step 10: dismiss success OK')
    await okByText.first().waitFor({ state: 'visible', timeout: 10_000 })
    await okByText.first().click()
    await page.waitForTimeout(1500)
    await dumpDomSnapshot(page, '06-after-save')

    // ===== Step 11: open Collection overlay and verify =====
    log('Step 11: open Collection overlay to verify the commander is listed')
    const collectionButton = page.locator('button:has-text("Collection")').first()
    await collectionButton.waitFor({ state: 'visible', timeout: 10_000 })
    await collectionButton.click()
    await page.waitForSelector('.collection-overlay', { state: 'visible', timeout: 10_000 })
    await page.waitForTimeout(2500)
    await dumpDomSnapshot(page, '07-collection-overlay')

    const collectionText = await page.locator('.collection-overlay').first().textContent().catch(() => '')
    const inList = (collectionText ?? '').includes(COMMANDER_NAME)
    log(`  Commander "${COMMANDER_NAME}" present in collection list: ${inList}`)

    if (!inList) {
        throw new Error(`Commander "${COMMANDER_NAME}" NOT found in Collection overlay text`)
    }

    // ===== Result =====
    log('Result: PASS')
    log(`  accelbyteUserId: ${liveState.accelbyteUserId}`)
    log(`  accelbyteDisplayName: ${liveState.accelbyteDisplayName}`)
    log(`  commander created: "${COMMANDER_NAME}"`)
    log(`  description length: ${COMMANDER_DESCRIPTION.length} chars`)
    log(`  nation description length: ${NATION_DESCRIPTION.length} chars`)
    log(`  console errors: ${consoleErrors.length}`)
    log(`  page errors: ${pageErrors.length}`)

    if (consoleErrors.length > 0) {
        log('  console error sample:')
        consoleErrors.slice(0, 5).forEach(e => log(`    ${e.slice(0, 200)}`))
    }
    if (pageErrors.length > 0) {
        log('  page error sample:')
        pageErrors.slice(0, 5).forEach(e => log(`    ${e.slice(0, 200)}`))
    }
} catch (err) {
    log(`Result: FAIL — ${err.message}`)
    await dumpDomSnapshot(page, 'FAIL')
    exitCode = 1
} finally {
    await browser.close()
    process.exit(exitCode)
}
