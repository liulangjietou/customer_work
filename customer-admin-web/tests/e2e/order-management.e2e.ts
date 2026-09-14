import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'

const ORDER_A = {
  orderId: 'order-A', userId: 'customer-A', username: '林女士', productId: 'product-A',
  productName: '蓝牙耳机', amount: '299.00', status: '待发货',
  receiverAddr: '杭州市西湖区示例路 18 号', createdAtMs: 1789086000000,
}
const ORDER_B = { ...ORDER_A, orderId: 'order-B', productName: '便携音箱' }
const detail = (order = ORDER_A) => ({ ...order, logisticsTrace: '仓库正在备货' })
const ok = (data: unknown) => ({ code: 0, data })
const failure = { code: 50000, message: '订单服务暂时不可用' }

async function permissions(page: Page, editable = true) {
  await page.route('**/api/auth/permissions', (route) => route.fulfill({
    json: ok(editable ? ['user-order:view', 'user-order:edit'] : ['user-order:view']),
  }))
}

async function listFixture(page: Page, rows = [ORDER_A, ORDER_B]) {
  await permissions(page)
  await page.route('**/api/ticket/orders/page?*', (route) => route.fulfill({
    json: ok({ items: rows, total: rows.length }),
  }))
}

function row(page: Page, id = 'order-A') {
  return page.locator('.el-table__body tr').filter({ hasText: id })
}

async function settleResponse(page: Page, route: Route, data: unknown) {
  const response = page.waitForResponse((item) => item.url() === route.request().url())
  await route.fulfill({ json: ok(data) })
  await (await response).finished()
  await page.evaluate(() => new Promise<void>((resolve) =>
    requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
  ))
}

test('订单首次读取失败可原地重试，成功前不把失败显示为空订单', async ({ page }) => {
  await permissions(page)
  let failing = true
  await page.route('**/api/ticket/orders/page?*', (route) => route.fulfill({
    json: failing ? failure : ok({ items: [], total: 0 }),
  }))
  await page.goto('/ticket/user-order')
  const error = page.locator('.crud-load-state')
  await expect(error).toContainText('数据加载失败')
  await expect(page.getByText('暂无符合条件的订单', { exact: true })).not.toBeVisible()
  failing = false
  await error.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(error).not.toBeVisible()
  await expect(page.getByText('暂无符合条件的订单', { exact: true })).toBeVisible()
})

test('订单刷新失败保留上次数据并标明过期，重试后替换', async ({ page }, testInfo) => {
  await listFixture(page)
  await page.setViewportSize({ width: 1440, height: 960 })
  await page.goto('/ticket/user-order')
  await expect(row(page)).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('orders-1440.png'), fullPage: true, animations: 'disabled' })
  let failing = true
  await page.route('**/api/ticket/orders/page?*', (route) => route.fulfill({
    json: failing ? failure : ok({ items: [ORDER_B], total: 1 }),
  }))
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const error = page.locator('.crud-load-state')
  await expect(error).toContainText('已保留上次结果')
  await expect(row(page)).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('orders-refresh-error-1440.png'), fullPage: true, animations: 'disabled' })
  failing = false
  await error.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(row(page)).not.toBeVisible()
  await expect(row(page, 'order-B')).toBeVisible()
})

test('连续查询只显示最后一次筛选结果，迟到响应不回填', async ({ page }) => {
  await permissions(page)
  let held: Route | undefined
  await page.route('**/api/ticket/orders/page?*', (route) => {
    const id = new URL(route.request().url()).searchParams.get('orderId')
    if (id === 'order-A') { held = route; return }
    return route.fulfill({ json: ok({ items: id ? [ORDER_B] : [], total: id ? 1 : 0 }) })
  })
  await page.goto('/ticket/user-order')
  await page.getByPlaceholder('订单号', { exact: true }).fill('order-A')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.getByPlaceholder('订单号', { exact: true }).fill('order-B')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(row(page, 'order-B')).toBeVisible()
  await settleResponse(page, held!, { items: [ORDER_A], total: 1 })
  await expect(row(page, 'order-B')).toBeVisible()
  await expect(row(page)).not.toBeVisible()
})

