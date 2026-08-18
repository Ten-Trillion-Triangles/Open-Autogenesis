import { chromium } from 'playwright';
import { setTimeout as sleep } from 'node:timers/promises';

const browser = await chromium.launchPersistentContext('/tmp/ag-profile-guest', {
  headless: true, viewport: { width: 1920, height: 1080 }, args: ['--no-sandbox'],
});
const page = await browser.newPage();
await page.goto('http://localhost:8080/', { waitUntil: 'load' });
await sleep(3000);
await page.locator('[data-testid="loading-screen-cta"]').click();
await sleep(3000);
await page.locator('[data-testid="login-as-guest"]').click();
await sleep(5000);
await page.waitForSelector('.autogenesis-message-box-overlay', { timeout: 30000 });
await page.locator('.autogenesis-message-box-overlay button:has-text("OK")').first().click({ force: true });
await sleep(3000);
await page.waitForSelector('[data-testid="main-menu"]', { timeout: 90000 });
await page.evaluate(() => {
  for (const b of document.querySelectorAll('[data-testid="main-menu"] button')) {
    if (b.textContent.trim() === 'PLAY') { b.click(); return; }
  }
});
await page.waitForSelector('.commander-selection-card', { timeout: 15000 });
await page.locator('.commander-selection-card').first().click({ force: true });
await sleep(2500);
await page.locator('[data-testid="commander-selection-root"] button:has-text("Next")').first().click({ force: true });
await sleep(2500);
await page.locator('.commander-selection-card:has-text("Play vs. AI locally")').first().click({ force: true });
await sleep(1500);
await page.locator('[data-testid="commander-selection-root"] button:has-text("Play")').first().click({ force: true });
await page.waitForSelector('.gameplay-ui', { timeout: 60000 });
await page.waitForSelector('.autogenesis-message-box-overlay:has-text("Match Ready")', { timeout: 10000 }).catch(() => {});
for (let i = 0; i < 10; i++) {
  const overlay = page.locator('.autogenesis-message-box-overlay:has-text("Match Ready")');
  if (await overlay.count() === 0) break;
  await page.locator('.autogenesis-message-box-overlay:has-text("Match Ready") button:has-text("OK")').first().click({ force: true });
  await sleep(3000);
}
await sleep(8000);
const probe = await page.evaluate(() => {
  const ghWindow = document.querySelector('.login-widget-window');
  const allClasses = new Set();
  if (ghWindow) {
    ghWindow.querySelectorAll('*').forEach(el => {
      if (el.className && typeof el.className === 'string') {
        el.className.split(' ').forEach(c => allClasses.add(c));
      }
    });
  }
  return {
    classCounts: {
      'gh-detail-card': document.querySelectorAll('.gh-detail-card').length,
      'gh-header-container': document.querySelectorAll('.gh-header-container').length,
      'gh-stack-container': document.querySelectorAll('.gh-stack-container').length,
      'login-widget-window': document.querySelectorAll('.login-widget-window').length,
      'gh-tab-button': document.querySelectorAll('.gh-tab-button').length,
      'gh-tab-button-active': document.querySelectorAll('.gh-tab-button-active').length,
    },
    allUniqueClasses: Array.from(allClasses).sort(),
    tabActive: document.querySelector('.gh-tab-button-active')?.textContent.trim(),
    panelContentSample: ghWindow ? ghWindow.textContent.substring(0, 300) : 'no panel',
  };
});
console.log(JSON.stringify(probe, null, 2));
await browser.close();
