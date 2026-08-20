import { defineConfig } from '@playwright/test'

const DIST_DIR = '../kvisionApp/build/dist/js/productionExecutable'

export default defineConfig({
    testDir: './tests',
    timeout: 120_000,
    expect: {
        timeout: 120_000
    },
    use: {
        baseURL: 'http://127.0.0.1:4175'
    },
    webServer: {
        // The static server is started manually before invoking
        // the test runner. `reuseExistingServer: true` forces
        // Playwright to skip its own webServer lifecycle so this
        // config does not spawn `npx http-server` (which hangs
        // on first install in this environment).
        command: 'true',
        url: 'http://127.0.0.1:4175/index.html',
        reuseExistingServer: true,
        timeout: 60_000
    }
})