test('关闭订单 A 后查看 B，A 的迟到详情不能覆盖 B', async ({ page }) => {
  await listFixture(page)
  let held: Route | undefined
  await page.route('**/api/ticket/orders/order-A', (route) => { held = route })
  await page.route('**/api/ticket/orders/order-B', (route) => route.fulfill({ json: ok(detail(ORDER_B)) }))
  await page.goto('/ticket/user-order')
  await row(page).getByRole('button', { name: '详情', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.getByRole('dialog', { name: '订单详情', exact: true }).getByRole('button', { name: '关闭此对话框' }).click()
  await row(page, 'order-B').getByRole('button', { name: '详情', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '订单详情', exact: true })
  await expect(drawer).toContainText('便携音箱')
  await settleResponse(page, held!, detail())
  await expect(drawer).toContainText('便携音箱')
  await expect(drawer).not.toContainText('蓝牙耳机')
})

test('切换订单时清掉旧详情，失败只重试当前订单', async ({ page }) => {
  await listFixture(page)
  await page.route('**/api/ticket/orders/order-A', (route) => route.fulfill({ json: ok(detail()) }))
  let failing = true
  await page.route('**/api/ticket/orders/order-B', (route) => route.fulfill({ json: failing ? failure : ok(detail(ORDER_B)) }))
  await page.goto('/ticket/user-order')
  await row(page).getByRole('button', { name: '详情', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '订单详情', exact: true })
  await expect(drawer).toContainText('蓝牙耳机')
  await drawer.getByRole('button', { name: '关闭此对话框' }).click()
  await row(page, 'order-B').getByRole('button', { name: '详情', exact: true }).click()
  await expect(drawer).not.toContainText('蓝牙耳机')
  await expect(drawer).toContainText('订单详情加载失败')
  failing = false
  await drawer.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(drawer).toContainText('便携音箱')
  await expect(drawer).not.toContainText('订单详情加载失败')
})

test('改址提交中锁定当前表单，失败保留地址并可重试', async ({ page }, testInfo) => {
  await listFixture(page)
  let held: Route | undefined
  const writes: unknown[] = []
  await page.route('**/api/ticket/orders/order-A/modify-address', (route) => {
    expect(route.request().method()).toBe('POST')
    writes.push(route.request().postDataJSON())
    if (writes.length === 1) { held = route; return }
    return route.fulfill({ json: ok(null) })
  })
  await page.goto('/ticket/user-order')
  await row(page).getByRole('button', { name: '改址', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '修改收货地址', exact: true })
  const input = dialog.getByRole('textbox')
  await input.fill('苏州市工业园区示例路 88 号')
  const submit = dialog.getByRole('button', { name: '确认改址', exact: true })
  await submit.click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await expect(submit).toBeDisabled()
  await expect(input).toBeDisabled()
  await held!.fulfill({ json: failure })
  await expect(dialog.getByRole('alert')).toContainText('订单服务暂时不可用')
  await expect(input).toHaveValue('苏州市工业园区示例路 88 号')
  await page.screenshot({ path: testInfo.outputPath('order-address-error.png'), fullPage: true, animations: 'disabled' })
  await submit.click()
  await expect(dialog).not.toBeVisible()
  expect(writes).toEqual(Array(2).fill({ newAddress: '苏州市工业园区示例路 88 号' }))
})

test('取消订单可撤回确认，写入失败保留原因且不显示成功', async ({ page }) => {
  await listFixture(page)
  let writes = 0
  await page.route('**/api/ticket/orders/order-A/cancel', (route) => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().postDataJSON()).toEqual({ reason: '客户更换型号' })
    writes += 1
    return route.fulfill({ json: failure })
  })
  await page.goto('/ticket/user-order')
  await row(page).getByRole('button', { name: '取消订单', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '取消订单', exact: true })
  await dialog.getByRole('textbox').fill('客户更换型号')
  await dialog.getByRole('button', { name: '确认取消', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '取消', exact: true }).click()
  expect(writes).toBe(0)
  await expect(dialog.getByRole('textbox')).toHaveValue('客户更换型号')
  await dialog.getByRole('button', { name: '确认取消', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('订单服务暂时不可用')
  await expect(dialog.getByRole('textbox')).toHaveValue('客户更换型号')
  await expect(page.getByText('订单已取消', { exact: true })).not.toBeVisible()
  expect(writes).toBe(1)
})

for (const change of ['permissions', 'principal'] as const) {
  test(`订单详情读取期间 ${change} 变化，关闭旧详情并忽略迟到结果`, async ({ page }) => {
    await listFixture(page)
    let held: Route | undefined
    await page.route('**/api/ticket/orders/order-A', (route) => { held = route })
    await page.goto('/ticket/user-order')
    await row(page).getByRole('button', { name: '详情', exact: true }).click()
    await expect.poll(() => Boolean(held)).toBe(true)
    await page.route('**/api/auth/permissions', (route) => route.fulfill({ json: ok([]) }))
    await page.evaluate(async (mode) => {
      const modulePath = '/src/store/auth.ts'
      const { useAuthStore } = await import(modulePath)
      if (mode === 'permissions') await useAuthStore().loadPermissions()
      else useAuthStore().token = 'another-account-token'
    }, change)
    const drawer = page.getByRole('dialog', { name: '订单详情', exact: true })
    await expect(drawer).not.toBeVisible()
    await settleResponse(page, held!, { ...detail(), logisticsTrace: '仅旧身份可读的物流记录' })
    await expect(page.getByText('仅旧身份可读的物流记录', { exact: true })).toHaveCount(0)
  })
}

test('等待取消确认时撤销编辑权限，关闭确认且不再提交', async ({ page }) => {
  await listFixture(page)
  const writes: string[] = []
  page.on('request', (request) => { if (request.method() === 'POST') writes.push(request.url()) })
  await page.goto('/ticket/user-order')
  await row(page).getByRole('button', { name: '取消订单', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '取消订单', exact: true })
  await dialog.getByRole('textbox').fill('需要重新核对')
  await dialog.getByRole('button', { name: '确认取消', exact: true }).click()
  await expect(page.locator('.el-message-box')).toBeVisible()
  await permissions(page, false)
  await page.evaluate(async () => {
    const modulePath = '/src/store/auth.ts'
    const { useAuthStore } = await import(modulePath)
    await useAuthStore().loadPermissions()
  })
  await expect(page.locator('.el-message-box')).not.toBeVisible()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByRole('button', { name: '改址', exact: true })).toHaveCount(0)
  expect(writes).toEqual([])
})

for (const action of [
  { button: '改址', dialog: '修改收货地址', submit: '确认改址', api: 'modify-address' },
  { button: '取消订单', dialog: '取消订单', submit: '确认取消', api: 'cancel' },
]) {
  test(`${action.button}成功后刷新尚未完成，键盘重开表单不会永久禁用`, async ({ page }) => {
    await permissions(page)
    let saved = false
    let held: Route | undefined
    await page.route('**/api/ticket/orders/page?*', (route) => {
      if (saved) { held = route; return }
      return route.fulfill({ json: ok({ items: [ORDER_A], total: 1 }) })
    })
    await page.route(`**/api/ticket/orders/order-A/${action.api}`, (route) => {
      saved = true
      return route.fulfill({ json: ok(null) })
    })
    await page.goto('/ticket/user-order')
    const trigger = row(page).getByRole('button', { name: action.button, exact: true })
    await trigger.click()
    const dialog = page.getByRole('dialog', { name: action.dialog, exact: true })
    await dialog.getByRole('textbox').fill('客户已确认的变更内容')
    await dialog.getByRole('button', { name: action.submit, exact: true }).click()
    if (action.api === 'cancel')
      await page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expect(dialog).not.toBeVisible()
    await expect.poll(() => Boolean(held)).toBe(true)
    await trigger.focus()
    await page.keyboard.press('Enter')
    await expect(dialog).toBeVisible()
    await settleResponse(page, held!, { items: [ORDER_A], total: 1 })
    await expect(dialog.getByRole('textbox')).toBeEnabled()
    await expect(dialog.getByRole('button', { name: action.submit, exact: true })).toBeEnabled()
  })
}

test('只读订单页不提供写操作，390px 长详情可完整阅读', async ({ page }, testInfo) => {
  const longOrder = { ...ORDER_A, productName: '便携会议终端'.repeat(12), receiverAddr: '很长的收货地址'.repeat(20) }
  await listFixture(page, [longOrder])
  await permissions(page, false)
  const writes: string[] = []
  page.on('request', (request) => { if (request.method() !== 'GET' && request.url().includes('/api/ticket/orders')) writes.push(request.url()) })
  await page.route('**/api/ticket/orders/order-A', (route) => route.fulfill({
    json: ok({ ...detail(longOrder), logisticsTrace: '<script>alert(1)</script>' + '物流节点'.repeat(50) }),
  }))
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/ticket/user-order')
  await expect(row(page)).toBeVisible()
  await expect(page.getByRole('button', { name: '改址', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '取消订单', exact: true })).toHaveCount(0)
  await row(page).getByRole('button', { name: '详情', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '订单详情', exact: true })
  await expect(drawer).toContainText('<script>alert(1)</script>')
  const bounds = await drawer.boundingBox()
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(bounds!.width).toBeLessThanOrEqual(390)
  expect((await drawer.locator('.el-descriptions__label').first().boundingBox())!.width).toBeGreaterThanOrEqual(80)
  expect(await drawer.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  await page.screenshot({ path: testInfo.outputPath('order-detail-readonly-390.png'), fullPage: true, animations: 'disabled' })
  expect(writes).toEqual([])
})
