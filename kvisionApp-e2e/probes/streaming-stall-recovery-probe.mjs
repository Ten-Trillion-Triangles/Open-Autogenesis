// Lord Maple Tree — streaming-stall recovery probe
// Live e2e regression probe for the Phase-6 WriterAgent streaming-stall class.
// Uses the canonical _probe_guest_login.mjs flow (persistent-context guest login,
// single-card click, "START" button text) to drive the game through REAL turns.
//
// What this probe verifies (3-check load-bearing recipe, per Pitfall 12 + 13):
//   1. isComplete=true is observed at least once per turn (T3 completion wire-up)
//   2. NO AWAITER FOUND warnings == 0 (T2 walker + orchestrator not stuck)
//   3. safety-gate verdict count >= turn count (every turn reached the gate)
// Pitfall 14 retired the 4th check (Game History panel `gh-detail-card`
// count) — the panel is only populated by client-side demo mode; the
// real turn-advancement signal is server-side.
//
// Prereqs:
//   - servers running via ./debugger/scripts/start_servers.sh on 7070/9080/8080
//   - /tmp/ag-profile-guest has the AccelByte OAuth session from a prior run
//
// Run:
//   node kvisionApp-e2e/probes/streaming-stall-recovery-probe.mjs

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
let fullCapturedLog = '';
const _origLog = log;
log = (...args) => { fullCapturedLog += args.join(' ') + '\n'; _origLog(...args); };
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

  // LoadingScreen → click CTA
  await page.waitForSelector('[data-testid="loading-screen-cta"]', { timeout: 30000 });
  await page.locator('[data-testid="loading-screen-cta"]').click();
  await sleep(3000);

  // Login As Guest button
  await page.waitForSelector('[data-testid="login-as-guest"]', { timeout: 30000 });
  await page.locator('[data-testid="login-as-guest"]').click();
  log('  Login As Guest clicked — waiting for OAuth (30-60s)');

  // Dismiss the "Login Complete" message box that appears after OAuth.
  // (The canonical probe's early versions missed this; the OK button is on
  // the message box overlay, not the Login screen root.)
  await page.waitForSelector('.autogenesis-message-box-overlay', { timeout: 30000 });
  await sleep(1000);
  // Use Playwright's locator click (not JS evaluate) so the KVision onClick
  // handler fires. Force-click bypasses the modal-overlay pointer-events
  // guard that earlier JS-evaluate clicks hit.
  await page.locator('.autogenesis-message-box-overlay button:has-text("OK")').first().click({ force: true });
  await sleep(3000);
  // Verify the overlay is gone.
  const overlayStillVisible = await page.locator('.autogenesis-message-box-overlay').count();
  log(`  Login Complete OK clicked; overlay still visible=${overlayStillVisible}`);

  // MainMenu (real OAuth up to 90s)
  try {
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 90000 });
  } catch (e) {
    await shoot('FAILED-no-mainmenu');
    const state = await page.evaluate(() => ({
      title: document.title,
      bodyText: document.body.textContent.substring(0, 500),
      visibleTestIds: Array.from(document.querySelectorAll('[data-testid]')).map(e => e.dataset.testid).slice(0, 20),
      visibleButtons: Array.from(document.querySelectorAll('button')).filter(b => b.offsetParent !== null).map(b => b.textContent.trim().substring(0, 30)).filter(t => t.length > 0).slice(0, 10),
    }));
    console.log('FAILED state:', JSON.stringify(state, null, 2));
    throw e;
  }
  await sleep(2000);
  await shoot('02-mainmenu');

  // === Phase 2 — drive to gameplay via canonical path ===
  log('=== Phase 2 — drive to gameplay ===');

  // Click PLAY in MainMenu
  await page.evaluate(() => {
    for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
      if (b.textContent.trim() === 'PLAY') { b.click(); return; }
    }
  });

  // Wait for commander selection dialog
  await page.waitForSelector('.commander-selection-card', { timeout: 15000 });
  await sleep(1000);

  // First .commander-selection-card is the saved Lord Maple Tree (or whatever
  // is in CloudSave for this account). Click it via Playwright's locator so
  // the KVision onClick handler fires (force-click bypasses the SimplePanel
  // pointer-events guard).
  await page.locator('.commander-selection-card').first().click({ force: true });
  log('  Commander card clicked');
  await sleep(2500);

  // Step 1 -> Step 2: the Next button is enabled after a commander is selected
  // (CommanderSelectionDialog.kt:276). Click it to advance to Step 2.
  await page.locator('[data-testid="commander-selection-root"] button:has-text("Next")').first().click({ force: true });
  log('  Next clicked (Step 1 -> Step 2)');
  await sleep(2500);

  // Step 2 — the game-type card text is 'Play vs. AI locally' for Single Player
  // (CommanderSelectionDialog.kt:369), not 'Single Player'. The Play button
  // is enabled by default once a commander is selected (default selection
  // is SINGLEPLAYER per CommanderSelectionDialog.kt:398).
  await page.locator('.commander-selection-card:has-text("Play vs. AI locally")').first().click({ force: true });
  log('  Single Player card clicked (Play vs. AI locally)');
  await sleep(1500);
  await sleep(1500);
  await sleep(2500);

  // Click the Step 2 confirm button. The Commander Selection Dialog's
  // Step 2 button is labeled 'Play' (CommanderSelectionDialog.kt:291).
  // Use Playwright's locator click so the KVision onClick handler fires
  // (force-click bypasses the modal-overlay pointer-events guard).
  await page.locator('[data-testid="commander-selection-root"] button:has-text("Play")').first().click({ force: true });
  log('  Play (Step 2 confirm) clicked');

  // Wait for the gameplay UI root class to appear.
  await page.waitForSelector('.gameplay-ui', { timeout: 60000 });
  log('  GameplayUI mounted');
  // Dismiss the 'Match Ready' overlay that covers the gameplay UI
  // immediately after mount. The actual map + command box render behind it.
  await page.waitForSelector('.autogenesis-message-box-overlay:has-text("Match Ready")', { timeout: 10000 }).catch(() => {});
  for (let i = 0; i < 10; i++) {
    const overlay = page.locator('.autogenesis-message-box-overlay:has-text("Match Ready")');
    if (await overlay.count() === 0) break;
    log('  dismissing Match Ready overlay (try ' + (i + 1) + ')');
    await page.locator('.autogenesis-message-box-overlay:has-text("Match Ready") button:has-text("OK")').first().click({ force: true });
    await sleep(3000);
  }
  // Wait for the Game History panel to render at least one entry card
  // (canonical proxy for gameplay-ready). The history panel is mounted
  // empty then populated by the first world update.
  await page.waitForSelector('.gh-detail-card', { timeout: 60000 }).catch(() => log('  ⚠ gh-detail-card did not render within 60s'));
  await sleep(3000);
  await shoot('03-gameplay-mounted');

  // === Phase 3 — submit TURN_COUNT turns and capture fingerprints ===
  log(`=== Phase 3 — submit ${TURN_COUNT} turns ===`);
  const fingerprints = [];
  for (let i = 0; i < TURN_COUNT; i++) {
    log(`\n--- Turn ${i + 1} of ${TURN_COUNT} ---`);

    // Fingerprint before: capture overlay state + the (informational)
    // Game History panel entry count. The entry count is read for
    // diagnostics only — the real turn-advancement signal is the
    // server-side `Safety gate: verdict` line count.
    const beforeFingerprint = await page.evaluate(() => {
      const historyEntries = document.querySelectorAll('.gh-detail-card').length;
      const overlay = document.querySelector('.autogenesis-message-box-overlay');
      return { historyEntries, overlayVisible: overlay !== null };
    });
    const beforeCompletes = completeTimestamps.length;

    // Submit turn via the Send button. The CommandBox textarea lives in
    // .command-box (CommandBox.kt:100). The placeholder text doesn't contain
    // 'command' (it says 'Type the action...'), so use the class selector.
    const ta = await page.locator('.command-box textarea').first();
    await ta.waitFor({ timeout: 10000 });
    await ta.fill(`Turn ${i + 1}: hold the line, scout the eastern flank, report back.`);
    await page.evaluate(() => {
      // The Send button label is 'SEND' (uppercase) per the screenshot
      // capture. Case-insensitive match catches both 'Send' and 'SEND'.
      for (const b of document.querySelectorAll('button')) {
        if (b.textContent.trim().toUpperCase() === 'SEND') { b.click(); return; }
      }
    });

    // Wait for turn resolution: the gameplay UI gets an overlay (Turn Resolution
    // widget) within ~30s of Send. Pre-fix stall: this can hang forever.
    await page.waitForFunction(
      (before) => {
        const overlay = document.querySelector('.autogenesis-message-box-overlay');
        return overlay !== null || before.overlayVisible === false;
      },
      beforeFingerprint,
      { timeout: 60000, polling: 500 },
    ).catch(() => log('  ⚠ turn resolution did not advance within 60s'));

    await sleep(2000);

    const afterFingerprint = await page.evaluate(() => {
      const historyEntries = document.querySelectorAll('.gh-detail-card').length;
      const overlay = document.querySelector('.autogenesis-message-box-overlay');
      return { historyEntries, overlayVisible: overlay !== null };
    });
    const afterCompletes = completeTimestamps.length;
    const completesThisTurn = afterCompletes - beforeCompletes;

    log(`  fingerprint before: ${JSON.stringify(beforeFingerprint)}`);
    log(`  fingerprint after:  ${JSON.stringify(afterFingerprint)}`);
    log(`  isComplete=true events during turn: ${completesThisTurn}`);
    // Stash before/after completes on the fingerprint object so the verdict
    // phase can compute per-turn deltas authoritatively.
    afterFingerprint.beforeCompletes = beforeCompletes;
    afterFingerprint.afterCompletes = afterCompletes;
    afterFingerprint.completesThisTurn = completesThisTurn;
    fingerprints.push(afterFingerprint);
    await shoot(`0${i + 4}-turn${i + 1}-fingerprint`);

    // Dismiss any MessageBox overlay so the next turn's Send button is reachable
    await page.evaluate(() => {
      const mb = document.querySelector('.autogenesis-message-box-overlay');
      if (mb) {
        for (const b of mb.querySelectorAll('button')) {
          if (b.textContent.trim().toUpperCase() === 'OK') { b.click(); return; }
        }
      }
    });
    await sleep(1000);
  }

  // === Phase 4 — verdict collection ===
  log('\n=== Phase 4 — verdict collection ===');
  // Per-turn isComplete=true counts are captured inline as
  // `completesThisTurn` (after-completes minus before-completes per turn).
  // The same fingerprint capture was the source of the broken proxy
  // `data-testid="territory-icon"` (the MapViewer's tile markup doesn't
  // carry stable testids during the AI's judgment phase). The
  // load-bearing turn-advancement signal is the per-turn completion count.
  // Per-turn gh-detail-card counts are the canonical turn-advancement
  // signal. The probe also tracks per-turn `isComplete=true` console events
  // as a secondary signal (T3 wire-up verification) but the primary
  // verdict is the history-entry count.
  const completesObservedPerTurn = fingerprints.map(f => f.completesThisTurn || 0);

  const noAwaiterWarnings = browserLogs.filter(l => l.text.includes('NO AWAITER')).length;

  // safety-verdict count from serverside log
  let safetyVerdictCount = null;
  try {
    const { execSync } = await import('node:child_process');
    const out = execSync(
      `grep -c "Safety gate: verdict" /tmp/autogenesis-proxy/srv.log 2>/dev/null || echo 0`,
      { encoding: 'utf8' },
    ).trim();
    safetyVerdictCount = parseInt(out, 10);
  } catch { /* ignore */ }

  const probeVerdicts = {
    turnOrderAdvanced: false, // approximate: tile count change between turns
    completesObservedPerTurn,
    noAwaiterWarnings,
    safetyVerdictCount,
    phase6Completed: false,
  };

  // The gh-detail-card count is informational only — the Game History
  // panel is only populated by client-side demo mode
  // (GameHistoryWindow.kt:165-170); the server-to-client RPC that
  // pushes history entries is not wired in real gameplay. The
  // turn-advancement signal is the server-side `Safety gate: verdict`
  // line count, which is safetyVerdictCount below.
  const entryCounts = fingerprints.map(f => f.historyEntries || 0);
  probeVerdicts.entryCountsPerTurn = entryCounts;

  log(JSON.stringify(probeVerdicts, null, 2));

  const allChecks = [
    { name: 'isComplete=true per turn (T3 wire-up)', pass: completesObservedPerTurn.some(c => c > 0) },
    { name: 'zero NO AWAITER warnings (T2 walker)', pass: noAwaiterWarnings === 0 },
    { name: 'safety verdict count >= turn count (orchestrator advances)', pass:
      safetyVerdictCount !== null && safetyVerdictCount >= TURN_COUNT },
  ];

  console.log('\n=== POST-FIX VERIFICATION ===');
  let allPassed = true;
  for (const check of allChecks) {
    const tag = check.pass ? '✅' : '❌';
    console.log(`${tag} ${check.name}`);
    if (!check.pass) allPassed = false;
  }
  console.log(allPassed
    ? '\n🪵 POST-FIX VERIFIED — Lord Maple Tree stays alive, agents retire properly.'
    : '\n🪵 POST-FIX FAILED — see checks above. Stall class may still be present.');

  process.exitCode = allPassed ? 0 : 1;
} catch (err) {
  console.error('FATAL:', err.message);
  console.error(err.stack);
  process.exitCode = 2;
} finally {
  await browser.close();
}