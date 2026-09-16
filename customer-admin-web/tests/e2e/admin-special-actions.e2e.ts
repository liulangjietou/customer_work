import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const skills = [73, 74].map(id => ({ id, skillName: `技能${id}`, skillCode: `skill-${id}`,
  content: `# 技能${id}原文`, description: '版本与上传归属验收', status: 1,
  currentVersionId: id * 10, latestVersionNo: 1, contentHash: 'a'.repeat(64), storageTargets: ['local'], files: [] }))
async function skillPage(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['skill:view', 'skill:edit']) }))
  await page.route('**/api/aiconfig/skill?*', r => r.fulfill({ json: ok({ list: skills, total: 2 }) }))
  await page.goto('/aiconfig/skill')
}
async function releaseAndPaint(page: Page, route: Route, data: unknown) {
  const received = page.waitForResponse(response => response.url() === route.request().url())
  await route.fulfill({ json: ok(data) })
  await (await received).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}

const version = (id: number) => ({ id: id * 10, versionNo: 1, contentHash: 'a'.repeat(64), changeNote: `仅属于技能${id}`, createTime: '2026-09-15 10:00:00' })

test('Skill 旧版本查询不能把另一技能的版本明细覆盖', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/aiconfig/skill/73/versions', route => { held = route })
  await page.route('**/api/aiconfig/skill/74/versions', route => route.fulfill({ json: ok([version(74)]) }))
  await skillPage(page)
  await page.getByRole('row').filter({ hasText: '技能73' }).getByRole('button', { name: '版本', exact: true }).click()
  await expect.poll(() => !!held).toBe(true)
  await page.getByRole('dialog', { name: '不可变版本 · 技能73', exact: true }).locator('.el-dialog__headerbtn').click()
  await page.getByRole('row').filter({ hasText: '技能74' }).getByRole('button', { name: '版本', exact: true }).click()
  const current = page.getByRole('dialog', { name: '不可变版本 · 技能74', exact: true })
  await expect(current.getByText('仅属于技能74', { exact: true })).toBeVisible()
  await releaseAndPaint(page, held!, [version(73)])
  await expect(current.getByText('仅属于技能73', { exact: true })).toHaveCount(0)
  await expect(current.getByText('仅属于技能74', { exact: true })).toBeVisible()
})

test('Skill 上传解析的迟到结果不能覆盖后来打开的编辑目标', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/aiconfig/skill/parse-upload', route => { held = route })
  await skillPage(page)
  await page.getByRole('row').filter({ hasText: '技能73' }).getByRole('button', { name: '编辑', exact: true }).click()
  let dialog = page.getByRole('dialog', { name: '编辑 Skill', exact: true })
  await dialog.locator('input[type=file]').setInputFiles({ name: 'SKILL.md', mimeType: 'text/markdown', buffer: Buffer.from('# 旧技能的新正文') })
  await expect.poll(() => !!held).toBe(true)
  await dialog.locator('.el-dialog__headerbtn').click()
  await page.getByRole('row').filter({ hasText: '技能74' }).getByRole('button', { name: '编辑', exact: true }).click()
  dialog = page.getByRole('dialog', { name: '编辑 Skill', exact: true })
  await expect(dialog.getByRole('textbox', { name: /名称$/ })).toHaveValue('技能74')
  await releaseAndPaint(page, held!, { content: '# 旧技能的新正文', files: [] })
  await expect(dialog.locator('textarea').first()).toHaveValue('# 技能74原文')
})

for (const kind of ['params', 'transforms'] as const) {
  test(`SQL ${kind} 只读账号可查看明细但不出现子资源写入口`, async ({ page }) => {
    const params = kind === 'params'
    const row = params
      ? { id: 901, paramName: 'query_user', paramDesc: '用户条件', paramType: 'STRING', required: false, defaultValue: '', isPageNum: false, isPageSize: false, sort: 0 }
      : { id: 902, fieldName: 'created_at', transformType: 'DATE_FORMAT', transformConfig: 'yyyy-MM-dd' }
    await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(['sql-define:view']) }))
    await page.route('**/api/sql/define?*', r => r.fulfill({ json: ok({ total: 1, list: [{
      id: 76, defineKey: 'acceptance_report', sqlDescribe: '参数与转换器', datasourceId: 75, datasourceName: '验收源',
      querySql: 'SELECT 1', countSql: '', enabled: true, autoLoad: false,
    }] }) }))
    await page.route(`**/api/sql/define/76/${kind}`, route => route.fulfill({ json: ok([row]) }))
    await page.goto('/sql/define')
    await page.getByRole('row').filter({ hasText: 'acceptance_report' }).getByRole('button', { name: params ? '参数配置' : '列转换器', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: `${params ? '参数配置' : '列转换器'} · acceptance_report`, exact: true })
    await expect(dialog.getByText(params ? 'query_user' : 'created_at', { exact: true })).toBeVisible()
    await expect(dialog.getByRole('button', { name: /^(新增参数|新增转换器|编辑|删除)$/ })).toHaveCount(0)
  })
}
