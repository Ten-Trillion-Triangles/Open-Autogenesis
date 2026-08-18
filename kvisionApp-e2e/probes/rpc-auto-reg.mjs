// kvisionApp-e2e/probes/rpc-auto-reg.mjs
//
// Verifies: does Map.Upload.Success / Map.Upload.Error auto-register on
// the server-extend RPC registry WITHOUT the manual
// ServerExtendBridge.registerHandlers { ... } block that used to live in
// Main.kt? (That block was the Task-1 cowboy addition; the user flagged
// it as a violation of the rpc-ksp auto-registration design.)
//
// Live evidence captured by Playwright's `console.log/info/warn/error`
// listener. The JS LogWriter (sharedModel/jsMain/.../LogWriter.js.kt)
// writes every entry to console.* — verified at runtime, the localStorage
// sink described in AGENTS.md is stale code (LogWriter.configure() says
// "(localStorage persistence removed for performance)"). So console
// capture is the load-bearing channel.
//
// Patterns we look for:
//
//   (a) Provider self-registers when its top-level val initialises —
//       "RpcRegistrationCollector: Registering provider
//        MapUploadSuccessClientHandlersRegistrationProvider"
//       emitted by RpcRegistrationCollector.registerProvider when
//       _mapUploadSuccessClientHandlersRpcHandlersProvider's init runs.
//       Same shape for MapUploadError, UiSignal, ActionHistory, Audio.
//
//   (b) RpcRegistry.init logs handler count AFTER registerAll:
//       "RpcRegistry initialized with N handlers from M providers"
//       One per bridge (WebSocketRpcBridge + SharedRestRpcBridge).
//
//   (c) Bridge.connect log lines that prove both transports reached the
//       step where their `rpcRegistry` was first read:
//       "RestRpcBridgeJs: connect() invoked" (or similar).
//
// Chain being verified: bundle does NOT contain my Task-1 second
// `ServerExtendBridge.registerHandlers { ... }` block. The providers
// should self-register purely via the generated
// GeneratedRpcMasterRegistrationKt master initializer that
// RpcRegistrationPlatform.js requires on first RpcRegistry.init.

import { chromium } from 'playwright';

const URL = 'http://localhost:8080/?skipLogin=true';
const PATIENCE_MS = 30000; // 30s is enough for both RpcRegistry.init
                           // lines + provider self-registrations.

const REQUIRED = [
    { name: 'reg-provider-MapUploadSuccess',  re: /RpcRegistrationCollector:\s*Registering provider\s+MapUploadSuccessClientHandlersRegistrationProvider/i, mustMatch: true },
    { name: 'reg-provider-MapUploadError',    re: /RpcRegistrationCollector:\s*Registering provider\s+MapUploadErrorClientHandlersRegistrationProvider/i,   mustMatch: true },
    { name: 'reg-provider-UiSignal',          re: /RpcRegistrationCollector:\s*Registering provider\s+UiSignalClientHandlersRegistrationProvider/i,         mustMatch: true },
    { name: 'reg-provider-Audio',             re: /RpcRegistrationCollector:\s*Registering provider\s+AudioClientHandlersRegistrationProvider/i,             mustMatch: true },
    { name: 'reg-provider-ActionHistory',     re: /RpcRegistrationCollector:\s*Registering provider\s+ActionHistoryClientHandlersRegistrationProvider/i,   mustMatch: true },
];

async function main() {
    const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await ctx.newPage();

    // CAPTURE CONSOLE. The LogWriter writes every entry to
    // console.log (DEBUG/UNSET), console.info (INFO), console.warn (WARN),
    // console.error (ERROR). Treat all of these as log sources.
    const captured = [];
    page.on('console', msg => {
        const text = msg.text();
        captured.push(`[${msg.type()}] ${text}`);
    });
    page.on('pageerror', err => captured.push(`[pageerror] ${err.message}`));

    console.log(`[${ts()}] navigate ${URL}`);
    await page.goto(URL, { waitUntil: 'load', timeout: 30000 });

    // Give the app 30s to boot. The skipLogin path fires
    // `WebSocketRpcBridge.connect(...)` and `RestRpcBridge.connect(...)`
    // synchronously in Main.kt init. Each `connect()` may read
    // `rpcRegistry` (via `registerHandlers`) which triggers `RpcRegistry.init`
    // → `initializeRpcRegistrationsPlatform()` → master JS require →
    // every provider self-registers into RpcRegistrationCollector →
    // `registerAll(this)` fires for each registry.
    //
    // All of this happens within the same task — should be done in <5s.
    // 30s patience gives plenty of margin.
    await page.waitForTimeout(PATIENCE_MS);

    await ctx.close();
    await browser.close();

    const all = captured.join('\n');
    const totalEntries = captured.length;

    let pass = true;
    for (const check of REQUIRED) {
        const matches = [...all.matchAll(new RegExp(check.re.source, 'gi'))];
        if (check.mustMatch && matches.length === 0) {
            console.error(`[${ts()}] FAIL: ${check.name} NOT found in any console output`);
            pass = false;
        } else if (matches.length > 0) {
            console.log(`[${ts()}] OK ${check.name}: ${matches.length} match(es) — first: "${matches[0][0].slice(0, 250)}"`);
        }
    }

    const initMatches = [...all.matchAll(/RpcRegistry initialized with (\d+) handlers from (\d+) providers/gi)];
    if (initMatches.length < 2) {
        console.error(`[${ts()}] FAIL: expected ≥2 RpcRegistry.init lines (one per bridge), saw ${initMatches.length}`);
        pass = false;
    } else {
        for (const m of initMatches) {
            console.log(`[${ts()}] OK registry-init: handlers=${m[1]} providers=${m[2]}`);
        }
    }

    const debugCount = (all.match(/\[DEBUG\]/g) || []).length;
    const infoCount  = (all.match(/\[INFO\]/g)  || []).length;
    const warnCount  = (all.match(/\[WARN\]/g)  || []).length;
    const errorCount = (all.match(/\[ERROR\]/g) || []).length;
    console.log(`[${ts()}] log counts: total=${totalEntries} debug=${debugCount} info=${infoCount} warn=${warnCount} error=${errorCount}`);

    if (pass) {
        console.log(`[${ts()}] RESULT: PASS — MapUpload*ClientHandlers providers self-registered without manual block`);
    } else {
        console.log(`[${ts()}] RESULT: FAIL — see FAIL lines above`);
        console.log(`[${ts()}] --- console tail (last 1500 chars) ---`);
        console.log(all.slice(-1500));
    }
    if (!pass) process.exit(1);
}

function ts() {
    const d = new Date();
    return d.toISOString().slice(11, 23);
}

main().catch(err => { console.error('UNCAUGHT:', err); process.exit(1); });
