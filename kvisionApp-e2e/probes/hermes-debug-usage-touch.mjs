// Test actual touch/wheel scrolling on the usage modal
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

// Check what element captures wheel events
const targets = await page.evaluate(() => {
  const modalBody = document.querySelector(".modal.billing-modal-window-host .modal-body.billing-modal-body");
  const contentRoot = document.querySelector(".billing-modal-content-root");
  const modalContent = document.querySelector(".modal.billing-modal-window-host .modal-content");
  const modalDialog = document.querySelector(".modal.billing-modal-window-host .modal-dialog");
  const modalHost = document.querySelector(".modal.billing-modal-window-host");

  // For each, check pointer-events, position, and whether it covers the visible viewport
  const isScrollable = (el) => {
    if (!el) return false;
    const cs = getComputedStyle(el);
    return (cs.overflowY === "auto" || cs.overflowY === "scroll") && el.scrollHeight > el.clientHeight;
  };

  // Find element at viewport center
  const cx = 195, cy = 422;
  const elementAtCenter = document.elementFromPoint(cx, cy);

  return {
    modalBody: modalBody ? {
      tag: modalBody.tagName,
      classes: modalBody.className,
      overflowY: getComputedStyle(modalBody).overflowY,
      scrollH: modalBody.scrollHeight,
      clientH: modalBody.clientHeight,
      pointerEvents: getComputedStyle(modalBody).pointerEvents,
      isScrollable: isScrollable(modalBody),
      rect: modalBody.getBoundingClientRect(),
    } : null,
    contentRoot: contentRoot ? {
      tag: contentRoot.tagName,
      classes: contentRoot.className,
      overflowY: getComputedStyle(contentRoot).overflowY,
      scrollH: contentRoot.scrollHeight,
      clientH: contentRoot.clientHeight,
      pointerEvents: getComputedStyle(contentRoot).pointerEvents,
      isScrollable: isScrollable(contentRoot),
      rect: contentRoot.getBoundingClientRect(),
    } : null,
    modalContent: modalContent ? {
      overflowY: getComputedStyle(modalContent).overflowY,
      scrollH: modalContent.scrollHeight,
      clientH: modalContent.clientHeight,
      isScrollable: isScrollable(modalContent),
    } : null,
    elementAtCenter: elementAtCenter ? {
      tag: elementAtCenter.tagName,
      classes: elementAtCenter.className,
      inContentRoot: !!elementAtCenter.closest(".billing-modal-content-root"),
      inModalBody: !!elementAtCenter.closest(".billing-modal-body"),
    } : null,
  };
});

console.log(JSON.stringify(targets, null, 2));

// Try actual touch-scroll using the touchscreen API
const startScrollTop = await page.evaluate(() => document.querySelector(".billing-modal-content-root").scrollTop);
// dispatchTouchEvent is the proper way; but playwright's touchscreen doesn't expose swipe easily
// Let's use the Wheel event instead
const beforeWheel = startScrollTop;
await page.mouse.wheel(0, 500);
await page.waitForTimeout(500);
const afterWheel = await page.evaluate(() => document.querySelector(".billing-modal-content-root").scrollTop);
console.log(`startScrollTop=${startScrollTop}, afterWheel=${afterWheel}`);

// Also try a synthetic touch sequence
console.log("\n--- Touch dispatch via CDP ---");
const client = await ctx.newCDPSession(page);
await client.send("Input.dispatchTouchEvent", {
  type: "touchStart",
  touchPoints: [{ x: 195, y: 600 }],
});
for (let i = 0; i < 20; i++) {
  await client.send("Input.dispatchTouchEvent", {
    type: "touchMove",
    touchPoints: [{ x: 195, y: 600 - i * 20 }],
  });
  await page.waitForTimeout(20);
}
await client.send("Input.dispatchTouchEvent", {
  type: "touchEnd",
  touchPoints: [],
});
await page.waitForTimeout(500);
const afterTouch = await page.evaluate(() => document.querySelector(".billing-modal-content-root").scrollTop);
console.log(`afterCDPTouch=${afterTouch}`);

// Capture after-touch screenshot
await page.screenshot({ path: "/tmp/usage-modal-after-touch.png" });

await browser.close();
