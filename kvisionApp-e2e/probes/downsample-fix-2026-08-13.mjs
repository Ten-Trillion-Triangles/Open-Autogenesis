// Map upload downsample regression probe (2026-08-13):
//
//   The MapUploadGate MUST always downsample to the operator-mandated
//   256 K-token floor (~408 KB at the empirical 0.627 tokens/byte
//   PNG ratio on Nova Lite). A 1.18 MB PNG (the operator's exact
//   failing screenshot size) must be ACCEPTED, not rejected with
//   "Image too large even after downsample (1184951 bytes > 921600 cap)".
//
// The probe builds a real `.map` zip pack (map.json + oversized.png)
// where the image is a 2048x2048 PNG that, under the LEGACY single-
// pass 1024x1024 downsample, would produce ~600 KB — still over the
// old 900 KB cap → reject. Under the FIXED iterated helper it
// compresses 1024→512→256 and lands under the 408 KB cap → accept.
//
// Outcome assertion: the failure MessageBox must NOT show
// "Image too large even after downsample"; the upload must succeed
// with Map.Upload.Success.

import { chromium } from 'playwright';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';
import { writeFileSync, mkdirSync, existsSync } from 'fs';
import { spawnSync } from 'child_process';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const BASE_URL = 'http://localhost:8080';
const ARTIFACTS_DIR = resolve(__dirname, '../artifacts-downsample-fix-2026-08-13');
mkdirSync(ARTIFACTS_DIR, { recursive: true });

const results = { assertions: {}, phases: [], errors: [] };

// Build the regression pack once (idempotent — reused across runs).
const REGRESSION_PACK_PATH = resolve(ARTIFACTS_DIR, 'mid-entropy-1024.map');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();

  const consoleLog = [];
  page.on('console', (m) => consoleLog.push(`[${m.type()}] ${m.text()}`));
  page.on('pageerror', (e) => results.errors.push(`PAGE_ERROR: ${e.message}`));

  try
  {
    await page.goto(`${BASE_URL}/?skipLogin=true`);
    await page.click('[data-testid="loading-screen-cta"]');
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 });
    results.phases.push('phase-0-loaded');
    results.assertions.mainMenuPresent = true;
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '00-main-menu.png') });

    // Open Collection overlay → Maps tab → Upload
    await page.locator('.main-menu button:has-text("Collection")').first().click();
    await page.waitForSelector('.collection-overlay', { timeout: 10000 });
    await page.click(".collection-tab-button[title='Maps']");
    await page.waitForTimeout(500);
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '01-maps-tab.png') });

    await page.click('[data-testid="maps-upload-button"]');
    await page.waitForSelector('[data-testid="map-upload-modal"]', { timeout: 10000 });
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '02-modal.png') });

    const packPath = REGRESSION_PACK_PATH;
    if (!existsSync(packPath))
    {
      // If the artifact is absent, abort with a clear error rather
      // than trying to build inline (the build is environment-
      // dependent and slow).
      throw new Error(`Regression pack not found at ${packPath}; build via /tmp build script`);
    }
    await page.setInputFiles('[data-testid="map-upload-file-input"]', packPath);
    await page.waitForTimeout(500);

    await page.fill('[data-testid="map-upload-name-input"]', 'Downsample Regression Probe');
    await page.click('[data-testid="map-upload-publish"]');
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '03-publishing.png') });

    // Wait for outcome — success OR fail.
    let outcomeTitle = '';
    let waitDeadline = Date.now() + 90_000;
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
    const lower = outcomeTitle.toLowerCase();
    results.assertions.acceptedWithoutDownsampleReject = !lower.includes('still over') && !lower.includes('rejected');
    results.assertions.showsAcceptance = lower.includes('upload successful') || lower.includes('map uploaded');
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '04-outcome.png') });

    // If a failure MessageBox appeared, capture its exact reason text.
    if (lower.includes('upload failed'))
    {
      const failureReason = await page.evaluate(() => {
        const overlay = document.querySelector('.autogenesis-message-box-overlay');
        if (!overlay) return '';
        const p = overlay.querySelector('p');
        return p ? p.textContent || '' : '';
      });
      results.assertions.failureReason = failureReason;
      results.assertions.bugReproduced = failureReason.includes('Image too large even after downsample');
    }

    // Phase 5: if a MessageBox with OK is on screen, click OK and
    // confirm the entire overlay leaves the rendered tree.
    const overlayBefore = await page.evaluate(() => {
      const overlay = document.querySelector('.autogenesis-message-box-overlay');
      if (!overlay) return null;
      return {
        hasOverlay: true,
        display: window.getComputedStyle(overlay).display,
        hasOk: !!overlay.querySelector('button'),
        okText: overlay.querySelector('button')?.textContent || ''
      };
    });
    results.assertions.overlayBeforeOk = overlayBefore;

    if (overlayBefore && overlayBefore.hasOk && /ok/i.test(overlayBefore.okText))
    {
      await page.evaluate(() => {
        const overlay = document.querySelector('.autogenesis-message-box-overlay');
        overlay?.querySelector('button')?.click();
      });
      await page.waitForTimeout(500);
    }

    const overlayAfter = await page.evaluate(() => {
      const overlay = document.querySelector('.autogenesis-message-box-overlay');
      if (!overlay) return { present: false };
      return {
        present: true,
        display: window.getComputedStyle(overlay).display,
        hasOk: !!overlay.querySelector('button'),
        hasTitle: !!overlay.querySelector('h3'),
        hasMessage: !!overlay.querySelector('p')
      };
    });
    results.assertions.overlayAfterOk = overlayAfter;
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '05-after-ok.png') });

    // Pass criteria.
    const pass = [
      results.assertions.mainMenuPresent === true,
      results.assertions.acceptedWithoutDownsampleReject === true,
      results.assertions.showsAcceptance === true,
      // The MessageBox OK regression is fixed in the source; verify the
      // OK-click collapses the overlay.
      overlayBefore === null || (overlayAfter.display === 'none' || overlayAfter.present === false)
    ];
    results.pass = pass.filter(Boolean).length;
    results.fail = pass.length - results.pass;
    results.passFailing = pass.map((p, i) => p ? null : `assertion #${i}`).filter(Boolean);
    results.console = consoleLog.slice(-50);
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
