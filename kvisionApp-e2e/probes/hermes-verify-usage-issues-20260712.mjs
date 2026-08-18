// Verify each of the 21 issues I identified in the screenshots.
// Some may be measurement artifacts, some may be real. Test with DOM measurements
// before applying fixes.

import { chromium } from "playwright";

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 3,
  isMobile: true,
  hasTouch: true,
});
const page = await ctx.newPage();
await page.goto("http://127.0.0.1:8080/index.html?skipLogin=true", { waitUntil: "domcontentloaded" });
await page.waitForTimeout(7000);
await page.locator('[data-testid="loading-screen-cta"]').first().click();
await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 });
await page.waitForTimeout(1500);
await page.locator('[data-testid="main-menu"] button:has-text("Usage")').click();
await page.waitForSelector('.modal.billing-modal-window-host', { timeout: 10000 });
await page.waitForTimeout(2000);

const results = await page.evaluate(() => {
  const out = {};

  // Issue #1: ALL TIME text overflows button
  const allTimeBtn = Array.from(document.querySelectorAll('.usage-tab-strip .billing-tab'))
    .find(b => b.textContent.trim() === "ALL TIME");
  if (allTimeBtn) {
    out.issue1_allTime = {
      btnWidth: allTimeBtn.offsetWidth,
      btnClientWidth: allTimeBtn.clientWidth,
      btnScrollWidth: allTimeBtn.scrollWidth,
      overflowBy: allTimeBtn.scrollWidth - allTimeBtn.clientWidth,
      padding: getComputedStyle(allTimeBtn).padding,
      fontSize: getComputedStyle(allTimeBtn).fontSize,
    };
  }

  // Issue #4: Calendar icon overlapping progress bar
  const meter = document.querySelector('.usage-meter-card');
  const calIcon = document.querySelector('.usage-meter-reset-icon');
  const bar = document.querySelector('.usage-meter-bar-track');
  const barFill = document.querySelector('.usage-meter-bar-fill');
  if (calIcon && bar) {
    const calR = calIcon.getBoundingClientRect();
    const barR = bar.getBoundingClientRect();
    out.issue4_calendar = {
      calIcon: { x: Math.round(calR.left), y: Math.round(calR.top), w: Math.round(calR.width), h: Math.round(calR.height), right: Math.round(calR.right) },
      bar: { x: Math.round(barR.left), y: Math.round(barR.top), w: Math.round(barR.width), h: Math.round(barR.height), right: Math.round(barR.right) },
      overlapsHorizontally: calR.left < barR.right && calR.right > barR.left,
      overlapsVertically: calR.top < barR.bottom && calR.bottom > barR.top,
      calIconParent: calIcon.parentElement.className,
      calIconPosition: getComputedStyle(calIcon).position,
    };
  }

  // Issue #5: Right zone of meter - where is "Today UNTIL RESET" placed
  const meterRight = document.querySelector('.usage-meter-right');
  const meterCenter = document.querySelector('.usage-meter-center');
  const meterLeft = document.querySelector('.usage-meter-left');
  if (meterRight && meterCenter) {
    const rR = meterRight.getBoundingClientRect();
    const cR = meterCenter.getBoundingClientRect();
    out.issue5_rightZone = {
      right: { x: Math.round(rR.left), y: Math.round(rR.top), w: Math.round(rR.width), h: Math.round(rR.height), right: Math.round(rR.right) },
      center: { x: Math.round(cR.left), y: Math.round(cR.top), w: Math.round(cR.width), h: Math.round(cR.height), right: Math.round(cR.right) },
      rightInCard: rR.right <= cR.right,
      rightParentClass: meterRight.parentElement.className,
      rightDisplay: getComputedStyle(meterRight).display,
      rightAlignItems: getComputedStyle(meterRight).alignItems,
    };
  }

  // Issue #8/9/10/11: KPI tiles - check rendering
  const kpiTiles = Array.from(document.querySelectorAll('.usage-kpi-tile'));
  out.issue8_kpiTiles = kpiTiles.map((t, i) => {
    const r = t.getBoundingClientRect();
    const label = t.querySelector('.usage-kpi-label, [class*="label"]');
    const value = t.querySelector('.usage-kpi-value, [class*="value"]');
    const delta = t.querySelector('[class*="delta"]');
    return {
      idx: i,
      tileClass: t.className,
      labelText: label?.textContent?.trim() || null,
      valueText: value?.textContent?.trim() || null,
      deltaText: delta?.textContent?.trim() || null,
      rect: { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) },
    };
  });

  // Issue #12/13/14: ACTIVE PLAN buttons
  const planStrip = document.querySelector('.usage-plan-strip');
  const manageBtn = planStrip?.querySelector('button:not(.usage-plan-upgrade)');
  const upgradeBtn = planStrip?.querySelector('button[class*="upgrade"], .usage-plan-upgrade');
  if (planStrip) {
    const psR = planStrip.getBoundingClientRect();
    out.issue12_planStrip = {
      stripRect: { x: Math.round(psR.left), y: Math.round(psR.top), w: Math.round(psR.width), right: Math.round(psR.right), h: Math.round(psR.height) },
      manageRect: manageBtn ? (() => { const r = manageBtn.getBoundingClientRect(); return { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), right: Math.round(r.right) }; })() : null,
      upgradeRect: upgradeBtn ? (() => { const r = upgradeBtn.getBoundingClientRect(); return { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), right: Math.round(r.right) }; })() : null,
      manageOverflows: manageBtn && manageBtn.getBoundingClientRect().left < psR.left + 2,
      upgradeOverflows: upgradeBtn && upgradeBtn.getBoundingClientRect().right > psR.right - 2,
    };
  }

  // Issue #17: scroll affordance - is there any scrollbar or hint?
  const contentRoot = document.querySelector('.billing-modal-content-root');
  if (contentRoot) {
    const cs = getComputedStyle(contentRoot);
    out.issue17_scrollAffordance = {
      overflowY: cs.overflowY,
      scrollHeight: contentRoot.scrollHeight,
      clientHeight: contentRoot.clientHeight,
      hasScrollbar: contentRoot.scrollHeight > contentRoot.clientHeight + 2,
      overflowContent: contentRoot.scrollHeight - contentRoot.clientHeight,
    };
  }

  // Issue #18: modal header border bleed
  const modalHeader = document.querySelector('.billing-modal-header');
  if (modalHeader) {
    const r = modalHeader.getBoundingClientRect();
    const cs = getComputedStyle(modalHeader);
    out.issue18_headerBorder = {
      rect: { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), right: Math.round(r.right) },
      borderBottom: cs.borderBottom,
      paddingBottom: cs.paddingBottom,
      parentRect: { x: Math.round(modalHeader.parentElement.getBoundingClientRect().left), w: Math.round(modalHeader.parentElement.getBoundingClientRect().width), right: Math.round(modalHeader.parentElement.getBoundingClientRect().right) },
    };
  }

  // Issue #21: ghost border at top of modal
  const modalContent = document.querySelector('.modal.billing-modal-window-host .modal-content');
  const modalBody = document.querySelector('.billing-modal-body');
  if (modalContent && modalBody) {
    const mcR = modalContent.getBoundingClientRect();
    const mbR = modalBody.getBoundingClientRect();
    out.issue21_ghostBorder = {
      modalContent: { x: Math.round(mcR.left), y: Math.round(mcR.top), w: Math.round(mcR.width), border: getComputedStyle(modalContent).border },
      modalBody: { x: Math.round(mbR.left), y: Math.round(mbR.top), w: Math.round(mbR.width), border: getComputedStyle(modalBody).border },
    };
  }

  return out;
});

console.log(JSON.stringify(results, null, 2));

await browser.close();