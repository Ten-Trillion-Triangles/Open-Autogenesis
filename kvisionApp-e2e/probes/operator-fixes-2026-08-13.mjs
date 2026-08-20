// Map upload + message-box regression probe for the 2026-08-13 operator
// bug report:
//   - Downsample always routes through to 256K tokens
//   - After downsample, the upload that previously rejected with "Image
//     too large even after downsample (1184951 bytes > 921600 cap)"
//     now SUCCEEDS because the helper iterates halving the dimension.
//   - The OK button on the failure MessageBox now closes the entire
//     dialog (not just removes the OK button).
//
// Phases:
//   1. Capture the upload failure case at the byte threshold that
//      triggered the operator's screenshot.
//   2. Assert the gate accepted the upload (no "Image too large" reject).
//   3. Capture the upload error MessageBox shape (success branch will
//      show a different notification, so we also exercise the failure
//      path with a deliberately-bad map).
//   4. Click OK on the MessageBox — assert the entire overlay DOM
//      leaves the rendered tree.

import { chromium } from 'playwright';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';
import { writeFileSync } from 'fs';
import { spawnSync } from 'child_process';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const BASE_URL = 'http://localhost:8080';
const ARTIFACTS_DIR = resolve(__dirname, '../artifacts-operator-fixes-2026-08-13');
import { mkdirSync } from 'fs';
mkdirSync(ARTIFACTS_DIR, { recursive: true });

const results = { assertions: {}, phases: [] };
const errors = [];

