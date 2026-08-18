// Drill into why tab buttons are 72px instead of ~80px (filling 321px / 4)
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
await page.waitForTimeout(1500);

const drilldown = await page.evaluate(() => {
  const tabs = Array.from(document.querySelectorAll(".usage-tab-strip .billing-tab"));
  const tabStrip = document.querySelector(".usage-tab-strip");
  return {
    tabStripOuter: { w: tabStrip.offsetWidth, h: tabStrip.offsetHeight, paddingLeft: getComputedStyle(tabStrip).paddingLeft, paddingRight: getComputedStyle(tabStrip).paddingRight },
    buttons: tabs.map((t, i) => {
      const cs = getComputedStyle(t);
      return {
        idx: i,
        label: t.textContent.trim(),
        offsetWidth: t.offsetWidth,
        offsetHeight: t.offsetHeight,
        clientWidth: t.clientWidth,
        scrollWidth: t.scrollWidth,
        inlineStyle: t.getAttribute("style") || "",
        computedWidth: cs.width,
        computedFlex: cs.flex,
        computedFlexGrow: cs.flexGrow,
        computedFlexShrink: cs.flexShrink,
        computedFlexBasis: cs.flexBasis,
        computedMinWidth: cs.minWidth,
        computedMaxWidth: cs.maxWidth,
        computedBoxSizing: cs.boxSizing,
        computedPadding: cs.padding,
      };
    }),
  };
});

console.log(JSON.stringify(drilldown, null, 2));
await browser.close();