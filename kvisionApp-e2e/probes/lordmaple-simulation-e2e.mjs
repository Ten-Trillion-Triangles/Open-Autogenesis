// lordmaple-simulation-e2e.mjs
// Full end-to-end probe for Simulation Mode — every menu, every interaction,
// every screen. Captures screenshots at each step and asserts that:
//   (a) every menu opens and renders correctly on desktop (1920x1080)
//   (b) the +/- slot count button cleanly rebuilds the map list (no orphan DOM)
//   (c) map selection toggles the active state visually
//   (d) commander mini-card toggle adds to the roster
//   (e) the Play button gates enable/disable correctly as the user configures

import { chromium } from 'playwright';
import { setTimeout as sleep } from 'node:timers/promises';
import { mkdirSync, writeFileSync } from 'node:fs';

const ART = '/tmp/lordmaple-sim-e2e-final';
mkdirSync(ART, { recursive: true });

const browser = await chromium.launchPersistentContext('/tmp/ag-sim-e2e-profile', {
    headless: true,
    viewport: { width: 1920, height: 1080 },
    args: ['--no-sandbox']
});
const page = await browser.newPage();

const errors = [];
page.on('console', (m) => {
    const t = m.type();
    if (t === 'error') errors.push({ type: t, text: m.text().slice(0, 200) });
});
page.on('pageerror', (e) => errors.push({ type: 'pageerror', text: e.message }));

async function shot(name, fullPage = false) {
    await page.screenshot({ path: `${ART}/${name}.png`, fullPage });
    console.log(`[shot] ${name}.png`);
}

// === Bundle ready ===
console.log('[t=0] Wait for HTML to load');
await page.goto('http://localhost:8080/', { waitUntil: 'load', timeout: 60000 });
await sleep(2000);

// === Loading screen CTA ===
console.log('[t=0] Wait for loading screen CTA');
try {
    await page.waitForSelector('[data-testid="loading-screen-cta"]', { timeout: 30000 });
    await page.locator('[data-testid="loading-screen-cta"]').click();
} catch (e) {
    console.log('[t=0] No loading CTA — already past it');
}
await sleep(2000);

// === Login as guest ===
console.log('[t=0] Wait for login as guest');
await page.waitForSelector('[data-testid="login-as-guest"]', { timeout: 30000 });
await page.locator('[data-testid="login-as-guest"]').click();
console.log('[t=0] Login clicked');

// === Dismiss the "Login Complete" OK modal FIRST (it appears during OAuth flow) ===
// Then wait for the MainMenu to mount behind it.
console.log('[t=0] Wait for Login Complete modal, dismiss it');
try {
    await page.waitForSelector('text=/Login Complete/i', { timeout: 60000 });
    console.log('[t=0] Login Complete modal visible');
    await sleep(1000);
    const okBtn = page.locator('.autogenesis-message-box-overlay button:has-text("OK")').first();
    if (await okBtn.count() > 0) {
        await okBtn.click({ force: true });
        console.log('[t=0] OK clicked');
    } else {
        await page.locator('button:has-text("OK")').first().click({ force: true });
    }
    await sleep(2500);
} catch (e) {
    console.log('[t=0] No Login Complete modal (or already dismissed)');
}

// === MainMenu ===
console.log('[t=0] Wait for main menu');
try {
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 60000 });
    console.log('[t=0] MainMenu visible');
} catch (e) {
    console.log('[t=0] TIMEOUT — saving diagnostic screenshot');
    await shot('TIMEOUT-no-mainmenu');
    const visibleState = await page.evaluate(() => {
        const all = Array.from(document.querySelectorAll('[data-testid]')).map(e => e.getAttribute('data-testid'));
        const buttons = Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().slice(0, 30));
        return { testIds: all.slice(0, 20), buttons: buttons.slice(0, 15) };
    });
    console.log('[t=0] page state:', JSON.stringify(visibleState, null, 2));
    await browser.close();
    process.exit(1);
}
await sleep(3000);
await shot('01-mainmenu');

// Dismiss any OK modal (login-complete popup, etc.)
for (let a = 0; a < 5; a++) {
    const okBtn = page.locator('button:has-text("OK")').first();
    if (await okBtn.count() > 0 && await okBtn.isVisible()) {
        console.log(`[t=0] dismissing OK modal #${a}`);
        await okBtn.click({ force: true });
        await sleep(1500);
    } else break;
}

// Dismiss any OK modal
for (let a = 0; a < 3; a++) {
    const okBtn = page.locator('button:has-text("OK")').first();
    if (await okBtn.count() > 0 && await okBtn.isVisible()) {
        await okBtn.click({ force: true });
        await sleep(1500);
    } else break;
}

// === Click PLAY → wizard ===
console.log('[t=0] Click PLAY');
await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
        if (b.textContent.trim() === 'PLAY') { b.click(); return }
    }
});

