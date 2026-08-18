// Diagnostic: dump everything captured verbatim for analysis
import { chromium } from 'playwright';

const URL = 'http://localhost:8080/?skipLogin=true';

async function main() {
    const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await ctx.newPage();
    const captured = [];
    page.on('console', msg => captured.push({ type: msg.type(), text: msg.text() }));
    await page.goto(URL, { waitUntil: 'load' });
    await page.waitForTimeout(30000);
    await ctx.close();
    await browser.close();
    const all = captured.map(c => `[${c.type}] ${c.text}`).join('\n');
    console.log('total captured:', captured.length);
    const matches = all.match(/RpcRegistrationCollector:\s*Registering provider[^\n]*/g);
    console.log('registration lines:', matches ? matches.length : 0);
    if (matches) {
        for (const m of matches) console.log(' ', m.slice(0, 250));
    }
    console.log('---registry init lines---');
    const inits = all.match(/RpcRegistry initialized with[^\n]*/g);
    console.log('init lines:', inits ? inits.length : 0);
    if (inits) {
        for (const m of inits) console.log(' ', m.slice(0, 250));
    }
    // Show first 5 sample debug lines
    const sample = captured.filter(c => c.type === 'log').slice(0, 15);
    console.log('---sample log-type entries---');
    for (const s of sample) console.log(`  [${s.type}] ${s.text.slice(0, 200)}`);
}
main();
