import { chromium } from 'playwright';
const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
await page.goto('http://localhost:8080/', { waitUntil: 'networkidle' });
await page.waitForTimeout(2500);
const enterBtn = page.getByRole('button', { name: /click to enter/i });
if (await enterBtn.count() > 0) { await enterBtn.click(); await page.waitForTimeout(4000); }
const guestBtn = page.locator('[data-testid="login-as-guest"]');
if (await guestBtn.count() > 0) { await guestBtn.click(); await page.waitForTimeout(8000); }
const okBtn = page.getByRole('button', { name: /^ok$/i });
if (await okBtn.count() > 0) { await okBtn.first().click(); await page.waitForTimeout(2000); }
await page.locator('[data-testid="main-menu"]').waitFor({ timeout: 15000 });
await page.waitForTimeout(2000);
// Inspect window.gameplayUI on the actual page
const probe = await page.evaluate(() => {
  const w = window;
  const gui = w.gameplayUI;
  return {
    hasGlobal: typeof gui !== 'undefined' && gui !== null,
    type: typeof gui,
    keys: gui ? Object.keys(gui).slice(0, 30) : [],
    world: gui && gui.world ? { type: typeof gui.world, roundNumber: gui.world.roundNumber, hasTurnOrder: Array.isArray(gui.world.turnOrder), activeTurnActor: gui.world.activeTurnActor } : null,
  };
});
console.log('=== gameplayUI probe ===');
console.log(JSON.stringify(probe, null, 2));
await page.screenshot({ path: '/tmp/autogenesis-stall-recovery/_debug3.png' });
await browser.close();