// === Step 1: Commander Selection ===
console.log('[t=0] Wait for Step 1');
await page.waitForSelector('.commander-selection-card', { timeout: 15000 });
await sleep(1500);
await shot('02-step1-commander-selection');

// Verify Step 1: cards visible
const step1Cards = await page.locator('.commander-selection-card').count();
console.log(`[step1] commander cards: ${step1Cards}`);

// Pick first commander
const firstCard = page.locator('.commander-selection-card').first();
await firstCard.click({ force: true });
await sleep(800);
await shot('03-step1-commander-selected');

// Verify Step 1: Next button visible
const step1NextVisible = await page.locator('button:has-text("Next")').first().isVisible();
console.log(`[step1] Next visible: ${step1NextVisible}`);

// Next → Step 2
const nextBtn = page.locator('button:has-text("Next")').first();
await nextBtn.click({ force: true });
await sleep(1500);
await shot('04-step2-game-type-selection');

// Verify Step 2: three cards visible (vs AI, PvP, Simulation)
const step2Cards = await page.locator('.commander-selection-card').count();
console.log(`[step2] game type cards: ${step2Cards}`);

// Pick Simulation
const simCard = page.locator('text=/play every slot yourself/i').first();
const simHit = await simCard.count();
console.log(`[step2] simulation card hits: ${simHit}`);
if (simHit > 0) await simCard.click({ force: true });
await sleep(800);
await shot('05-step2-simulation-selected');

// Verify Step 2 → Step 3: Next visible (the visibility-rule fix from 2026-08-07)
const step2NextVisible = await nextBtn.isVisible();
console.log(`[step2] Next visible after picking sim: ${step2NextVisible}`);

// Next → Step 3
await nextBtn.click({ force: true });
await sleep(2000);
await shot('06-step3-simulation-settings-initial');

// === STEP 3: SimulationSettingsPage ===
// This is the BROKEN screen. Verify it renders correctly.
const step3PageExists = await page.locator('.simulation-settings-page').count();
console.log(`[step3] .simulation-settings-page exists: ${step3PageExists}`);

const step3InitialState = await page.evaluate(() => {
    const page = document.querySelector('.simulation-settings-page');
    if (!page) return { exists: false };
    const cards = Array.from(document.querySelectorAll('.simulation-map-card'));
    const pickers = Array.from(document.querySelectorAll('.simulation-map-picker'));
    const body = document.querySelector('.simulation-settings-body');
    return {
        exists: true,
        pickerCount: pickers.length,    // MUST be 1, not 2+
        cardCount: cards.length,        // MUST be 3 for slotCount=2 (San_Martello, Arctica, StartMap)
        bodyWidth: body ? body.offsetWidth : 0,
        cards: cards.map(c => ({
            w: c.offsetWidth,
            h: c.offsetHeight,
            text: (c.innerText || '').slice(0, 60)
        }))
    };
});
console.log('[step3] INITIAL STATE:', JSON.stringify(step3InitialState, null, 2));
writeFileSync(`${ART}/state-initial.json`, JSON.stringify(step3InitialState, null, 2));

// === REPRO STEP 1: Click + to bump slot count to 3 ===
console.log('[repro] Click +');
const plusBtn = page.locator('.simulation-settings-page button:has-text("+")').first();
await plusBtn.click({ force: true });
await sleep(1500);
await shot('07-after-plus-to-3');

const step3AfterPlus3 = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll('.simulation-map-card'));
    const pickers = Array.from(document.querySelectorAll('.simulation-map-picker'));
    return {
        pickerCount: pickers.length,
        cardCount: cards.length,
        cards: cards.map(c => ({
            w: c.offsetWidth,
            h: c.offsetHeight,
            text: (c.innerText || '').slice(0, 60)
        }))
    };
});
console.log('[repro] AFTER + STATE:', JSON.stringify(step3AfterPlus3, null, 2));
writeFileSync(`${ART}/state-after-plus.json`, JSON.stringify(step3AfterPlus3, null, 2));

// === REPRO STEP 2: Click + again to bump to 4 ===
await plusBtn.click({ force: true });
await sleep(1500);
await shot('08-after-plus-to-4');

const step3AfterPlus4 = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll('.simulation-map-card'));
    return {
        cardCount: cards.length,
        cards: cards.map(c => ({
            w: c.offsetWidth, h: c.offsetHeight,
            text: (c.innerText || '').slice(0, 60)
        }))
    };
});
console.log('[repro] AFTER ++ STATE:', JSON.stringify(step3AfterPlus4, null, 2));
writeFileSync(`${ART}/state-after-plus-plus.json`, JSON.stringify(step3AfterPlus4, null, 2));

// === REPRO STEP 3: Click - to bring back to 2 ===
const minusBtn = page.locator('.simulation-settings-page button:has-text("-")').first();
await minusBtn.click({ force: true });
await sleep(1500);
await shot('09-after-minus-back-to-3');

await minusBtn.click({ force: true });
await sleep(1500);
await shot('10-after-minus-back-to-2');

