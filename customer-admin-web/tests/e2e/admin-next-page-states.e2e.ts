import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const note = '确认归属后再处理，保留操作证据。'.repeat(24)
const date = '2026-09-15 10:00:00'
// 下一批待执行的真实页面状态验收。只列已读过实际响应映射的页面。
const pages = [
  { path: '/system/tenant', api: '/tenant/page', permission: 'tenant:view', shape: 'list', marker: '验收租户',
    empty: '暂无符合条件的租户', search: '搜索', readonly: true,
    row: { id: 801, tenantCode: 'acceptance', tenantName: '验收租户', status: 'ACTIVE', contactName: '负责人',
      contactPhone: '', contactEmail: 'acceptance@example.test', remark: note, reserved: false, expireTime: null, createTime: date } },
  { path: '/system/config-version', api: '/config-version/page', permission: 'config-version:view', shape: 'list', marker: 'acceptance-agent',
    empty: '暂无配置发布记录', search: '搜索', readonly: true,
    row: { id: 802, configType: 'AGENT', targetCode: 'acceptance-agent', targetId: 7, version: 2, content: null,
      contentHash: 'a'.repeat(64), publishScope: 'ALL', grayTenants: null, dataId: 'acceptance-agent.json',
      status: 'SUCCESS', sourceVersion: null, remark: note, createTime: date } },
  { path: '/aiconfig/scheduled-task', api: '/aiconfig/scheduled-task/page', permission: 'scheduler:view', shape: 'records', marker: '验收定时任务',
    empty: '暂无符合条件的定时任务', search: '搜索', readonly: true,
    row: { id: 803, taskCode: 'acceptance-task', taskName: '验收定时任务', agentId: 7, agentName: '服务助手',
      prompt: note, cron: '0 0 9 * * ?', enabled: true, remark: note, scheduleMode: 'internal', createTime: date } },
  { path: '/aiconfig/agent-task', api: '/aiconfig/agent-task/page', permission: 'agent-task:view', shape: 'records', marker: 'acceptance-long-task',
    empty: '暂无符合条件的后台任务', search: '搜索', readonly: true,
    row: { id: 804, taskId: 'acceptance-long-task', parentAgentCode: 'service-agent', subAgentId: 'readonly-helper',
      status: 'COMPLETED', result: note, resultTruncated: true, createdAt: date, costMs: 1250 } },
  { path: '/aiconfig/channel-robot', api: '/channel-robots/page', permission: 'channel-robot:view', shape: 'records', marker: '验收渠道',
    empty: '暂无符合条件的渠道机器人', search: '搜索', readonly: true,
    row: { id: 805, channelType: 'dingtalk', robotName: '验收渠道', appKey: 'acceptance-app', robotCode: 'acceptance-robot',
      callbackMode: 'plaintext', hasEncodingAesKey: false, agentCode: 'service-agent', sessionMode: 'continuous',
      status: 1, remark: note, hasSecret: true, createTime: date, updateTime: date } },
  // Project 统一使用 workspace 权限，操作按租户和角色数据范围约束，不伪造不存在的只读角色。
  { path: '/project', api: '/workspace/project', permission: 'workspace', shape: 'array', marker: '验收项目',
    empty: '还没有项目，点击“新建 Project”开始整理会话', search: '搜索', readonly: false,
    row: { id: 806, projectName: '验收项目', description: note, sessionCount: 2, createTime: date } },
  { path: '/ops/badcase', api: '/badcase/page', permission: 'badcase:view', shape: 'list', marker: '验收问题',
    empty: '暂无符合条件的 Badcase', search: '搜索', readonly: true,
    row: { id: 'acceptance-badcase', source: 'NEGATIVE_FEEDBACK', sessionId: 'acceptance-session', messageId: null,
      userInput: '验收问题', agentReply: note, signalHash: 's'.repeat(64), detail: note, status: 'PENDING',
      adoptedKnowledgeId: null, adoptedEvalCaseId: null, handledBy: null, handledAtMs: 0, ignoreReason: null,
      createdAtMs: 1789441200000, pending: true } },
]

for (const item of pages) {
  test(`${item.path} 查询状态、错误重试、长记录和窄屏可读`, async ({ page }, testInfo) => {
    let state: 'error' | 'data' | 'empty' = 'error'
    let requests = 0
    const writes: string[] = []
    await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok([item.permission]) }))
    await page.route(url => url.pathname === '/api' + item.api, route => {
      requests += 1
      const rows = state === 'data' ? [item.row] : []
      const data = item.shape === 'array' ? rows : item.shape === 'records'
        ? { total: rows.length, current: 1, size: 10, records: rows } : { total: rows.length, pageNum: 1, pageSize: 10, list: rows }
      return route.fulfill({ json: state === 'error' ? { code: 50000, message: '查询暂不可用' } : ok(data) })
    })
    page.on('request', request => {
      if (new URL(request.url()).pathname.startsWith('/api/') && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) writes.push(request.url())
    })
    await page.goto(item.path)
    const error = page.locator('.layout-main .crud-load-state').first()
    await expect(error).toBeVisible()
    state = 'data'
    await error.getByRole('button', { name: '重新加载' }).click()
    const record = page.getByRole('row').filter({ hasText: item.marker })
    await expect(record).toBeVisible()
    if (item.readonly) await expect(record.getByRole('button', { name: /^(编辑|删除|冻结|退租|恢复|忽略|立即执行|取消|回滚至此|灰度)$/ })).toHaveCount(0)
    state = 'error'
    await page.getByRole('button', { name: item.search, exact: true }).click()
    await expect(error).toContainText('已保留上次结果')
    await expect(record).toBeVisible()
    state = 'data'
    await error.getByRole('button', { name: '重新加载' }).click()
    await expect(error).not.toBeVisible()
    await page.setViewportSize({ width: 390, height: 844 })
    await expect.poll(() => page.locator('.layout-aside').evaluate(e => e.getBoundingClientRect().width)).toBeLessThanOrEqual(1)
    if (item.path === '/aiconfig/channel-robot') {
      const clipped = await page.locator('.page > .el-alert').evaluateAll(alerts => alerts.flatMap(alert => {
        const right = alert.getBoundingClientRect().right
        return Array.from(alert.querySelectorAll('.el-alert__content, code')).filter(e => e.getBoundingClientRect().right > right + 1).map(e => e.textContent)
      }))
      expect(clipped, '接入说明及回调地址不能被卡片边缘裁切').toEqual([])
    }
    await record.scrollIntoViewIfNeeded()
    expect((await record.boundingBox())!.height).toBeLessThanOrEqual(120)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    await expect(page.getByText('查询暂不可用', { exact: true })).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('next-business-data-390.png') })
    state = 'empty'
    await page.getByRole('button', { name: item.search, exact: true }).click()
    await expect(page.locator('.layout-main').getByText(item.empty, { exact: true })).toBeVisible()
    expect(requests).toBeGreaterThanOrEqual(5)
    expect(writes).toEqual([])
  })
}
