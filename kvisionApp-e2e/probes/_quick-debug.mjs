import { chromium } from 'playwright';
const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
page.on('console', (m) => console.log('  ', m.type(), m.text().slice(0, 120)));
page.on('pageerror', (e) => console.log('  pageerror', e.message));
await page.goto('http://localhost:8080/', { waitUntil: 'networkidle' });
await page.waitForTimeout(2500);
const enterBtn = page.getByRole('button', { name: /click to enter/i });
if (await enterBtn.count() > 0) { await enterBtn.click(); await page.waitForTimeout(4000); }
const guestBtn = page.locator('[data-testid="login-as-guest"]');
if (await guestBtn.count() > 0) { await guestBtn.click(); await page.waitForTimeout(8000); }
const okBtn = page.getByRole('button', { name: /^ok$/i });
if (await okBtn.count() > 0) { await okBtn.first().click(); await page.waitForTimeout(2000); }
await page.waitForTimeout(3000);
// Dump the live DOM shape relevant to gameplay
const probe = await page.evaluate(() => {
  return {
    bodyClass: document.body.className,
    visibleButtons: Array.from(document.querySelectorAll('button')).slice(0, 30).map(b => b.textContent.trim().slice(0, 30)),
    visibleInputs: Array.from(document.querySelectorAll('input,textarea')).slice(0, 20).map(i => ({ tag: i.tagName, placeholder: (i.placeholder||'').slice(0, 40), className: i.className })),
    dataTestIds: Array.from(document.querySelectorAll('[data-testid]')).slice(0, 30).map(e => e.dataset.testid),
  };
});
console.log('=== DOM probe at post-login ===');
console.log(JSON.stringify(probe, null, 2));
await page.screenshot({ path: '/tmp/autogenesis-stall-recovery/03-post-login.png' });
await browser.close();