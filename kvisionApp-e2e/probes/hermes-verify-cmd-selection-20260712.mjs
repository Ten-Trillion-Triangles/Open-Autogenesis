// Probe CommanderSelectionDialog button sizing + card text behavior at 390x844
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
await page.waitForTimeout(8000);
await page.locator('[data-testid="loading-screen-cta"]').first().click();
await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 });
await page.waitForTimeout(1500);

// Need at least one commander first. Click NEW COMMANDER +
await page.locator('[data-testid="main-menu"] button:has-text("New Commander")').click();
await page.waitForTimeout(2500);

// Fill name field
const nameField = page.locator('.commander-creation-dialog input[type="text"]').first();
if (await nameField.count() > 0) {
  await nameField.fill("Test Commander 1");
  await nameField.press("Tab");
}
// Fill nation description (third field per CC inputs)
const descField = page.locator('.commander-creation-dialog textarea').first();
if (await descField.count() > 0) {
  await descField.fill("Test description");
}
// Fill empire description
const empireField = page.locator('.commander-creation-dialog textarea').nth(1);
if (await empireField.count() > 0) {
  await empireField.fill("Test Empire Land");
}
await page.waitForTimeout(500);

// Click CREATE button (scoped to commander-creation-overlay)
const createBtn = page.locator('.commander-creation-overlay button:has-text("Create")');
if (await createBtn.count() > 0) {
  await createBtn.click({ timeout: 10000 });
}
await page.waitForTimeout(4000);

// Dismiss any message-box overlay (success/error popup)
const msgBoxOpen = await page.evaluate(() => !!document.querySelector('.autogenesis-message-box-overlay'));
if (msgBoxOpen) {
  console.log("message-box overlay open, clicking OK");
  await page.locator('.autogenesis-message-box-overlay button:has-text("OK")').click({ timeout: 10000 });
  await page.waitForTimeout(1500);
}

// If overlay still open, force-close by clicking BACK
const overlayStillOpen = await page.evaluate(() => !!document.querySelector('.commander-creation-overlay'));
if (overlayStillOpen) {
  console.log("CC overlay still open, clicking BACK");
  await page.locator('.commander-creation-overlay button:has-text("BACK")').click({ timeout: 10000 });
  await page.waitForTimeout(1500);
}

// Now open PLAY
await page.locator('[data-testid="main-menu"] button:has-text("PLAY")').click();
await page.waitForTimeout(3000);

const probe = await page.evaluate(() => {
  const out = {};

  // Find the dialog
  const dialog = document.querySelector(".commander-selection-window, .commander-selection-overlay");
  if (dialog) {
    const r = dialog.getBoundingClientRect();
    out.dialog = {
      tag: dialog.tagName,
      classes: dialog.className,
      rect: { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), right: Math.round(r.right), h: Math.round(r.height) },
    };
  }

  // Footer buttons (Cancel + Next)
  const buttons = Array.from(document.querySelectorAll(".commander-selection-window button, .commander-selection-overlay button"));
  out.buttons = buttons.map((b, i) => {
    const r = b.getBoundingClientRect();
    const cs = getComputedStyle(b);
    return {
      idx: i,
      text: b.textContent.trim(),
      visible: r.width > 0 && r.height > 0,
      disabled: b.disabled,
      display: cs.display,
      rect: { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), right: Math.round(r.right), h: Math.round(r.height) },
      inlineStyle: b.getAttribute("style") || "",
      computedWidth: cs.width,
      computedFlex: cs.flex,
      computedPadding: cs.padding,
    };
  });

  // Commander cards
  const cards = Array.from(document.querySelectorAll(".commander-selection-card"));
  out.cards = cards.map((c, i) => {
    const r = c.getBoundingClientRect();
    const cs = getComputedStyle(c);
    const nameEl = c.querySelector(".commander-selection-card-name, h3, h4");
    const empireEl = c.querySelector(".commander-selection-card-meta, .commander-selection-card-description");
    return {
      idx: i,
      rect: { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), right: Math.round(r.right), h: Math.round(r.height) },
      inlineStyle: c.getAttribute("style") || "",
      nameText: nameEl?.textContent?.trim() || null,
      empireText: empireEl?.textContent?.trim() || null,
      nameScrollWidth: nameEl?.scrollWidth || null,
      nameClientWidth: nameEl?.clientWidth || null,
      empireScrollWidth: empireEl?.scrollWidth || null,
      empireClientWidth: empireEl?.clientWidth || null,
    };
  });

  // Footer hPanel parent
  const footerParent = buttons[0]?.parentElement;
  if (footerParent) {
    const fr = footerParent.getBoundingClientRect();
    out.footerPanel = {
      classes: footerParent.className,
      rect: { x: Math.round(fr.left), y: Math.round(fr.top), w: Math.round(fr.width), right: Math.round(fr.right), h: Math.round(fr.height) },
      flexDirection: getComputedStyle(footerParent).flexDirection,
      flexWrap: getComputedStyle(footerParent).flexWrap,
      gap: getComputedStyle(footerParent).gap,
      display: getComputedStyle(footerParent).display,
    };
  }

  // Modal panel container
  const panel = document.querySelector(".commander-selection-window");
  if (panel) {
    const pr = panel.getBoundingClientRect();
    out.modalPanel = {
      rect: { x: Math.round(pr.left), y: Math.round(pr.top), w: Math.round(pr.width), right: Math.round(pr.right), h: Math.round(pr.height) },
      padding: getComputedStyle(panel).padding,
    };
  }

  return out;
});

console.log(JSON.stringify(probe, null, 2));
await page.screenshot({ path: "/tmp/cmd-selection-pre.png" });
await browser.close();