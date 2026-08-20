// kvisionApp-e2e/probes/fail-to-fetch-repro.mjs
// Reproduces the 'Fail to fetch' POST error observed in the upload RPC.
// Hypothesis matrix:
//   A. CORS preflight rejected (OPTIONS request returns 403)
//   B. SSE channel + POST share a port; some browsers treat the SSE channel as already-open and block new connections
//   C. Ktor's HttpClient (which RestRpcClient wraps) sends without `credentials: include` and the server's session cookie is required
//   D. The fetch happens during a page reload / before ServiceWorker handshake
//   E. The fetch URL has a trailing-whitespace / encoding issue
//
// Strategy: bypass RestRpcClient entirely and call fetch() directly with
// the exact same URL, method, headers, and body that the failing POST would
// have. If THIS fetch succeeds, the bug is in the RestRpcClient/Ktor layer.
// If THIS fetch also fails with "Fail to fetch", the bug is at the browser/network level (CORS, fetch interception, or a server-side rejection).
import { chromium } from '@playwright/test';

const URL = 'http://127.0.0.1:8080/?skipLogin=true';
const RPC_BASE = 'http://127.0.0.1:7070/rpc';

async function setup() {
    const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await ctx.newPage();
    const allConsole = [];
    page.on('console', msg => allConsole.push({ type: msg.type(), text: msg.text() }));
    page.on('pageerror', err => allConsole.push({ type: 'pageerror', text: err.message }));
    page.on('requestfailed', req => allConsole.push({ type: 'requestfailed', text: `${req.method()} ${req.url()} → ${req.failure()?.errorText || 'unknown'}` }));
    page.on('response', async resp => {
        const url = resp.url();
        if (url.includes('127.0.0.1:7070') && (resp.request().method() === 'POST' || resp.request().method() === 'OPTIONS')) {
            const body = await resp.text().catch(() => '');
            allConsole.push({ type: 'network', text: `${resp.request().method()} ${url} → ${resp.status()} (body=${body.slice(0, 200)})` });
        }
    });
    return { browser, ctx, page, allConsole };
}

async function getPlayerIdFromBridge(page) {
    // Read the bridge's current playerId by triggering a state-readable path.
    // The bridge internals aren't on window. Instead, sniff the latest
    // 'RestRpcBridge: connected to' log line and parse out the playerId.
    return null;
}

