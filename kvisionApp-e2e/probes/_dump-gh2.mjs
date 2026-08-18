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
// Submit a turn
const ta = page.locator('.command-box textarea').first();
await ta.waitFor({ timeout: 10000 });
await ta.fill('Test turn advance');
await page.evaluate(() => {
  for (const b of document.querySelectorAll('button')) {
    if (b.textContent.trim().toUpperCase() === 'SEND') { b.click(); return; }
  }
});
// Wait for the turn to resolve and history to populate
await sleep(60000);
const probe = await page.evaluate(() => {
  const ghWindow = document.querySelectorAll('.login-widget-window');
  const detailCards = document.querySelectorAll('.gh-detail-card');
  const storyPanel = document.querySelector('.gh-stack-container');
  return {
    loginWidgetWindowCount: ghWindow.length,
    ghDetailCardCount: detailCards.length,
    storyPanelHTML: storyPanel ? storyPanel.innerHTML.substring(0, 1500) : 'no story panel',
    storyPanelText: storyPanel ? storyPanel.textContent.substring(0, 500) : 'no story panel',
    allClasses: Array.from(new Set(Array.from(document.querySelectorAll('*')).flatMap(el => typeof el.className === 'string' ? el.className.split(' ') : []))).filter(c => c.startsWith('gh') || c.includes('history') || c.includes('turn-')).sort(),
  };
});
console.log(JSON.stringify(probe, null, 2));
await browser.close();
