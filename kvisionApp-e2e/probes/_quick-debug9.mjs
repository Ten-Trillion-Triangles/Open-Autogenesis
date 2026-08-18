import { chromium } from 'playwright';
const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
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
const mainMenuProbe = await page.evaluate(() => {
  const w = window;
  return { gameplayUI: typeof w.gameplayUI, gameplayWorld: typeof w.gameplayWorld };
});
console.log('=== MainMenu probe ===');
console.log(JSON.stringify(mainMenuProbe));
// Now navigate through to GameplayUI
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
await page.waitForTimeout(3000);
const gameplayProbe = await page.evaluate(() => {
  const w = window;
  return {
    gameplayUI: typeof w.gameplayUI,
    gameplayUIKeys: w.gameplayUI ? Object.keys(w.gameplayUI).slice(0, 30) : null,
    gameplayUIHasWorld: w.gameplayUI && 'world' in w.gameplayUI,
    gameplayWorld: typeof w.gameplayWorld,
    gameplayWorldKeys: w.gameplayWorld ? Object.keys(w.gameplayWorld).slice(0, 30) : null,
    worldDataType: w.gameplayWorld && w.gameplayWorld.worldData ? typeof w.gameplayWorld.worldData : null,
    worldRoundNumber: w.gameplayWorld && w.gameplayWorld.worldData ? w.gameplayWorld.worldData.roundNumber : null,
  };
});
console.log('=== GameplayUI probe ===');
console.log(JSON.stringify(gameplayProbe, null, 2));
await page.screenshot({ path: '/tmp/autogenesis-stall-recovery/_debug8.png' });
await browser.close();