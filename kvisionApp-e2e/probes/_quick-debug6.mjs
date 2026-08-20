import { chromium } from 'playwright';
const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
page.on('console', (m) => { const t = m.text(); if (t.includes('updateWorldState') || t.includes('gameplayUI') || t.includes('TurnResolution')) console.log('  ', m.type(), t.slice(0, 200)); });
await page.goto('http://localhost:8080/', { waitUntil: 'networkidle' });
await page.waitForTimeout(3000);
const enterBtn = page.getByRole('button', { name: /click to enter/i });
if (await enterBtn.count() > 0) { await enterBtn.click(); await page.waitForTimeout(4000); }
const guestBtn = page.locator('[data-testid="login-as-guest"]');
if (await guestBtn.count() > 0) { await guestBtn.click(); await page.waitForTimeout(12000); }
const okBtn = page.getByRole('button', { name: /^ok$/i });
if (await okBtn.count() > 0) { await okBtn.first().click(); await page.waitForTimeout(2000); }
await page.locator('[data-testid="main-menu"]').waitFor({ timeout: 15000 });
const resumeDialog = page.locator('[data-testid="resume-or-new-dialog"]');
if (await resumeDialog.count() > 0) {
  const newGameBtn = resumeDialog.getByRole('button', { name: /new game/i }).first();
  if (await newGameBtn.count() > 0) { await newGameBtn.click({ force: true }); await page.waitForTimeout(1500); }
}
await page.getByRole('button', { name: /new commander/i }).first().click();
await page.waitForTimeout(1500);
const nameInput = page.locator('input[type="text"]').first();
await nameInput.fill('DebugCmd');
const descInput = page.locator('textarea[placeholder*="commander" i]').first();
await descInput.fill('Debug commander for probe.');
const nationInput = page.locator('textarea[placeholder*="nation" i]').first();
await nationInput.fill('Debug nation for probe.');
const createBtn = page.getByRole('button', { name: /^create|save/i });
await createBtn.first().click();
for (let i = 0; i < 30; i++) {
  await page.waitForTimeout(1500);
  const saveDialog = page.locator('.autogenesis-message-box-overlay:has-text("Saving")');
  if (await saveDialog.count() === 0) break;
}
const okAfter = page.getByRole('button', { name: /^ok$/i });
if (await okAfter.count() > 0) { await okAfter.first().click(); await page.waitForTimeout(2000); }
await page.getByRole('button', { name: /^play$/i }).first().click({ force: true });
await page.waitForTimeout(3000);
const dialog = page.locator('[data-testid="commander-selection-root"]');
const card = dialog.locator('.commander-selection-card').first();
if (await card.count() > 0) { await card.click({ force: true }); await page.waitForTimeout(1000); }
const nextBtn = dialog.getByRole('button', { name: /^next$/i }).first();
if (await nextBtn.count() > 0) { await nextBtn.click({ force: true }); await page.waitForTimeout(2500); }
const playBtn = dialog.getByRole('button', { name: /^play$/i }).first();
if (await playBtn.count() > 0) { await playBtn.click({ force: true }); await page.waitForTimeout(8000); }
const mr = page.locator('.autogenesis-message-box-overlay:has-text("Match Ready")');
if (await mr.count() > 0) {
  const okOk = mr.getByRole('button', { name: /^ok$/i }).first();
  if (await okOk.count() > 0) { await okOk.click({ force: true }); await page.waitForTimeout(3000); }
}
await page.waitForTimeout(5000);
const probe = await page.evaluate(() => {
  const w = window;
  const gui = w.gameplayUI;
  return {
    hasGlobal: typeof gui !== 'undefined' && gui !== null,
    worldType: gui && typeof gui.world,
    worldValue: gui && gui.world ? { roundNumber: gui.world.roundNumber, activeTurnActor: gui.world.activeTurnActor, turnOrderLen: Array.isArray(gui.world.turnOrder) ? gui.world.turnOrder.length : 'X', turnOrderMembers: Array.isArray(gui.world.turnOrder) ? gui.world.turnOrder.slice(0, 5) : 'X' } : null,
    keys: gui ? Object.keys(gui).slice(0, 40) : [],
    testIds: Array.from(document.querySelectorAll('[data-testid]')).map(e => e.dataset.testid).slice(0, 20),
  };
});
console.log('=== gameplay probe ===');
console.log(JSON.stringify(probe, null, 2));
await page.screenshot({ path: '/tmp/autogenesis-stall-recovery/_debug6.png' });
await browser.close();
