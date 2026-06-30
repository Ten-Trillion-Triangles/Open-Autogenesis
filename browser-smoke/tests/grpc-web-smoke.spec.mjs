import { expect, test } from '@playwright/test'

test('grpc-web smoke probe reaches server-extend directly', async ({ page }) => {
    await page.goto('/?skipLogin=true&serverExtendTransport=grpc-web&browserSmoke=true')

    const marker = page.locator('#autogenesis-browser-smoke')

    await expect(marker).toHaveAttribute('data-transport', 'grpc-web')
    await expect(marker).toHaveAttribute('data-connected', 'true')
    await expect(marker).toHaveAttribute('data-session-ready', 'true')
    await expect(marker).toHaveAttribute('data-smoke-status', 'passed')
    await expect(marker).toHaveAttribute('data-smoke-method', 'server.extend.invokeMatchMaking')
    await expect(marker).toHaveAttribute('data-smoke-value', '127.0.0.1:9080')
})
