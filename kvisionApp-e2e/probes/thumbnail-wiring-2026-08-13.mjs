// Map card thumbnail verification probe (2026-08-13):
//
// After "Map uploaded" lands, the catalogue's Maps tab MUST render
// a card whose `.co-thumb` div has a `data:image/png;base64,...`
// `background-image` URL and NO placeholder `<i class="fas fa-map">`
// child. Without the wiring the card shows a giant blank solid-color
// block (the operator's reported symptom on 2026-08-13).
//
// The probe:
//   1. Uploads a real `.map` pack via the live gate.
//   2. Waits for Map.Upload.Success notification.
//   3. Polls the .co-thumb divs for `data:image/png;base64,` in their
//      computed background-image.
//   4. Asserts at least one card passes the contract.

import { chromium } from 'playwright';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';
import { writeFileSync, mkdirSync, existsSync } from 'fs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const BASE_URL = 'http://localhost:8080';
const ARTIFACTS_DIR = resolve(__dirname, '../artifacts-thumbnail-wiring-2026-08-13');
mkdirSync(ARTIFACTS_DIR, { recursive: true });

const REALISTIC_MAP_PATH = resolve(__dirname, '../tests/fixtures/realistic-map.map');
const results = { assertions: {}, phases: [], errors: [] };

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();

  const consoleLog = [];
  page.on('console', (m) => consoleLog.push(`[${m.type()}] ${m.text()}`));
  page.on('pageerror', (e) => results.errors.push(`PAGE_ERROR: ${e.message}`));

  try
  {
    if (!existsSync(REALISTIC_MAP_PATH))
    {
      throw new Error(`Realistic map fixture missing at ${REALISTIC_MAP_PATH}`);
    }

    await page.goto(`${BASE_URL}/?skipLogin=true`);
    await page.click('[data-testid="loading-screen-cta"]');
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 });
    results.phases.push('phase-0-loaded');
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '00-main-menu.png') });

    // Open Collection overlay → Maps tab.
    await page.locator('.main-menu button:has-text("Collection")').first().click();
    await page.waitForSelector('.collection-overlay', { timeout: 10000 });
    await page.click(".collection-tab-button[title='Maps']");
    await page.waitForTimeout(500);
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '01-collection-maps-before.png') });

    // Capture the empty-state thumb before upload.
    const beforeUploadThumbState = await page.evaluate(() => {
      const thumbs = Array.from(document.querySelectorAll('.collection-overlay [data-testid^="map-card-thumb-"]'));
      return thumbs.map((t) => {
        const style = window.getComputedStyle(t);
        const icon = t.querySelector('i');
        return {
          backgroundImage: style.backgroundImage,
          hasIcon: !!icon,
          iconClass: icon ? icon.className : '',
        };
      });
    });
    results.assertions.beforeUploadThumbState = beforeUploadThumbState;

    // Trigger upload.
    await page.click('[data-testid="maps-upload-button"]');
    await page.waitForSelector('[data-testid="map-upload-modal"]', { timeout: 10000 });
    await page.setInputFiles('[data-testid="map-upload-file-input"]', REALISTIC_MAP_PATH);
    await page.waitForTimeout(500);
    await page.fill('[data-testid="map-upload-name-input"]', 'Thumbnail Wiring Probe');
    await page.click('[data-testid="map-upload-publish"]');
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '02-publishing.png') });

    // Wait for terminal outcome.
    let outcomeTitle = '';
    let waitDeadline = Date.now() + 120_000;
    while (Date.now() < waitDeadline)
    {
      outcomeTitle = await page.evaluate(() => {
        const overlay = document.querySelector('.autogenesis-message-box-overlay');
        if (!overlay) return '';
        const h = overlay.querySelector('h3');
        return h ? h.textContent || '' : '';
      });
      const lower = outcomeTitle.toLowerCase();
      if (lower.includes('upload successful') ||
          lower.includes('upload failed') ||
          lower.includes('map uploaded'))
      {
        break;
      }
      await page.waitForTimeout(500);
    }
    results.assertions.outcomeTitle = outcomeTitle;
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '03-outcome.png') });

    // Dismiss any leftover MessageBoxes so the overlay is clickable.
    await page.evaluate(() => {
      document.querySelectorAll('.autogenesis-message-box-overlay').forEach((o) => {
        o.style.display = 'none';
      });
    });
    await page.waitForTimeout(500);

    // Poll the .co-thumb divs for the data URL contract.
    let foundThumbWithDataUrl = false;
    let polledDeadline = Date.now() + 30_000;
    let lastPoll = null;
    let lastInlineStyle = null;
    while (Date.now() < polledDeadline)
    {
      const poll = await page.evaluate(() => {
        const thumbs = Array.from(document.querySelectorAll('.collection-overlay [data-testid^="map-card-thumb-"]'));
        return thumbs.map((t) => {
          const style = window.getComputedStyle(t);
          const bgImg = style.backgroundImage;
          const hasDataUrl = bgImg.includes('data:image/png;base64,');
          const icon = t.querySelector('i');
          return {
            backgroundImage: bgImg.slice(0, 90),
            hasDataUrl,
            hasIcon: !!icon,
            // Inline style attribute — what the renderer actually set.
            inlineBackground: (t.getAttribute('style') || '').slice(0, 200)
          };
        });
      });
      lastPoll = poll;
      if (poll.length > 0)
      {
        lastInlineStyle = poll[0].inlineBackground;
      }
      if (poll.some((p) => p.hasDataUrl))
      {
        foundThumbWithDataUrl = true;
        results.assertions.thumbPollWinning = poll;
        break;
      }
      await page.waitForTimeout(500);
    }
    results.assertions.foundThumbWithDataUrl = foundThumbWithDataUrl;
    results.assertions.lastThumbPoll = lastPoll;
    results.assertions.firstInlineStyle = lastInlineStyle;
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '04-after-upload.png') });

    const pass = [
      results.assertions.outcomeTitle &&
        (results.assertions.outcomeTitle.toLowerCase().includes('upload successful') ||
         results.assertions.outcomeTitle.toLowerCase().includes('map uploaded')),
      results.assertions.foundThumbWithDataUrl === true,
    ];
    results.pass = pass.filter(Boolean).length;
    results.fail = pass.length - results.pass;
    results.passFailing = pass.map((p, i) => p ? null : `assertion #${i}`).filter(Boolean);
    results.console = consoleLog.slice(-30);
  }
  catch (err)
  {
    results.error = String(err);
    console.error('PROBE FAILED:', err);
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, 'failure.png') }).catch(() => {});
  }
  finally
  {
    writeFileSync(resolve(ARTIFACTS_DIR, 'results.json'), JSON.stringify(results, null, 2));
    console.log(JSON.stringify(results, null, 2));
    await browser.close();
  }
})();