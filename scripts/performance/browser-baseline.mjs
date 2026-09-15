import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { readFile, writeFile, appendFile, mkdir, stat } from 'node:fs/promises'
import { createReadStream } from 'node:fs'
import { createRequire } from 'node:module'
import { resolve, extname, dirname, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { once } from 'node:events'

// 只测生产构建与真实工单 HTTP 链。认证、菜单、WS 就绪和辅助摘要用明确登记的本地夹具。
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const require = createRequire(resolve(root, 'customer-admin-web/package.json'))
const { chromium, expect } = require('@playwright/test')
const [serverDirectory, outputDirectory, mode = 'measure'] = process.argv.slice(2)
assert(serverDirectory && outputDirectory, 'Usage: browser-baseline.mjs SERVER_DIR NEW_OUTPUT_DIR [smoke|measure]')
assert(['smoke', 'measure'].includes(mode))
const output = resolve(outputDirectory)
await mkdir(output, { recursive: false })
const settings = Object.fromEntries((await readFile(resolve(serverDirectory, 'server.properties'), 'utf8'))
  .split('\n').filter(line => line && !line.startsWith('#')).map(line => {
    const at = line.indexOf('=')
    return [line.slice(0, at), line.slice(at + 1).replaceAll('\\:', ':')]
  }))
const dist = resolve(root, 'customer-admin-web/dist')
await stat(resolve(dist, 'index.html'))
const warmups = mode === 'smoke' ? 1 : 10
const samples = mode === 'smoke' ? 2 : 50
const errors = []
const apiEvidence = []
const frontendOrigin = 'http://127.0.0.1:4174'
const permissions = ['user-ticket:view', 'user-ticket:reply', 'user-ticket:edit', 'user-ticket:transfer']
const menu = [{ id: 1, name: '客服工单', path: '/ticket/user-ticket', icon: 'ChatDotRound',
  iconType: 'library', permCode: 'user-ticket:view', sort: 1, agentCode: null,
  capabilities: null, dynamic: false, children: [] }]
const shellFixtures = new Map([
  ['/api/auth/permissions', permissions],
  ['/api/menu/routes', menu],
  ['/api/menu/version', 1],
  ['/api/tenant/current-view', { userTenantId: settings.tenant, effectiveTenantId: settings.tenant, crossTenantAuthority: false }],
  ['/api/message/unread-count', 0],
  ['/api/ticket/ws-credential', { token: 'local-performance-ws-fixture', agentId: settings.agent,
    tenantId: settings.tenant, wsUrl: 'ws://127.0.0.1:4174/ws/agent', expiresAtMs: Date.now() + 3600000 }],
])
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff' }
function json(response, data) {
  response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
  response.end(JSON.stringify({ code: 0, message: 'success', data }))
}
async function upstream(path) {
  const start = performance.now()
  const response = await fetch(settings.adminBaseUrl + path, {
    headers: { [settings.requestHeader]: settings.requestToken, Accept: 'application/json' },
    signal: AbortSignal.timeout(15000),
  })
  const body = await response.text()
  apiEvidence.push({ path, status: response.status, elapsedMs: performance.now() - start,
    bytes: Buffer.byteLength(body), body: JSON.parse(body) })
  assert.equal(response.status, 200, `${path}: ${body}`)
  return { response, body }
}
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, frontendOrigin)
    assert.equal(request.method, 'GET', 'Performance server accepts GET only')
    if (shellFixtures.has(url.pathname)) return json(response, shellFixtures.get(url.pathname))
    if (/^\/api\/ticket\/(page|performance-owned-\d+(\/messages)?)$/.test(url.pathname)) {
      const result = await upstream(url.pathname + url.search)
      const payload = JSON.parse(result.body)
      assert.equal(payload.code, 0)
      if (url.pathname === '/api/ticket/page') {
        assert.equal(payload.data.total, url.searchParams.get('assignee') === settings.agent ? 1000 : 10000)
        assert.equal(payload.data.items.length, Number(url.searchParams.get('pageSize')))
        assert(payload.data.items.every(ticket => ticket.id.startsWith(settings.tenant + '-')))
      } else if (url.pathname.endsWith('/messages')) {
        assert(payload.data.length <= Number(url.searchParams.get('limit')))
        assert(payload.data.every(message => message.ticketId === url.pathname.split('/').at(-2)))
      }
      response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
      response.end(result.body)
      return
    }
    const assist = url.pathname.match(/^\/api\/ticket\/(performance-owned-\d+)\/assist$/)
    if (assist) {
      // 辅助面板的来源读取真实消息，摘要是固定夹具，不能登记为模型推理或辅助服务性能。
      const messages = JSON.parse((await upstream(`/api/ticket/${assist[1]}/messages?limit=30`)).body).data
      return json(response, { ticketId: assist[1], summary: {
        oneLineSummary: '客户咨询售后进度，等待坐席核对。', userIntent: '咨询退款进度', emotion: null,
        triedSolutions: [], pendingIssues: ['核对退款记录'], suggestedNextStep: '核对订单后答复客户。',
        suggestedReply: '我正在核对处理记录，请稍候。', fromModel: false,
        evidence: { version: `performance-fixture:${assist[1]}`, generatedAtMs: 1789400000000,
          historyLimit: 30, truncated: true, sources: messages.slice(-3).map(message => ({
            id: message.id, messageId: message.messageId, senderType: message.senderType,
            excerpt: message.content, createdAtMs: message.createdAtMs, truncated: false,
          })) },
      } })
    }
    assert(!url.pathname.startsWith('/api/'), `Unregistered API: ${url.pathname}`)
    let path = resolve(dist, '.' + decodeURIComponent(url.pathname))
    assert(path === dist || path.startsWith(dist + sep), 'Asset path outside production dist')
    if (!extname(path)) path = resolve(dist, 'index.html')
    await stat(path)
    response.writeHead(200, { 'Content-Type': mime[extname(path)] ?? 'application/octet-stream' })
    createReadStream(path).pipe(response)
  } catch (error) {
    errors.push({ request: request.url, error: String(error) })
    response.writeHead(500)
    response.end('Performance harness request failed')
  }
})
server.listen(4174, '127.0.0.1')
await once(server, 'listening')
let browser
const results = []
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  await writeFile(resolve(output, 'conditions.json'), JSON.stringify({
    mode, productionBuild: true, browser: browser.version(), warmups, samples,
    ownRows: Number(settings.ownRows), otherRows: Number(settings.otherRows), messagesPerConversation: 200,
    pageSize: 20, messagePageSize: 30, proxy: 'local Node HTTP → Admin MVC → signed HTTP → customer WebFlux → production MyBatis',
    excluded: ['login', 'Sa-Token permission interceptor', 'websocket delivery', 'model inference', 'assist summary service'],
  }, null, 2))
  for (const viewport of [{ width: 1680, height: 1050 }, { width: 390, height: 844 }]) {
    const context = await browser.newContext({ viewport, serviceWorkers: 'block' })
    await context.routeWebSocket('**/ws/agent?**', socket => socket.onMessage(() => {}))
    await context.addInitScript(({ agent, allowedOrigin }) => {
      if (location.origin !== allowedOrigin) return
      localStorage.setItem('admin-token', 'local-performance-login-fixture')
      localStorage.setItem('admin-nickname', '性能验收坐席')
      localStorage.setItem('admin-username', agent)
      localStorage.setItem('admin-force-change-password', 'false')
      localStorage.setItem('admin-approval-status', 'APPROVED')
      localStorage.setItem('customer-admin-theme-selection', JSON.stringify({ version: 1, kind: 'preset', id: 'ocean' }))
      localStorage.setItem('customer-admin-theme-mode', 'light')
      localStorage.setItem('customer-admin-theme-color', '#3e63dd')
      performance.setResourceTimingBufferSize(10000)
      window.__performanceLongTasks = []
      new PerformanceObserver(list => window.__performanceLongTasks.push(...list.getEntries()
        .map(entry => ({ startTime: entry.startTime, duration: entry.duration }))))
        .observe({ type: 'longtask', buffered: true })
    }, { agent: settings.agent, allowedOrigin: frontendOrigin })
    const page = await context.newPage()
    const requestsInFlight = new Set()
    page.on('pageerror', error => errors.push({ pageError: String(error) }))
    page.on('console', message => { if (message.type() === 'error') errors.push({ console: message.text() }) })
    page.on('request', request => {
      if (new URL(request.url()).origin !== frontendOrigin) errors.push({ externalRequest: request.url() })
      if (request.url().includes('/api/')) requestsInFlight.add(request)
    })
    page.on('requestfinished', request => requestsInFlight.delete(request))
    page.on('requestfailed', request => {
      requestsInFlight.delete(request)
      errors.push({ failedRequest: request.url(), error: request.failure()?.errorText })
    })
    await page.goto(frontendOrigin + '/ticket/user-ticket')
    // 窄屏隐藏在线文字，连接状态仍由可见圆点表达；不能把隐藏文字误判为未连接。
    await expect(page.locator('.connection-dot.connected')).toBeVisible()
    await expect(page.locator('.queue-ticket')).toHaveCount(10)
    await page.locator('.queue-pagination .el-select').click()
    await page.getByRole('option', { name: /20\s*条/ }).click()
    await expect(page.locator('.queue-ticket')).toHaveCount(20)
    await page.evaluate(() => document.fonts.ready)

    async function measure(scenario, locator, target, iteration) {
      await page.evaluate(target => {
        window.__performanceResult = new Promise(resolve => {
          let start = null
          let stopped = false
          const started = event => { start = performance.now() }
          document.addEventListener('click', started, { capture: true, once: true })
          const timeout = setTimeout(() => {
            stopped = true
            document.removeEventListener('click', started, true)
            resolve({ error: 'UI readiness timeout', target })
          }, 15000)
          function ready() {
            if (target.kind === 'queue') {
              const queue = document.querySelector('.queue-list')
              return queue?.getAttribute('aria-busy') === 'false'
                && queue.querySelectorAll('.queue-ticket').length === 20
                && queue.querySelector('.queue-entry strong')?.textContent === `售后进度与历史咨询 ${target.first}`
            }
            const messages = [...document.querySelectorAll('.messages .message-row')]
            return document.querySelector('.conversation-heading')?.textContent.includes(target.id)
              && document.querySelector('.messages')?.getAttribute('aria-busy') === 'false'
              && !document.querySelector('.conversation-error')
              && messages.length === target.count
              && messages.every(message => message.textContent.includes(`[${target.id}:`))
              && !document.querySelector('.conversation-status .is-loading')
          }
          function tick() {
            if (stopped) return
            if (start !== null && ready()) {
              const domReady = performance.now()
              requestAnimationFrame(() => requestAnimationFrame(() => {
                clearTimeout(timeout)
                const end = performance.now()
                const requests = performance.getEntriesByType('resource').filter(entry =>
                  entry.startTime >= start && entry.name.includes('/api/ticket/'))
                  .map(entry => ({ url: entry.name, startTime: entry.startTime, responseEnd: entry.responseEnd,
                    duration: entry.duration, transferSize: entry.transferSize }))
                resolve({ elapsedMs: end - start, domMs: domReady - start, startTime: start, endTime: end,
                  requests, target, longTasks: window.__performanceLongTasks.filter(entry => entry.startTime >= start && entry.startTime < end) })
              }))
            } else requestAnimationFrame(tick)
          }
          requestAnimationFrame(tick)
        })
      }, target)
      await locator.click()
      const result = await page.evaluate(() => window.__performanceResult)
      const row = { scenario, viewport, iteration, ...result }
      await appendFile(resolve(output, 'actions.jsonl'), JSON.stringify(row) + '\n')
      assert(!result.error, JSON.stringify(row))
      assert(result.requests.length, 'Measurement must include actual HTTP requests')
      // 就绪时间在页面内已经记录；等待剩余辅助请求结束，不将上一轮网络负载带进下一轮。
      await expect.poll(() => requestsInFlight.size).toBe(0)
      return row
    }
    for (let i = -warmups; i < samples; i++) {
      const next = (i + warmups) % 2 === 0
      const row = await measure('queue-page', page.locator(next ? '.queue-pagination .btn-next' : '.queue-pagination .btn-prev'),
        { kind: 'queue', first: next ? 9979 : 9999 }, i)
      if (i >= 0) results.push(row)
    }
    // 下一阶段固定在第一页；smoke 的奇数轮同样通过用户入口回到第一页。
    if (await page.locator('.queue-pagination .btn-prev').isEnabled()) await page.locator('.queue-pagination .btn-prev').click()
    await expect(page.locator('.queue-entry strong').first()).toHaveText('售后进度与历史咨询 9999')
    for (let i = -warmups; i < samples; i++) {
      const mine = (i + warmups) % 2 === 0
      const row = await measure('queue-assignee-filter', page.locator('.queue-scopes').getByRole('button', {
        name: mine ? '我负责' : '全部', exact: true,
      }), { kind: 'queue', first: mine ? 9998 : 9999 }, i)
      if (i >= 0) results.push(row)
    }
    if (await page.locator('.queue-scopes').getByRole('button', { name: '全部', exact: true }).getAttribute('aria-pressed') !== 'true') {
      await page.locator('.queue-scopes').getByRole('button', { name: '全部', exact: true }).click()
      await expect(page.locator('.queue-entry strong').first()).toHaveText('售后进度与历史咨询 9999')
    }
    async function back() {
      const button = page.getByRole('button', { name: '返回工单队列' })
      if (await button.isVisible()) await button.click()
    }
    async function select(number) {
      await back()
      await page.getByRole('button', { name: `打开工单：售后进度与历史咨询 ${number}`, exact: true }).click()
      await expect(page.locator('.conversation-heading')).toContainText(`performance-owned-${number}`)
      await expect(page.locator('.messages .message-row')).toHaveCount(30)
      for (let count = 60; count <= 210; count += 30) {
        await page.getByRole('button', { name: '加载更早消息' }).click()
        await expect(page.locator('.messages .message-row')).toHaveCount(Math.min(count, 200))
      }
      await expect(page.getByRole('button', { name: '加载更早消息' })).toHaveCount(0)
    }
    await select(9998)
    await select(9996)
    for (let i = -warmups; i < samples; i++) {
      const number = (i + warmups) % 2 === 0 ? 9998 : 9996
      await back()
      const row = await measure('conversation-switch-200', page.getByRole('button', {
        name: `打开工单：售后进度与历史咨询 ${number}`, exact: true,
      }), { kind: 'conversation', id: `performance-owned-${number}`, count: 200 }, i)
      if (i >= 0) results.push(row)
    }
    for (let i = -warmups; i < samples; i++) {
      const row = await page.evaluate(async () => {
        const element = document.querySelector('.messages')
        const frames = []
        const positions = []
        const start = performance.now()
        const max = element.scrollHeight - element.clientHeight
        let previous
        await new Promise(resolve => {
          function tick(time) {
            if (previous !== undefined) frames.push(time - previous)
            previous = time
            const phase = Math.min((time - start) / 1000, 1)
            element.scrollTop = phase < 0.5 ? max * phase * 2 : max * (2 - phase * 2)
            positions.push(element.scrollTop)
            if (phase < 1) requestAnimationFrame(tick)
            else resolve()
          }
          requestAnimationFrame(tick)
        })
        const end = performance.now()
        return { startTime: start, endTime: end, elapsedMs: end - start, frames, positions,
          messageCount: element.querySelectorAll('.message-row').length,
          scrollHeight: element.scrollHeight, clientHeight: element.clientHeight,
          longTasks: window.__performanceLongTasks.filter(entry => entry.startTime >= start && entry.startTime < end) }
      })
      assert.equal(row.messageCount, 200)
      assert(row.scrollHeight > row.clientHeight && row.frames.length > 0)
      const record = { scenario: 'scroll-200', viewport, iteration: i, ...row }
      await appendFile(resolve(output, 'scroll.jsonl'), JSON.stringify(record) + '\n')
      if (i >= 0) results.push(record)
    }
    await back()
    await page.getByRole('button', { name: '打开工单：售后进度与历史咨询 9998', exact: true }).click()
    const input = page.getByRole('textbox', { name: '回复客户', exact: true })
    await expect(input).toBeEditable()
    await expect(page.locator('.conversation-heading')).toContainText('performance-owned-9998')
    await expect(page.locator('.messages .message-row')).toHaveCount(200)
    await expect(page.locator('.messages')).toHaveAttribute('aria-busy', 'false')
    await expect.poll(() => requestsInFlight.size).toBe(0)
    await input.fill('性能验收草稿，保留在本工单。')
    await input.focus()
    await expect(input).toBeFocused()
    await expect(page.getByRole('button', { name: '发送', exact: true })).toBeEnabled()
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'Viewport overflow')
    await page.screenshot({ path: resolve(output, `desk-${viewport.width}.png`), animations: 'disabled' })
    const information = page.getByRole('button', { name: '工单信息', exact: true })
    if (await information.isVisible()) await information.click()
    await page.getByRole('button', { name: '查看原始会话依据', exact: true }).click()
    const sources = page.getByRole('dialog', { name: '原始会话依据', exact: true })
    await expect(sources.locator('.source-list li')).toHaveCount(3)
    await expect(sources).toContainText('[performance-owned-9998:')
    await expect(sources).toBeInViewport()
    // 固定动画的最终状态，避免把仍在入场的抽屉或对话框当成验收截图。
    await page.screenshot({ path: resolve(output, `desk-sources-${viewport.width}.png`), animations: 'disabled' })
    await writeFile(resolve(output, `long-tasks-${viewport.width}.json`), JSON.stringify(await page.evaluate(() => window.__performanceLongTasks)))
    await context.close()
  }
  assert.deepEqual(errors, [], 'Harness/browser errors must be retained and resolved')
} finally {
  await writeFile(resolve(output, 'api-evidence.json'), JSON.stringify(apiEvidence))
  await writeFile(resolve(output, 'errors.json'), JSON.stringify(errors, null, 2))
  await writeFile(resolve(output, 'results.json'), JSON.stringify(results))
  if (browser) await browser.close()
  await new Promise(resolve => server.close(resolve))
}
console.log(`PERFORMANCE_${mode.toUpperCase()}_COMPLETE`)
