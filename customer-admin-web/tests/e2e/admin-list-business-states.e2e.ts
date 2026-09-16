import { expect, test } from './fixtures/adminTestFixture'

const ok = (data: unknown) => ({ code: 0, data })
const longText = '已核对服务范围和维护说明。'.repeat(20)

// 显式列出各页实际查询契约，避免用通用空数组掩盖数据映射错误。
const pages = [
  { path: '/system/log', api: '/system/log', permission: 'log:view', empty: '暂无操作日志',
    marker: '验收审计人', row: { id: 70, username: '验收审计人', operation: '修改服务配置', target: 'agent',
      result: 1, ip: '127.0.0.1', eventId: 'owned-event', errorMsg: longText, createTime: '2026-09-15 10:00:00' } },
  { path: '/system/role', api: '/system/role', permission: 'role:view', empty: '暂无符合条件的角色',
    marker: '验收角色', row: { id: 71, roleName: '验收角色', roleCode: 'service', remark: longText,
      status: 1, dataScope: 'TENANT', permissionIds: [], controlPlane: false } },
  { path: '/system/ai-audit', api: '/workspace/ai-coding-audit', permission: 'ai-audit:view', empty: '暂无 AI 编码审计记录',
    marker: '验收操作人', row: { id: 72, username: '验收操作人', operation: 'FILE_SAVE', agentCode: 'helper',
      sessionId: longText, changedFiles: '["README.md"]', result: 1, durationMs: 500, totalTokens: 30,
      createTime: '2026-09-15 10:00:00' } },
  { path: '/aiconfig/skill', api: '/aiconfig/skill', permission: 'skill:view', empty: '暂无符合条件的 Skill',
    marker: '验收技能', row: { id: 73, skillName: '验收技能', skillCode: 'acceptance-skill', description: longText,
      content: '# 服务说明\n' + longText, status: 1, storageTargets: ['local'], files: [], currentVersionId: 701, latestVersionNo: 1 } },
  { path: '/contentguard/sensitive-word', api: '/contentguard/sensitive-word/page', permission: 'sensitive-word:view',
    empty: '暂无符合条件的敏感词', marker: '验收词条', row: { id: 74, word: '验收词条', category: 'CUSTOM',
      action: 'BLOCK', enabled: true, updatedAtMs: 1789441200000 } },
  { path: '/sql/datasource', api: '/sql/datasource', permission: 'sql-datasource:view', empty: '暂无符合条件的数据源',
    marker: '验收数据源', row: { id: 75, name: '验收数据源', jdbcUrl: 'jdbc:mysql://example.test:3306/acceptance',
      username: 'readonly', passwordMasked: '******', enabled: true, remark: longText } },
  { path: '/sql/define', api: '/sql/define', permission: 'sql-define:view', empty: '暂无符合条件的 SQL 定义',
    marker: 'acceptance_report', row: { id: 76, defineKey: 'acceptance_report', sqlDescribe: longText,
      datasourceId: 75, datasourceName: '验收数据源', querySql: 'SELECT 1', countSql: '', enabled: true, autoLoad: false } },
  { path: '/workbench/site', api: '/workbench/site', permission: 'workbench-site:view', empty: '暂无符合条件的工作台站点',
    marker: '验收站点', row: { id: 77, name: '验收站点', category: '客服', url: 'https://example.test/acceptance',
      account: 'readonly', hasPassword: false, passwordMasked: null, enabled: true, remark: longText } },
]

for (const item of pages) {
  test(`${item.path} 真实组件数据态、故障恢复、只读查询与窄屏记录可读`, async ({ page }, testInfo) => {
    let state: 'failed' | 'data' | 'empty' = 'failed'
    let requests = 0
    const writes: string[] = []
    page.on('request', request => {
      if (request.url().includes('/api/') && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) writes.push(request.url())
    })
    await page.route('**/api/auth/permissions', route => route.fulfill({ json: ok([item.permission]) }))
    await page.route(`**/api${item.api}?*`, route => {
      requests += 1
      return route.fulfill({ json: state === 'failed' ? { code: 50000, message: '列表暂不可用' }
        : ok({ total: state === 'data' ? 1 : 0, list: state === 'data' ? [item.row] : [] }) })
    })
    await page.goto(item.path)
    const error = page.locator('.crud-load-state')
    await expect(error).toBeVisible()
    await expect(page.locator('.el-table__empty-text')).not.toBeVisible()
    state = 'data'
    await error.getByRole('button', { name: '重新加载', exact: true }).click()
    const marker = page.getByText(item.marker, { exact: true })
    await expect(marker).toBeVisible()
    const row = page.getByRole('row').filter({ hasText: item.marker })
    await expect(row.getByRole('button', { name: /^(编辑|删除|停用|启用)$/ })).toHaveCount(0)
    state = 'failed'
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await expect(error).toContainText('已保留上次结果')
    await expect(marker).toBeVisible()
    state = 'data'
    await error.getByRole('button', { name: '重新加载', exact: true }).click()
    await expect(error).not.toBeVisible()
    await page.setViewportSize({ width: 390, height: 844 })
    await expect.poll(() => page.locator('.layout-aside').evaluate(element => element.getBoundingClientRect().width)).toBeLessThanOrEqual(1)
    await marker.scrollIntoViewIfNeeded()
    await expect.poll(() => marker.evaluate(element => {
      const box = element.getBoundingClientRect()
      const hit = document.elementFromPoint(box.left + Math.min(24, box.width / 2), box.top + box.height / 2)
      return !!hit && (element.contains(hit) || hit.contains(element))
    })).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    await expect(page.getByText('列表暂不可用', { exact: true })).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('business-data-390.png') })
    state = 'empty'
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await expect(page.getByText(item.empty, { exact: true })).toBeVisible()
    expect(requests).toBeGreaterThanOrEqual(5)
    expect(writes).toEqual([])
  })
}
