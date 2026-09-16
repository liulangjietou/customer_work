import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const permissions = ['skill:view', 'skill:edit', 'skill:export']
async function setup(page: Page) {
  await page.route('**/api/auth/permissions', r => r.fulfill({ json: ok(permissions) }))
  await page.route('**/api/aiconfig/skill?*', r => r.fulfill({ json: ok({ list: [{ id: 73, skillName: '下载技能', skillCode: 'download-skill',
    content: '# 已保存的原文', description: '', status: 1, storageTargets: ['local'], files: [] }], total: 1 }) }))
  await page.goto('/aiconfig/skill')
}
async function settled(page: Page, held: Route, json: unknown) {
  const response = page.waitForResponse(r => r.url() === held.request().url())
  await held.fulfill({ json })
  await (await response).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}
test('Skill 旧登录下载完成不能解除新下载的进行中状态', async ({ page }) => {
  const held: Route[] = []
  await page.route('**/api/aiconfig/skill/73/download', route => { held.push(route) })
  await setup(page)
  const download = page.getByRole('row').filter({ hasText: '下载技能' }).getByRole('button', { name: '下载', exact: true })
  await download.click()
  await expect.poll(() => held.length).toBe(1)
  await page.evaluate(async permissions => {
    const path = '/src/store/auth.ts'
    const { useAuthStore } = await import(path)
    const auth = useAuthStore()
    auth.applyLoginResult({ token: auth.token, nickname: '后来登录', forceChangePassword: false, approvalStatus: 'APPROVED', approvalRemark: null }, 'new-user')
    auth.permissions = permissions
  }, permissions)
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(download).toBeEnabled()
  await download.click()
  await expect.poll(() => held.length).toBe(2)
  await settled(page, held[0], { code: 50000, message: '旧导出失败' })
  await expect(download).toBeDisabled()
  await settled(page, held[1], { code: 50000, message: '本次导出失败' })
  await expect(download).toBeEnabled()
  await expect(page.getByText('本次导出失败', { exact: true })).toBeVisible()
  await expect(page.getByText('旧导出失败', { exact: true })).toHaveCount(0)
})
test('Skill 上传解析中不能保存旧正文，失败后可保留输入重试', async ({ page }) => {
  let held: Route | undefined
  await page.route('**/api/aiconfig/skill/parse-upload', route => { held = route })
  await setup(page)
  await page.getByRole('row').filter({ hasText: '下载技能' }).getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '编辑 Skill', exact: true })
  await dialog.locator('input[type=file]').setInputFiles({ name: 'SKILL.md', mimeType: 'text/markdown', buffer: Buffer.from('# 新正文') })
  await expect.poll(() => !!held).toBe(true)
  const save = dialog.getByRole('button', { name: '保存 Skill', exact: true })
  await expect(save).toBeDisabled()
  await settled(page, held!, { code: 50000, message: '技能包解析失败' })
  await expect(save).toBeEnabled()
  await expect(dialog.locator('textarea').first()).toHaveValue('# 已保存的原文')
  held = undefined
  await dialog.locator('input[type=file]').setInputFiles({ name: 'SKILL.md', mimeType: 'text/markdown', buffer: Buffer.from('# 正确正文') })
  await expect.poll(() => !!held).toBe(true)
  await expect(save).toBeDisabled()
  await settled(page, held!, ok({ content: '# 正确正文', files: [] }))
  await expect(save).toBeEnabled()
  await expect(dialog.locator('textarea').first()).toHaveValue('# 正确正文')
})
