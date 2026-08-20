import { chromium } from 'playwright';
const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
page.on('console', (m) => {
  const t = m.text();
  if (t.includes('error') || t.includes('Error') || t.includes('command-box') || t.includes('cmd-box') || t.includes('CommandBox')) {
    console.log('  ', m.type(), t.slice(0, 120));
  }
});
await page.goto('http://localhost:8080/', { waitUntil: 'networkidle' });
await page.waitForTimeout(2500);
const enterBtn = page.getByRole('button', { name: /click to enter/i });
if (await enterBtn.count() > 0) { await enterBtn.click(); await page.waitForTimeout(4000); }
const guestBtn = page.locator('[data-testid="login-as-guest"]');
if (await guestBtn.count() > 0) { await guestBtn.click(); await page.waitForTimeout(8000); }
const okBtn = page.getByRole('button', { name: /^ok$/i });
if (await okBtn.count() > 0) { await okBtn.first().click(); await page.waitForTimeout(2000); }
await page.locator('[data-testid="main-menu"]').waitFor({ timeout: 15000 });
await page.getByRole('button', { name: /new commander/i }).first().click();
await page.waitForTimeout(1500);
const nameInput = page.locator('input[type="text"]').first();
await nameInput.fill('Lord Maple Tree');
const createBtn = page.getByRole('button', { name: /^create|save/i });
await createBtn.first().click();
for (let i = 0; i < 20; i++) {
  await page.waitForTimeout(2000);
  const saveDialog = page.locator('.autogenesis-message-box-overlay:has-text("Saving")');
  if (await saveDialog.count() === 0) break;
}
const okAfterSave = page.getByRole('button', { name: /^ok$/i });
if (await okAfterSave.count() > 0) { await okAfterSave.first().click(); await page.waitForTimeout(2000); }
await page.getByRole('button', { name: /^play$/i }).first().click({ force: true });
await page.waitForTimeout(2500);
const singleBtn = page.getByRole('button', { name: /single\s*player|vs\s*ai|1\s*v\s*1/i }).first();
if (await singleBtn.count() > 0) { await singleBtn.click({ force: true }); await page.waitForTimeout(2500); }
const oneVOneBtn = page.getByRole('button', { name: /1\s*v\s*1.*ai/i }).first();
if (await oneVOneBtn.count() > 0) { await oneVOneBtn.click({ force: true }); await page.waitForTimeout(2500); }
const startBtn = page.getByRole('button', { name: /start|begin|launch/i }).first();
if (await startBtn.count() > 0) { await startBtn.click({ force: true }); await page.waitForTimeout(5000); }
for (let i = 0; i < 5; i++) {
  const overlay = page.locator('.autogenesis-message-box-overlay');
  if (await overlay.count() > 0) {
    const ok = overlay.getByRole('button', { name: /^ok$/i });
    if (await ok.count() > 0) { await ok.first().click({ force: true }); await page.waitForTimeout(800); }
  } else break;
}
await page.waitForTimeout(3000);
const probe = await page.evaluate(() => ({
  bodyClass: document.body.className,
  buttons: Array.from(document.querySelectorAll('button')).slice(0, 60).map(b => ({ text: (b.textContent || '').trim().slice(0, 30), className: b.className.slice(0, 60), disabled: b.disabled })),
  textareas: Array.from(document.querySelectorAll('textarea')).slice(0, 10).map(t => ({ placeholder: (t.placeholder || '').slice(0, 80), visible: t.offsetParent !== null, className: t.className.slice(0, 60) })),
  dataTestIds: Array.from(document.querySelectorAll('[data-testid]')).slice(0, 30).map(e => e.dataset.testid),
}));
console.log('=== DOM probe at gameplay screen ===');
console.log(JSON.stringify(probe, null, 2));
await page.screenshot({ path: '/tmp/autogenesis-stall-recovery/03d-gameplay-dom.png' });
await browser.close();