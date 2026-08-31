import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import { expect, test } from '@playwright/test'
import { createAdminHarness, installFailClosedNetwork } from './fixtures/adminTestFixture'
import { LOGIN_E2E_ORIGIN } from './loginTestEnvironment'

test('BrowserContext 网络边界覆盖 popup 首请求与 popup WebSocket，且不会触达真实端口', async ({ browser }) => {
  let httpHits = 0
  let upgradeHits = 0
  const trapServer = createServer((_request, response) => {
    httpHits += 1
    response.writeHead(204).end()
  })
  trapServer.on('upgrade', (_request, socket) => {
    upgradeHits += 1
    socket.destroy()
  })

  await new Promise<void>((resolve, reject) => {
    trapServer.once('error', reject)
    trapServer.listen(0, '127.0.0.1', resolve)
  })

  const address = trapServer.address() as AddressInfo
  const httpTarget = `http://127.0.0.1:${address.port}/popup-boundary`
  const socketTarget = `ws://127.0.0.1:${address.port}/socket-boundary`
  const context = await browser.newContext({
    baseURL: LOGIN_E2E_ORIGIN,
    serviceWorkers: 'block',
  })
  const harness = createAdminHarness()

  try {
    await installFailClosedNetwork(context, harness)
    const page = await context.newPage()
    await page.setContent('<main>network boundary probe</main>')

    const blockedPopupPromise = context.waitForEvent('page')
    await page.evaluate((url) => window.open(url, '_blank'), httpTarget)
    await blockedPopupPromise

    await expect.poll(() => harness.externalRequests).toEqual([`GET ${httpTarget}`])

    const socketPopupPromise = context.waitForEvent('page')
    await page.evaluate(() => window.open('about:blank', '_blank'))
    const socketPopup = await socketPopupPromise
    await socketPopup.evaluate((url) => {
      const probeSocket = new WebSocket(url)
      probeSocket.addEventListener('error', () => {})
    }, socketTarget)

    await expect.poll(() => harness.externalSockets).toEqual([socketTarget])
    await expect.poll(() => ({ httpHits, upgradeHits })).toEqual({ httpHits: 0, upgradeHits: 0 })
    expect(context.serviceWorkers()).toEqual([])
  } finally {
    await context.close()
    await new Promise<void>((resolve, reject) => {
      trapServer.close((error) => error ? reject(error) : resolve())
    })
  }
})
