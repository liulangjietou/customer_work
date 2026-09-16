import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

async function openCreate(page: Page) {
  await page.getByRole('button', { name: '新建智能体', exact: true }).click()
  await expect(page.getByRole('region', { name: '智能体配置' })).toBeVisible()
}

async function selectModel(page: Page, name: string) {
  await page.locator('#agent-models .el-select').first().click()
  await page.getByRole('option', { name, exact: true }).click()
}

async function fulfillAndRender(page: Page, route: Route, response: unknown, completionCount = 1) {
  const [completed] = await Promise.all([
    page.waitForResponse((value) => value.url() === route.request().url()),
    route.fulfill({ json: response }),
  ])
  await completed.finished()
  // 等 Axios 的 XHR loadend 及后续 Vue 绘制完成，避免“错误尚未来得及显示”的假通过。
  await page.waitForFunction(
    ({ path, count }) => {
      const state = window as Window & {
        __modelCheckCompletions: Record<string, number>
      }
      return state.__modelCheckCompletions[path] === count
    },
    { path: new URL(route.request().url()).pathname, count: completionCount },
  )
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    const completed: Record<string, number> = {}
    const paths = new WeakMap<XMLHttpRequest, string>()
    Object.assign(window, { __modelCheckCompletions: completed })
    XMLHttpRequest.prototype.open = new Proxy(XMLHttpRequest.prototype.open, {
      apply(target, receiver, args) {
        paths.set(receiver, new URL(String(args[1]), window.location.href).pathname)
        return Reflect.apply(target, receiver, args)
      },
    })
    XMLHttpRequest.prototype.send = new Proxy(XMLHttpRequest.prototype.send, {
      apply(target, receiver, args) {
        const path = paths.get(receiver)
        if (path?.endsWith('/test-connectivity')) {
          receiver.addEventListener(
            'loadend',
            () => {
              requestAnimationFrame(() =>
                requestAnimationFrame(() => {
                  completed[path] = (completed[path] ?? 0) + 1
                }),
              )
            },
            { once: true },
          )
        }
        return Reflect.apply(target, receiver, args)
      },
    })
  })
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: ['agent:view', 'agent:add', 'agent:edit', 'model:view', 'model:edit'],
      },
    }),
  )
  await page.route('**/api/aiconfig/model?*', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          total: 2,
          list: [
            { id: 11, modelName: '模型 A', status: 1, testStatus: 1 },
            { id: 12, modelName: '模型 B', status: 1, testStatus: 2 },
          ],
        },
      },
    }),
  )
})

test('旧模型验证成功不能放行当前验证失败的模型', async ({ page }) => {
  let delayed: Route | undefined
  await page.route('**/api/aiconfig/model/11/test-connectivity', (route) => {
    delayed = route
  })
  await page.route('**/api/aiconfig/model/12/test-connectivity', (route) =>
    route.fulfill({
      json: { code: 0, data: { testStatus: 2, message: '模型 B 不可用' } },
    }),
  )
  await page.goto('/aiconfig/agent')
  await openCreate(page)
  await selectModel(page, '模型 A')
  await expect.poll(() => delayed !== undefined).toBe(true)
  await selectModel(page, '模型 B')
  await expect(page.locator('.connectivity-row')).toContainText('模型 B 不可用')

  await fulfillAndRender(page, delayed!, {
    code: 0,
    data: { testStatus: 1, message: '成功' },
  })

  await expect(page.locator('.connectivity-row')).toContainText('模型 B 不可用')
  await expect(page.getByRole('button', { name: '保存智能体', exact: true }).first()).toBeDisabled()
})

test('重开表单验证同一模型时，旧成功不能覆盖本次失败', async ({ page }) => {
  let delayed: Route | undefined
  let attempts = 0
  await page.route('**/api/aiconfig/model/11/test-connectivity', (route) => {
    attempts += 1
    if (attempts === 1) {
      delayed = route
      return
    }
    return route.fulfill({
      json: { code: 0, data: { testStatus: 2, message: '本次模型 A 不可用' } },
    })
  })
  await page.goto('/aiconfig/agent')
  await openCreate(page)
  await selectModel(page, '模型 A')
  await expect.poll(() => delayed !== undefined).toBe(true)
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await page
    .getByRole('dialog', { name: '离开配置' })
    .getByRole('button', { name: '放弃未保存修改' })
    .click()
  await openCreate(page)
  await selectModel(page, '模型 A')
  await expect(page.locator('.connectivity-row')).toContainText('本次模型 A 不可用')

  await fulfillAndRender(page, delayed!, { code: 0, data: { testStatus: 1, message: '成功' } }, 2)

  await expect(page.getByRole('button', { name: '保存智能体', exact: true }).first()).toBeDisabled()
})

test('配置选项按资源查看权限加载，无权资源不阻塞已授权的模型选项', async ({ page }) => {
  const denied: string[] = []
  for (const path of [
    '/aiconfig/mcp',
    '/aiconfig/skill',
    '/system-tool',
    '/aiconfig/knowledge-base/options',
  ]) {
    await page.route(
      (url) => url.pathname === `/api${path}`,
      (route) => {
        denied.push(path)
        return route.fulfill({
          json: { code: 20001, message: '无权读取该资源' },
        })
      },
    )
  }
  await page.goto('/aiconfig/agent')
  await openCreate(page)
  await page.locator('#agent-models .el-select').first().click()
  await expect(page.getByRole('option', { name: '模型 A', exact: true })).toBeVisible()
  expect(denied).toEqual([])
})
