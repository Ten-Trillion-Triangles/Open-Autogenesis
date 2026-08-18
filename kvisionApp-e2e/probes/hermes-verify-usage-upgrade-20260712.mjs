// Find the upgrade button correctly + check its position vs plan strip
import { chromium } from "playwright";
const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true });
const page = await ctx.newPage();
await page.goto("http://127.0.0.1:8080/index.html?skipLogin=true", { waitUntil: "domcontentloaded" });
await page.waitForTimeout(7000);
await page.locator('[data-testid="loading-screen-cta"]').first().click();
await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 });
await page.waitForTimeout(1500);
await page.locator('[data-testid="main-menu"] button:has-text("Usage")').click();
await page.waitForSelector('.modal.billing-modal-window-host', { timeout: 10000 });
await page.waitForTimeout(2000);

const plan = await page.evaluate(() => {
  const planStrip = document.querySelector('.usage-plan-strip');
  if (!planStrip) return null;

  const buttons = Array.from(planStrip.querySelectorAll('button'));
  const planR = planStrip.getBoundingClientRect();

  return {
    planStrip: { x: Math.round(planR.left), w: Math.round(planR.width), right: Math.round(planR.right), h: Math.round(planR.height) },
    buttons: buttons.map((b, i) => {
      const r = b.getBoundingClientRect();
      const cs = getComputedStyle(b);
      return {
        idx: i,
        text: b.textContent.trim(),
        className: b.className,
        rect: { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), right: Math.round(r.right), bottom: Math.round(r.bottom) },
        overflowsLeft: r.left < planR.left + 1,
        overflowsRight: r.right > planR.right - 1,
        padding: cs.padding,
        flex: cs.flex,
        position: cs.position,
      };
    }),
  };
});

console.log(JSON.stringify(plan, null, 2));
await browser.close();