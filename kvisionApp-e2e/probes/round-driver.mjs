// 1v1 round driver — keeps the browser session open so the orchestrator's
// TurnHarness can advance through the round. Does NOT close the browser.
// Caller is responsible for shutting down the servers when done.

import { chromium } from 'playwright';
import { setTimeout as sleep } from 'node:timers/promises';
import { mkdirSync } from 'node:fs';

const ART = '/tmp/autogenesis-stall-recovery';
mkdirSync(ART, { recursive: true });
const TURN_COUNT = 3;
const BASE_URL = 'http://localhost:8080';

const browser = await chromium.launchPersistentContext('/tmp/ag-profile-guest', {
  headless: true,
  viewport: { width: 1920, height: 1080 },
  args: ['--no-sandbox'],
});
const page = await browser.newPage();

const browserLogs = [];
const completeTimestamps = [];
page.on('console', (m) => {
  const text = m.text();
  browserLogs.push({ ts: Date.now(), type: m.type(), text });
  if (text.includes('isComplete=true')) completeTimestamps.push(Date.now());
});
page.on('pageerror', (err) => console.log('⚠ pageerror:', err.message));

function log(...args) {
  const ts = new Date().toISOString().slice(11, 19);
  console.log(`[${ts}]`, ...args);
}

async function shoot(name) {
  await page.screenshot({ path: `${ART}/${name}.png`, fullPage: false });
  console.log(`  📸 ${ART}/${name}.png`);
}

try {
  // === Phase 1 — splash → guest login → main menu ===
  log('=== Phase 1 — splash → guest login → main menu ===');
  await page.goto(`${BASE_URL}/`, { waitUntil: 'load' });
  await shoot('01-splash');

  await page.waitForSelector('[data-testid="loading-screen-cta"]', { timeout: 30000 });
  await page.locator('[data-testid="loading-screen-cta"]').click();
  await sleep(3000);

  await page.waitForSelector('[data-testid="login-as-guest"]', { timeout: 30000 });
  await page.locator('[data-testid="login-as-guest"]').click();
  log('  Login As Guest clicked — waiting for OAuth (30-60s)');

  await page.waitForSelector('.autogenesis-message-box-overlay', { timeout: 30000 });
  await sleep(1000);
  await page.locator('.autogenesis-message-box-overlay button:has-text("OK")').first().click({ force: true });
  await sleep(3000);
  const overlayStillVisible = await page.locator('.autogenesis-message-box-overlay').count();
  log(`  Login Complete OK clicked; overlay still visible=${overlayStillVisible}`);

  await page.waitForSelector('[data-testid="main-menu"]', { timeout: 90000 });
  await sleep(2000);
  await shoot('02-mainmenu');

  // === Phase 2 — drive to gameplay ===
  log('=== Phase 2 — drive to gameplay ===');
  await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
      if (b.textContent.trim() === 'PLAY') { b.click(); return; }
    }
  });

  await page.waitForSelector('.commander-selection-card', { timeout: 15000 });
  await sleep(1000);
  await page.locator('.commander-selection-card').first().click({ force: true });
  log('  Commander card clicked');
  await sleep(2500);

  await page.locator('[data-testid="commander-selection-root"] button:has-text("Next")').first().click({ force: true });
  log('  Next clicked (Step 1 -> Step 2)');
  await sleep(2500);

  await page.locator('.commander-selection-card:has-text("Play vs. AI locally")').first().click({ force: true });
  log('  Single Player card clicked');
  await sleep(1500);

  await page.locator('[data-testid="commander-selection-root"] button:has-text("Play")').first().click({ force: true });
  log('  Play (Step 2 confirm) clicked');

  await page.waitForSelector('.gameplay-ui', { timeout: 60000 });
  log('  GameplayUI mounted');

  await page.waitForSelector('.autogenesis-message-box-overlay:has-text("Match Ready")', { timeout: 10000 }).catch(() => {});
  for (let i = 0; i < 10; i++) {
    const overlay = page.locator('.autogenesis-message-box-overlay:has-text("Match Ready")');
    if (await overlay.count() === 0) break;
    log('  dismissing Match Ready overlay (try ' + (i + 1) + ')');
    await page.locator('.autogenesis-message-box-overlay:has-text("Match Ready") button:has-text("OK")').first().click({ force: true });
    await sleep(3000);
  }
  await sleep(3000);
  await shoot('03-gameplay-mounted');

  // === Phase 3 — submit one player turn and keep session alive ===
  log('=== Phase 3 — submit one player turn, keep session open ===');
  const ta = page.locator('.command-box textarea').first();
  await ta.waitFor({ timeout: 10000 });
  await ta.fill('Lord Maple Tree holds the line on the eastern flank.');
  await page.evaluate(() => {
    for (const b of document.querySelectorAll('button')) {
      if (b.textContent.trim().toUpperCase() === 'SEND') { b.click(); return; }
    }
  });
  log('  Player turn submitted');

  // Keep the page alive — the orchestrator's TurnHarness needs the WebSocket
  // to advance through the round. Just keep polling for turn dir growth
  // and report what's happening. Never close the browser here.
  log('  Session kept open. Orchestrator should auto-advance through the round.');
  log('  Watch ~/.tpipe/debug/trace/ for new Round_*_Turn_* directories.');
  log('');
  log('  Status reporter running...');

  // Hang here, reporting server-side progress every 30s, until interrupted.
  let lastLogSize = 0;
  while (true) {
    await sleep(30000);
    const traceDir = '/home/cage/.tpipe/debug/trace';
    try {
      const { execSync } = await import('node:child_process');
      const out = execSync(`ls ${traceDir}/Round_*/Turn_*/trace.json 2>/dev/null | wc -l`, { encoding: 'utf8' }).trim();
      const srvSize = parseInt(execSync(`wc -l /tmp/autogenesis-proxy/srv.log 2>/dev/null | awk '{print $1}'`, { encoding: 'utf8' }).trim(), 10);
      const isCompleteCount = completeTimestamps.length;
      log(`  [poll] turn_trace_files=${out} srv.log_lines=${srvSize} isComplete_events=${isCompleteCount}`);
    } catch (e) {
      log(`  [poll] error: ${e.message}`);
    }
  }
} catch (err) {
  console.error('FATAL:', err.message);
  console.error(err.stack);
  process.exitCode = 2;
}
// Deliberately NOT calling browser.close() — orchestrator needs the session.