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
// Click PLAY (the CommanderSelectionDialog should auto-open)
await page.getByRole('button', { name: /^play$/i }).first().click({ force: true });
await page.waitForTimeout(3000);
// Inspect the dialog: what commander cards are visible?
const probe = await page.evaluate(() => {
  const dialog = document.querySelector('[data-testid="commander-selection-root"]');
  if (!dialog) return { error: 'no dialog' };
  const cards = Array.from(dialog.querySelectorAll('.commander-selection-card')).map(c => ({
    text: c.textContent.trim().slice(0, 80),
    classes: c.className.slice(0, 80),
    attributes: Array.from(c.attributes).map(a => `${a.name}=${a.value}`).slice(0, 6),
  }));
  return { cardCount: cards.length, cards };
});
console.log('=== commander selection dialog ===');
console.log(JSON.stringify(probe, null, 2));
await page.screenshot({ path: '/tmp/autogenesis-stall-recovery/_debug7.png' });
await browser.close();