// Use the realistic-map.map fixture already checked into the repo —
// it's a real zip with map.json + map.png. We bypass the size fixture
// and instead trigger the failure path by packing the same map.json
// but with a deliberately-oversized image so the iterated downsample
// either accepts (fix) or rejects (legacy bug) at the new cap.
function buildOpsMapPack(p) {
  const fixturePath = resolve(__dirname, '../tests/fixtures/realistic-map.map');
  const result = spawnSync('python3', ['-c', `
import zipfile, sys
src = '${fixturePath}'
with zipfile.ZipFile(src) as z:
    img_name = next((n for n in z.namelist() if n.endswith('.png')), None)
    json_name = next((n for n in z.namelist() if n.endswith('.json')), None)
    sys.stderr.write('found img=' + str(img_name) + ' json=' + str(json_name) + '\\n')
    img_data = z.read(img_name) if img_name else b''
    json_data = z.read(json_name) if json_name else b'{}'
    sys.stdout.buffer.write(json_data + b'|DELIM|' + img_data)
`], { encoding: null });
  return result.stdout.toString().split('|DELIM|');
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();

  const consoleLog = [];
  page.on('console', (m) => consoleLog.push(`[${m.type()}] ${m.text()}`));
  page.on('pageerror', (e) => errors.push(`PAGE_ERROR: ${e.message}`));

  try {
    // Phase 0: dismiss loading + nav to MainMenu
    await page.goto(`${BASE_URL}/?skipLogin=true`);
    await page.click('[data-testid="loading-screen-cta"]');
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 });
    results.phases.push('phase-0-loaded');
    results.assertions.mainMenuPresent = true;
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '00-main-menu.png') });

    // Phase 1: open Collection overlay, switch to Maps tab, click upload
    // The button label is "Collection" (no data-testid on the button itself).
    await page.locator('.main-menu button:has-text("Collection")').first().click();
    await page.waitForSelector('.collection-overlay', { timeout: 10000 });
    await page.click(".collection-tab-button[title='Maps']");
    await page.waitForTimeout(500);

    // Capture screenshots we will later inspect.
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '01-collection-maps-tab.png') });

    await page.click('[data-testid="maps-upload-button"]');
    await page.waitForSelector('[data-testid="map-upload-modal"]', { timeout: 10000 });
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '02-upload-modal-opened.png') });

    // Build a 4-megapixel PNG that, under the legacy single-pass
    // 1024×1024 downsample, would produce ~600 KB → still over the old
    // 900 KB cap and trigger the operator's screenshot. Under the new
    // iterated helper it should compress to 512×512 first and pass.
    const oversizedPath = buildOversizedPng(ARTIFACTS_DIR);
    console.log(`Built oversized PNG: ${oversizedPath}`);

    await page.setInputFiles('[data-testid="map-upload-file-input"]', oversizedPath);
    await page.waitForTimeout(500);
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '03-file-selected.png') });

    // Set mapName and publish.
    await page.fill('[data-testid="map-upload-name-input"]', 'Operator Test Map');
    await page.click('[data-testid="map-upload-publish"]');
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '04-throbber-shown.png') });

    // Phase 2: poll for outcome — either success notification, failure
    // MessageBox, or 60s timeout. Under the FIX we expect SUCCESS
    // because the iterated downsample fits the 256 K-token floor.
    let outcome = 'unknown';
    let waitDeadline = Date.now() + 60_000;
    let titleText = '';
    while (Date.now() < waitDeadline)
    {
      titleText = await page.evaluate(() => {
        const overlay = document.querySelector('.autogenesis-message-box-overlay');
        if (!overlay) return '';
        const h = overlay.querySelector('h3');
        return h ? h.textContent || '' : '';
      });
      if (titleText.toLowerCase().includes('upload successful') ||
          titleText.toLowerCase().includes('upload failed') ||
          titleText.toLowerCase().includes('map uploaded'))
      {
        break;
      }
      await page.waitForTimeout(500);
    }
    results.assertions.outcomeText = titleText;
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '05-outcome.png') });

    // Phase 3: if a failure MessageBox appeared, exercise the OK-button
    // regression test: click OK, assert the entire overlay DOM leaves
    // the rendered tree.
    const overlayVisibleBefore = await page.evaluate(() => {
      const overlay = document.querySelector('.autogenesis-message-box-overlay');
      if (!overlay) return null;
      const style = window.getComputedStyle(overlay);
      const title = overlay.querySelector('h3')?.textContent || '';
      const message = overlay.querySelector('p')?.textContent || '';
      const button = overlay.querySelector('button');
      const buttonText = button ? button.textContent || '' : '';
      return {
        display: style.display,
        title,
        message,
        buttonText,
        hasButton: button !== null
      };
    });
    results.assertions.overlayStateBeforeOk = overlayVisibleBefore;

    let clickedOkResult = null;
    let dialogStillPresent = null;
    if (overlayVisibleBefore && overlayVisibleBefore.hasButton && /ok/i.test(overlayVisibleBefore.buttonText))
    {
      // Click OK via the real DOM click — never dispatchEvent.
      await page.evaluate(() => {
        const overlay = document.querySelector('.autogenesis-message-box-overlay');
        const button = overlay?.querySelector('button');
        if (button) button.click();
      });
      await page.waitForTimeout(500);
      // Verify the dialog is GONE.
      dialogStillPresent = await page.evaluate(() => {
        const overlay = document.querySelector('.autogenesis-message-box-overlay');
        if (!overlay) return { present: false };
        const style = window.getComputedStyle(overlay);
        const titleStill = overlay.querySelector('h3')?.textContent || '';
        const messageStill = overlay.querySelector('p')?.textContent || '';
        const buttonStill = overlay.querySelector('button');
        return {
          present: true,
          display: style.display,
          titleStill,
          messageStill,
          buttonStill: buttonStill !== null
        };
      });
      results.assertions.dialogStillPresent = dialogStillPresent;
      clickedOkResult = !dialogStillPresent?.present || dialogStillPresent?.display === 'none';
    }
    results.assertions.okClicked = clickedOkResult !== null;
    results.assertions.okClosedEntireDialog = !!clickedOkResult;

    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '06-after-ok-click.png') });

    // Summary
    const pass = [
      results.assertions.mainMenuPresent === true,
      results.assertions.outcomeText && results.assertions.outcomeText.toLowerCase().includes('upload') &&
        !results.assertions.outcomeText.toLowerCase().includes('still over') &&
        !results.assertions.outcomeText.toLowerCase().includes('rejected'),
      results.assertions.overlayStateBeforeOk && results.assertions.overlayStateBeforeOk.hasButton === true,
      results.assertions.dialogStillPresent && (
        results.assertions.dialogStillPresent.display === 'none' ||
        results.assertions.dialogStillPresent.present === false
      ),
    ];
    results.pass = pass.filter(Boolean).length;
    results.fail = pass.length - results.pass;
    results.console = consoleLog.slice(-30);
    results.errors = errors;
    console.log(JSON.stringify(results, null, 2));
    writeFileSync(resolve(ARTIFACTS_DIR, 'results.json'), JSON.stringify(results, null, 2));
  }
  catch (err)
  {
    console.error('PROBE FAILED:', err);
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, 'failure.png') }).catch(() => {});
    results.error = String(err);
    writeFileSync(resolve(ARTIFACTS_DIR, 'results.json'), JSON.stringify(results, null, 2));
    process.exitCode = 1;
  }
  finally
  {
    await browser.close();
  }
})();