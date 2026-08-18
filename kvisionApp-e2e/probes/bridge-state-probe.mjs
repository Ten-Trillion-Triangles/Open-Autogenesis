// kvisionApp-e2e/probes/bridge-state-probe.mjs
// Probe the bridge state directly via JS injection + console capture.
import { chromium } from '@playwright/test';

const URL = 'http://127.0.0.1:8080/?skipLogin=true';

async function main() {
    const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await ctx.newPage();
    const consoleLog = [];
    page.on('console', msg => consoleLog.push(`[${msg.type()}] ${msg.text()}`));
    page.on('pageerror', err => consoleLog.push(`[pageerror] ${err.message}`));

    await page.goto(URL, { waitUntil: 'load' });
    await page.waitForTimeout(2000);

    // Dismiss loading screen CTA if needed
    const ctaCount = await page.getByTestId('loading-screen-cta').count();
    if (ctaCount > 0) {
        await page.getByTestId('loading-screen-cta').click();
        await page.waitForTimeout(2000);
    }

    // Wait for main menu
    await page.locator('[data-testid="main-menu"]').waitFor({ state: 'visible', timeout: 30000 });
    console.log('[probe] main-menu mounted');

    // Check what the current state is
    await page.waitForTimeout(2000);

    // Look at the last 30 console-log entries that mention RestRpcBridge
    const relevantLines = consoleLog.filter(l =>
        /RestRpcBridge|RestRpcClient|ServerExtendBridge|skipping rebind|skip rebind|defuse|Main: rebinding|REGRESSION-PROBE/.test(l)
    );
    console.log('[probe] --- relevant console lines ---');
    relevantLines.slice(-50).forEach(l => console.log(`  ${l.slice(0, 300)}`));

    console.log('[probe] --- REGRESSION-PROBE count ---');
    const probeCount = consoleLog.filter(l => /REGRESSION-PROBE/.test(l)).length;
    console.log(`  count: ${probeCount}`);
    console.log('[probe] --- first 5 REGRESSION-PROBE lines ---');
    consoleLog.filter(l => /REGRESSION-PROBE/.test(l)).slice(0, 5).forEach(l => console.log(`  ${l.slice(0, 400)}`));

    console.log('[probe] --- skipping rebind count ---');
    const skipCount = consoleLog.filter(l => /skipping rebind/.test(l)).length;
    console.log(`  count: ${skipCount}`);

    console.log('[probe] --- last 5 RestRpcBridgeJs.connect entries ---');
    const connectLines = consoleLog.filter(l => /RestRpcBridgeJs.connect/.test(l));
    connectLines.slice(-5).forEach(l => console.log(`  ${l.slice(0, 250)}`));

    await ctx.close();
    await browser.close();
}

main().catch(err => { console.error('UNCAUGHT:', err); process.exit(1); });
