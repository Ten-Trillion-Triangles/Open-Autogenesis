import { defineConfig } from '@playwright/test'

export default defineConfig({
    testDir: './tests',
    timeout: 120_000,
    expect: {
        timeout: 120_000
    },
    use: {
        baseURL: 'http://127.0.0.1:4173'
    },
    webServer: {
        command: 'node start.mjs',
        url: 'http://127.0.0.1:4173/index.html',
        reuseExistingServer: !process.env.CI,
        timeout: 180_000,
        cwd: process.cwd()
    }
})