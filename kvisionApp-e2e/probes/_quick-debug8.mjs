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

const probe = await page.evaluate(() => {
  const w = window;
  return {
    gameplayUI: typeof w.gameplayUI,
    gameplayUIKeys: w.gameplayUI ? Object.keys(w.gameplayUI).slice(0, 30) : null,
    gameplayUIHasWorld: w.gameplayUI && 'world' in w.gameplayUI,
    gameplayWorld: typeof w.gameplayWorld,
    gameplayWorldKeys: w.gameplayWorld ? Object.keys(w.gameplayWorld).slice(0, 30) : null,
    gameplayWorldDataType: w.gameplayWorld && w.gameplayWorld.worldData ? typeof w.gameplayWorld.worldData : null,
  };
});
console.log('=== probe ===');
console.log(JSON.stringify(probe, null, 2));
await browser.close();