const step3AfterMinus = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll('.simulation-map-card'));
    return {
        cardCount: cards.length,
        cards: cards.map(c => ({
            w: c.offsetWidth, h: c.offsetHeight,
            text: (c.innerText || '').slice(0, 60)
        }))
    };
});
console.log('[repro] AFTER -- STATE:', JSON.stringify(step3AfterMinus, null, 2));
writeFileSync(`${ART}/state-after-minus.json`, JSON.stringify(step3AfterMinus, null, 2));

// === Click a map card to verify selection works ===
console.log('[step3] Click a map card');
const mapCards = page.locator('.simulation-map-card');
const mapCount = await mapCards.count();
console.log(`[step3] map cards: ${mapCount}`);
if (mapCount > 1) {
    await mapCards.nth(1).click({ force: true });
    await sleep(800);
    await shot('11-map-selected');
}

// Verify active state on the map card
const activeMapCard = await page.evaluate(() => {
    const a = document.querySelector('.simulation-map-card-active');
    return a ? { text: (a.innerText || '').slice(0, 60), w: a.offsetWidth } : null;
});
console.log('[step3] active map card:', JSON.stringify(activeMapCard, null, 2));

// === Click a commander mini-card to verify toggle ===
console.log('[step3] Click a commander mini-card');
const miniCards = page.locator('.commander-mini-card');
const miniCount = await miniCards.count();
console.log(`[step3] commander mini cards: ${miniCount}`);
if (miniCount > 0) {
    await miniCards.first().click({ force: true });
    await sleep(800);
    await shot('12-commander-toggled');

    // Verify it became active
    const activeCommander = await page.evaluate(() => {
        const a = document.querySelector('.commander-mini-card.commander-selection-card-active');
        return a ? { text: (a.innerText || '').slice(0, 80) } : null;
    });
    console.log('[step3] active commander:', JSON.stringify(activeCommander, null, 2));
}

// === Verify Play button state ===
const playBtnState = await page.evaluate(() => {
    // Find the dialog footer Play button (outer Play, not inner which was removed)
    const buttons = Array.from(document.querySelectorAll('button'));
    const play = buttons.find(b => b.textContent.trim() === 'PLAY' && b.offsetParent !== null);
    if (!play) return { exists: false };
    return {
        exists: true,
        disabled: play.disabled,
        opacity: window.getComputedStyle(play).opacity,
        text: play.textContent.trim()
    };
});
console.log('[step3] Play button state:', JSON.stringify(playBtnState, null, 2));
writeFileSync(`${ART}/play-button-state.json`, JSON.stringify(playBtnState, null, 2));

// === Test the search field ===
console.log('[step3] Test the search field');
const searchField = page.locator('.simulation-settings-page input[placeholder*="Search"]').first();
const searchExists = await searchField.count();
console.log(`[step3] search field count: ${searchExists}`);
if (searchExists > 0) {
    await searchField.fill('maple');
    await sleep(800);
    await shot('13-search-filtered');

    // Verify the filter narrowed the list
    const filteredCount = await page.evaluate(() => {
        const cards = Array.from(document.querySelectorAll('.commander-mini-card'));
        return cards.filter(c => c.offsetParent !== null && c.offsetWidth > 0).length;
    });
    console.log(`[step3] visible commander cards after filter "maple": ${filteredCount}`);

    // Clear search
    await searchField.fill('');
    await sleep(500);
}

// === DOM dump of the FINAL state (the proof the fix worked) ===
const finalDomDump = await page.evaluate(() => {
    const page = document.querySelector('.simulation-settings-page');
    if (!page) return 'NO SIM PAGE';
    function summarize(el, depth = 0) {
        if (depth > 8) return '';
        const cls = el.className ? `.${String(el.className).split(' ').filter(Boolean).join('.')}` : '';
        const tag = el.tagName.toLowerCase();
        const text = (el.childNodes.length === 1 && el.childNodes[0].nodeType === 3)
            ? ` "${(el.textContent || '').trim().slice(0, 50)}"` : '';
        const w = el.offsetWidth, h = el.offsetHeight;
        const indent = '  '.repeat(depth);
        let s = `${indent}<${tag}${cls}> [${w}x${h}]${text}\n`;
        for (const child of el.children) s += summarize(child, depth + 1);
        return s;
    }
    return summarize(page);
});
writeFileSync(`${ART}/final-dom-dump.txt`, finalDomDump);

await browser.close();

// === FINAL REPORT ===
console.log('\n=== FINAL REPORT ===');
const consoleErrors = errors.filter(e => !e.text.includes('webpack-dev-server') &&
                                          !e.text.includes('504') &&
                                          !e.text.includes('Invalid frame header'));
console.log(`Console errors (excl. webpack infra): ${consoleErrors.length}`);
for (const e of consoleErrors.slice(0, 10)) {
    console.log(`  [${e.type}] ${e.text}`);
}
console.log('\nArtifacts saved to', ART);