async function run() {
    const { browser, ctx, page, allConsole } = await setup();
    await page.goto(URL, { waitUntil: 'load' });
    await page.waitForTimeout(2000);

    const ctaCount = await page.getByTestId('loading-screen-cta').count();
    if (ctaCount > 0) {
        await page.getByTestId('loading-screen-cta').click();
        await page.waitForTimeout(3000);
    }

    // Wait for the storm to settle and a live bridge to be in place
    await page.waitForTimeout(5000);

    // ───── TEST 1: Direct fetch from page context, mimicking RestRpcClient ─────
    console.log('\n[test1] direct fetch from page context, no credentials');

    const t1 = await page.evaluate(async () => {
        const url = 'http://127.0.0.1:7070/rpc?playerId=test-probe-1&guestMode=true';
        const body = JSON.stringify({
            type: 'request',
            id: 'test-probe-1-' + Date.now(),
            method: 'server.extend.getMasterRecord',
            params: 'guest-user'
        });
        const headers = {
            'Accept': 'application/json',
            'Content-Type': 'application/json; charset=UTF-8'
        };
        try {
            const resp = await fetch(url, { method: 'POST', headers, body });
            return { ok: true, status: resp.status, statusText: resp.statusText };
        } catch (e) {
            return { ok: false, error: e.message, errorName: e.name };
        }
    });
    console.log('  result:', JSON.stringify(t1));

    // ───── TEST 2: Direct fetch with credentials: include ─────
    console.log('\n[test2] direct fetch with credentials: include');
    const t2 = await page.evaluate(async () => {
        const url = 'http://127.0.0.1:7070/rpc?playerId=test-probe-2&guestMode=true';
        const body = JSON.stringify({
            type: 'request',
            id: 'test-probe-2-' + Date.now(),
            method: 'server.extend.getMasterRecord',
            params: 'guest-user'
        });
        const headers = {
            'Accept': 'application/json',
            'Content-Type': 'application/json; charset=UTF-8'
        };
        try {
            const resp = await fetch(url, { method: 'POST', headers, body, credentials: 'include', mode: 'cors' });
            return { ok: true, status: resp.status, statusText: resp.statusText };
        } catch (e) {
            return { ok: false, error: e.message, errorName: e.name };
        }
    });
    console.log('  result:', JSON.stringify(t2));

    // ───── TEST 3: Direct fetch via no-cors mode (silently sends) ─────
    console.log('\n[test3] direct fetch with mode no-cors');
    const t3 = await page.evaluate(async () => {
        const url = 'http://127.0.0.1:7070/rpc?playerId=test-probe-3&guestMode=true';
        const body = JSON.stringify({
            type: 'request',
            id: 'test-probe-3-' + Date.now(),
            method: 'server.extend.getMasterRecord',
            params: 'guest-user'
        });
        try {
            const resp = await fetch(url, { method: 'POST', body, mode: 'no-cors' });
            return { ok: true, status: resp.status, statusText: resp.statusText };
        } catch (e) {
            return { ok: false, error: e.message, errorName: e.name };
        }
    });
    console.log('  result:', JSON.stringify(t3));

    // ───── TEST 4: SSE event channel ─────
    console.log('\n[test4] SSE GET /events');
    const t4 = await page.evaluate(async () => {
        const url = 'http://127.0.0.1:7070/events?playerId=test-probe-sse&guestMode=true';
        try {
            const resp = await fetch(url, { method: 'GET', headers: { 'Accept': 'text/event-stream' } });
            // Try to read a tiny bit then abort
            const reader = resp.body.getReader();
            const { value, done } = await reader.read();
            reader.cancel();
            const text = value ? new TextDecoder().decode(value) : '';
            return { ok: true, status: resp.status, firstChunk: text.slice(0, 200) };
        } catch (e) {
            return { ok: false, error: e.message };
        }
    });
    console.log('  result:', JSON.stringify(t4));

    // ───── TEST 5: OPTIONS preflight (CORS test) ─────
    console.log('\n[test5] OPTIONS preflight');
    const t5 = await page.evaluate(async () => {
        const url = 'http://127.0.0.1:7070/rpc?playerId=test-probe-5&guestMode=true';
        try {
            const resp = await fetch(url, {
                method: 'OPTIONS',
                headers: {
                    'Origin': 'http://127.0.0.1:8080',
                    'Access-Control-Request-Method': 'POST',
                    'Access-Control-Request-Headers': 'content-type'
                }
            });
            return {
                ok: true,
                status: resp.status,
                statusText: resp.statusText,
                aco: resp.headers.get('Access-Control-Allow-Origin'),
                acm: resp.headers.get('Access-Control-Allow-Methods'),
                ach: resp.headers.get('Access-Control-Allow-Headers'),
                acc: resp.headers.get('Access-Control-Allow-Credentials')
            };
        } catch (e) {
            return { ok: false, error: e.message };
        }
    });
    console.log('  result:', JSON.stringify(t5));

    // ───── TEST 6: Try POST after OPTIONS preflight manually ─────
    console.log('\n[test6] POST after manual OPTIONS preflight');
    const t6 = await page.evaluate(async () => {
        const url = 'http://127.0.0.1:7070/rpc?playerId=test-probe-6&guestMode=true';
        try {
            // CORS preflight
            const preflight = await fetch(url, {
                method: 'OPTIONS',
                headers: {
                    'Origin': 'http://127.0.0.1:8080',
                    'Access-Control-Request-Method': 'POST',
                    'Access-Control-Request-Headers': 'content-type'
                }
            });
            if (!preflight.ok) {
                return { ok: false, preflightStatus: preflight.status };
            }
            const body = JSON.stringify({
                type: 'request',
                id: 'test-probe-6-' + Date.now(),
                method: 'server.extend.getMasterRecord',
                params: 'guest-user'
            });
            const resp = await fetch(url, {
                method: 'POST',
                headers: {
                    'Origin': 'http://127.0.0.1:8080',
                    'Content-Type': 'application/json; charset=UTF-8',
                    'Accept': 'application/json'
                },
                body
            });
            return { ok: true, status: resp.status, statusText: resp.statusText };
        } catch (e) {
            return { ok: false, error: e.message };
        }
    });
    console.log('  result:', JSON.stringify(t6));

    // ───── Diagnostic console dump ─────
    console.log('\n[console] relevant network events:');
    allConsole.filter(e => e.type === 'network' || e.type === 'requestfailed' || /rpc|fetch|Fail|7070/.test(e.text))
        .slice(-30)
        .forEach(e => console.log(`  [${e.type}] ${e.text.slice(0, 300)}`));

    await ctx.close();
    await browser.close();
}

run().catch(err => { console.error('UNCAUGHT:', err); process.exit(1); });
