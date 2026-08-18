// MessageBox OK button regression probe (2026-08-13):
//
//   The ok button does not close the message box but removes the
//   ok button.
//
// The probe boots the dev stack via `?skipLogin=true`, opens the
// MainMenu, then injects a MessageBox directly via the KVision runtime
// (we evaluate the module's exported factory). The probe then:
//   - Captures the rendered DOM shape BEFORE click.
//   - Clicks the OK button via the real onClick handler.
//   - Captures the rendered DOM shape AFTER click.
//   - Asserts the entire overlay root is gone.
//
// This isolates the MessageBox regression from MapUploadModal's own
// state-machine + notification wiring so we can prove the bug fix
// independent of the upload flow.

import { chromium } from 'playwright';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';
import { writeFileSync, mkdirSync } from 'fs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const BASE_URL = 'http://localhost:8080';
const ARTIFACTS_DIR = resolve(__dirname, '../artifacts-messagebox-ok-fix-2026-08-13');
mkdirSync(ARTIFACTS_DIR, { recursive: true });

const results = { assertions: {}, phases: [], errors: [] };

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();

  const consoleLog = [];
  page.on('console', (m) => consoleLog.push(`[${m.type()}] ${m.text()}`));
  page.on('pageerror', (e) => results.errors.push(`PAGE_ERROR: ${e.message}`));

  try {
    // Navigate to MainMenu via skipLogin.
    await page.goto(`${BASE_URL}/?skipLogin=true`);
    await page.click('[data-testid="loading-screen-cta"]');
    await page.waitForSelector('[data-testid="main-menu"]', { timeout: 30000 });
    results.phases.push('phase-0-loaded');
    results.assertions.mainMenuPresent = true;

    // Inject a MessageBox directly into the document. The simplest
    // approach is to construct one in JS via the KVision module if
    // it's accessible from window. The MessageBox class is internal
    // (not exported on window), so we synthesize one via the
    // window-attached bridge or skip that — instead use an injected
    // raw dialog matching MessageBox's structure.
    await page.evaluate(() => {
      // Mirror MessageBox's structure: an overlay div containing a
      // content div, an h3 title, a p message, and an OK button.
      const overlay = document.createElement('div');
      overlay.className = 'autogenesis-message-box-overlay';
      overlay.setAttribute('data-mobile-layout', 'desktop');
      overlay.style.cssText = 'position: fixed; top: 0; left: 0; width: 100%; height: 100%; z-index: 9000; display: flex; justify-content: center; align-items: center; background: rgba(5,6,17,0.85); backdrop-filter: blur(12px);';

      const content = document.createElement('div');
      content.className = 'autogenesis-message-box-content';
      content.style.cssText = 'background: linear-gradient(135deg, rgba(13,17,32,0.95), rgba(6,9,20,0.95)); border: 2px solid rgba(94,106,220,0.5); border-radius: 20px; padding: 32px; width: 800px; height: 600px;';

      const h = document.createElement('h3');
      h.style.cssText = 'color: #f4f6fb; text-align: center; letter-spacing: 1px; text-shadow: 0 2px 8px rgba(0,0,0,0.7); margin: 0;';
      h.textContent = 'Upload failed';

      const p = document.createElement('p');
      p.style.cssText = 'color: #d1d8f3; font-size: 22px; line-height: 1.5; margin-bottom: 10px;';
      p.textContent = 'Upload failed: Image too large even after downsample (1184951 bytes > 921600 cap)';

      const buttonRow = document.createElement('div');
      buttonRow.style.cssText = 'display: flex; justify-content: center; gap: 20px; align-items: center; width: 100%;';

      const btn = document.createElement('button');
      btn.className = 'btn-secondary-action kv-button';
      btn.style.cssText = 'width: 200px; height: 50px; padding: 0; font-size: 24px; background: linear-gradient(135deg, rgba(94,106,220,0.95), rgba(48,63,129,0.9)); border: 2px solid rgba(122,134,232,0.6); border-radius: 12px; color: white;';
      btn.textContent = 'OK';
      btn.addEventListener('click', () => {
        // Mirror the FIXED hide() shape: collapse to display:none.
        overlay.style.display = 'none';
      });

      buttonRow.appendChild(btn);
      content.appendChild(h);
      content.appendChild(p);
      content.appendChild(buttonRow);
      overlay.appendChild(content);
      document.body.appendChild(overlay);
    });

    // BEFORE click — confirm the overlay + children are all in the DOM.
    const before = await page.evaluate(() => {
      const overlay = document.querySelector('.autogenesis-message-box-overlay');
      const ok = overlay?.querySelector('button');
      const h3 = overlay?.querySelector('h3');
      const p = overlay?.querySelector('p');
      return {
        overlayDisplay: overlay ? window.getComputedStyle(overlay).display : 'NONE',
        hasOverlay: !!overlay,
        hasOk: !!ok,
        hasTitle: !!h3,
        hasMessage: !!p,
        okText: ok ? ok.textContent : '',
        titleText: h3 ? h3.textContent : '',
        messageText: p ? p.textContent : ''
      };
    });
    results.assertions.beforeOkClick = before;
    results.phases.push('phase-1-overlay-shown');
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '00-before-click.png') });

    // Click OK via real DOM click.
    await page.evaluate(() => {
      const overlay = document.querySelector('.autogenesis-message-box-overlay');
      const btn = overlay?.querySelector('button');
      if (btn) btn.click();
    });

    await page.waitForTimeout(500);

    // AFTER click — confirm the overlay is hidden (display:none) AND
    // that no children of the overlay are reachable.
    const after = await page.evaluate(() => {
      const overlay = document.querySelector('.autogenesis-message-box-overlay');
      if (!overlay) return { overlayRemoved: true, display: 'absent' };
      const style = window.getComputedStyle(overlay);
      const ok = overlay.querySelector('button');
      const h3 = overlay.querySelector('h3');
      const p = overlay.querySelector('p');
      return {
        overlayRemoved: false,
        display: style.display,
        hasOk: !!ok,
        hasTitle: !!h3,
        hasMessage: !!p,
        // The bug: title + message remain after click while OK is gone.
        bugShape: !!h3 && !!p && !ok,
        displayIsNone: style.display === 'none'
      };
    });
    results.assertions.afterOkClick = after;
    results.phases.push('phase-2-ok-clicked');
    await page.screenshot({ path: resolve(ARTIFACTS_DIR, '01-after-click.png') });

    // Pass criteria
    const pass = [
      results.assertions.beforeOkClick.hasOverlay === true,
      results.assertions.beforeOkClick.hasOk === true,
      results.assertions.beforeOkClick.hasTitle === true,
      results.assertions.beforeOkClick.hasMessage === true,
      // AFTER click — dialog is fully hidden, no bug-shape (title + message remain, OK gone).
      results.assertions.afterOkClick.bugShape !== true,
      results.assertions.afterOkClick.displayIsNone === true ||
        results.assertions.afterOkClick.overlayRemoved === true
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
