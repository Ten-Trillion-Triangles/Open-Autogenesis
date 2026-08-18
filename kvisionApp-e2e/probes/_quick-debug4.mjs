import { chromium } from 'playwright';
const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
page.on('console', (m) => { const t = m.text(); if (t.includes('error') || t.includes('UI') || t.includes('login') || t.includes('Login')) console.log('  ', m.type(), t.slice(0, 150)); });
await page.goto('http://localhost:8080/', { waitUntil: 'networkidle' });
await page.waitForTimeout(3000);
const enterBtn = page.getByRole('button', { name: /click to enter/i });
if (await enterBtn.count() > 0) { await enterBtn.click(); await page.waitForTimeout(4000); }
const guestBtn = page.locator('[data-testid="login-as-guest"]');
if (await guestBtn.count() > 0) { await guestBtn.click(); await page.waitForTimeout(12000); }
const okBtn = page.getByRole('button', { name: /^ok$/i });
if (await okBtn.count() > 0) { await okBtn.first().click(); await page.waitForTimeout(3000); }
await page.waitForTimeout(3000);
const probe = await page.evaluate(() => {
  const w = window;
  return {
    gameplayUI: typeof w.gameplayUI,
    testIds: Array.from(document.querySelectorAll('[data-testid]')).map(e => e.dataset.testid),
    bodyClass: document.body.className,
  };
});
console.log('=== post-login probe ===');
console.log(JSON.stringify(probe, null, 2));
await page.screenshot({ path: '/tmp/autogenesis-stall-recovery/_debug4.png' });
await browser.close();
