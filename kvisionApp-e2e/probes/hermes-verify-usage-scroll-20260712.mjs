// Usage modal scroll behavior + tab strip layout at 390x844.
// Follows the canonical survey's data-testid="main-menu" scope pattern.

import { chromium } from "playwright";

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 3,
  isMobile: true,
  hasTouch: true,
});
const page = await context.newPage();

await page.goto("http://127.0.0.1:8080/index.html?skipLogin=true", { waitUntil: "domcontentloaded", timeout: 30000 });
await page.waitForTimeout(8000);
await page.screenshot({ path: "/tmp/usage-modal-preboot.png" });

// Click loading-screen CTA
const cta = await page.locator('[data-testid="loading-screen-cta"]').first();
if (await cta.count() > 0) { await cta.scrollIntoViewIfNeeded(); await cta.click(); }
try {
  await page.waitForSelector('[data-testid="main-menu"]', { timeout: 45000 });
} catch {
  console.log("main-menu never appeared, current state:");
  console.log(await page.evaluate(() => document.body.innerText.slice(0, 500)));
  await page.screenshot({ path: "/tmp/usage-modal-bootfail.png" });
  process.exit(1);
}
await page.waitForTimeout(1500);

// Click USAGE button (📊 Usage)
await page.locator('[data-testid="main-menu"] button:has-text("Usage")').click();
await page.waitForSelector('.modal.billing-modal-window-host', { timeout: 10000 });
await page.waitForTimeout(1500);

// Screenshot
await page.screenshot({ path: "/tmp/usage-modal-current.png" });

// Probe modal scroll container
const probe = await page.evaluate(() => {
  const modalHost = document.querySelector(".modal.billing-modal-window-host");
  const modalDialog = document.querySelector(".modal.billing-modal-window-host .modal-dialog");
  const modalContent = document.querySelector(".modal.billing-modal-window-host .modal-content");
  const modalBody = document.querySelector(".modal.billing-modal-window-host .modal-body.billing-modal-body");
  const contentRoot = document.querySelector(".billing-modal-content-root");
  const tabs = Array.from(document.querySelectorAll(".usage-tab-strip .billing-tab"));
  const tabStrip = document.querySelector(".usage-tab-strip");

  const measure = (el) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return {
      x: Math.round(r.left), y: Math.round(r.top),
      w: Math.round(r.width), h: Math.round(r.height),
      right: Math.round(r.right), bottom: Math.round(r.bottom),
      scrollH: el.scrollHeight, clientH: el.clientHeight,
      scrollW: el.scrollWidth, clientW: el.clientWidth,
      overflowY: cs.overflowY, overflowX: cs.overflowX,
      flexDirection: cs.flexDirection, flexWrap: cs.flexWrap,
      maxHeight: cs.maxHeight, height: cs.height,
    };
  };

  const tabInfo = tabs.map((t, i) => {
    const r = t.getBoundingClientRect();
    const cs = getComputedStyle(t);
    return {
      idx: i,
      label: t.textContent.trim(),
      x: Math.round(r.left), y: Math.round(r.top),
      w: Math.round(r.width), right: Math.round(r.right),
      flex: cs.flex, minWidth: cs.minWidth, maxWidth: cs.maxWidth,
      padding: cs.padding, fontSize: cs.fontSize,
      boxSizing: cs.boxSizing,
    };
  });

  return {
    viewport: { w: window.innerWidth, h: window.innerHeight },
    modalHost: measure(modalHost),
    modalDialog: measure(modalDialog),
    modalContent: measure(modalContent),
    modalBody: measure(modalBody),
    contentRoot: measure(contentRoot),
    tabStrip: measure(tabStrip),
    tabs: tabInfo,
    modalBodyClasses: modalBody ? modalBody.className : null,
    modalBodyParent: modalBody ? modalBody.parentElement.className : null,
    historyHostExists: !!document.querySelector(".usage-history-list"),
    historyCardsCount: document.querySelectorAll(".usage-history-row").length,
    planStripExists: !!document.querySelector(".usage-plan-strip"),
    kpiTilesCount: document.querySelectorAll(".usage-kpi-tile").length,
    meterCardExists: !!document.querySelector(".usage-meter-card"),
  };
});

console.log(JSON.stringify(probe, null, 2));

// Try scrolling the modal
const scrollAttempt = await page.evaluate(async () => {
  const modalBody = document.querySelector(".modal.billing-modal-window-host .modal-body.billing-modal-body");
  const contentRoot = document.querySelector(".billing-modal-content-root");
  const modalContent = document.querySelector(".modal.billing-modal-window-host .modal-content");
  const modalDialog = document.querySelector(".modal.billing-modal-window-host .modal-dialog");
  const modalHost = document.querySelector(".modal.billing-modal-window-host");

  const before = {
    body: { scrollTop: modalBody?.scrollTop, scrollH: modalBody?.scrollHeight, clientH: modalBody?.clientHeight },
    root: { scrollTop: contentRoot?.scrollTop, scrollH: contentRoot?.scrollHeight, clientH: contentRoot?.clientHeight },
    content: { scrollTop: modalContent?.scrollTop, scrollH: modalContent?.scrollHeight, clientH: modalContent?.clientHeight },
    dialog: { scrollTop: modalDialog?.scrollTop, scrollH: modalDialog?.scrollHeight, clientH: modalDialog?.clientHeight },
    host: { scrollTop: modalHost?.scrollTop, scrollH: modalHost?.scrollHeight, clientH: modalHost?.clientHeight },
  };

  // Try to scroll each
  if (modalBody) modalBody.scrollTop = 9999;
  if (contentRoot) contentRoot.scrollTop = 9999;
  if (modalContent) modalContent.scrollTop = 9999;
  if (modalDialog) modalDialog.scrollTop = 9999;
  if (modalHost) modalHost.scrollTop = 9999;

  await new Promise(r => setTimeout(r, 200));

  const after = {
    body: { scrollTop: modalBody?.scrollTop, scrollH: modalBody?.scrollHeight, clientH: modalBody?.clientHeight },
    root: { scrollTop: contentRoot?.scrollTop, scrollH: contentRoot?.scrollHeight, clientH: contentRoot?.clientHeight },
    content: { scrollTop: modalContent?.scrollTop, scrollH: modalContent?.scrollHeight, clientH: modalContent?.clientHeight },
    dialog: { scrollTop: modalDialog?.scrollTop, scrollH: modalDialog?.scrollHeight, clientH: modalDialog?.clientHeight },
    host: { scrollTop: modalHost?.scrollTop, scrollH: modalHost?.scrollHeight, clientH: modalHost?.clientHeight },
  };

  return { before, after };
});
console.log("\nSCROLL ATTEMPT:");
console.log(JSON.stringify(scrollAttempt, null, 2));

// Screenshot AFTER scrolling
await page.screenshot({ path: "/tmp/usage-modal-scrolled.png" });

await browser.close();
