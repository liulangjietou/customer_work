import { expect, test } from './fixtures/adminTestFixture'
import type { Locator, Page } from '@playwright/test'

const themes = [
  ['system', 'System 随行'], ['atlas', 'Atlas 翡翠'], ['ocean', 'Ocean 深海'],
  ['violet', 'Violet 智紫'], ['ember', 'Ember 暖焰'], ['dawn', 'Dawn 晨曦'],
  ['night', 'Night 夜航'], ['aurora', 'Aurora 极光'], ['graphite', 'Graphite 墨岩'],
] as const
const hosts = [
  ['home', '/home'], ['calls', '/system/agent-call-stats'],
  ['evaluation', '/ops/eval'], ['content-guard', '/contentguard/hit-log'],
] as const

async function data(page: Page) {
  await page.route('**/api/agent-call-stats/trend?**', route => route.fulfill({ json: { code: 0, data: [
    { bucket: '2026-09-10', count: 8, avgDurationMs: 800, avgModelMs: 500, avgToolMs: 200, avgMcpMs: 100, avgSkillMs: 0, totalTokens: 900 },
    { bucket: '2026-09-11', count: 12, avgDurationMs: 700, avgModelMs: 400, avgToolMs: 200, avgMcpMs: 100, avgSkillMs: 0, totalTokens: 1200 },
  ] } }))
  await page.route('**/api/eval/runs?**', route => route.fulfill({ json: { code: 0, data: [
    { runId: 'new', evalType: 'INTENT', total: 10, passed: 9, primaryMetric: 0.9, secondaryMetric: 0.8,
      failedCaseIds: ['case-1'], failures: [], metrics: {}, trigger: 'MANUAL', datasetSize: 10, remark: null, createdAtMs: Date.UTC(2026, 8, 11, 10) },
    { runId: 'old', evalType: 'INTENT', total: 10, passed: 7, primaryMetric: 0.7, secondaryMetric: 0.6,
      failedCaseIds: ['case-1', 'case-2', 'case-3'], failures: [], metrics: {}, trigger: 'MANUAL', datasetSize: 10, remark: null, createdAtMs: Date.UTC(2026, 8, 10, 10) },
  ] } }))
  await page.route('**/api/contentguard/hit-log/stats?**', route => route.fulfill({ json: { code: 0, data: {
    total: 20, byAction: [{ label: 'BLOCK', total: 20 }], byDirection: [{ label: 'INBOUND', total: 20 }], topWords: [],
    trend: [{ label: '2026-09-10', total: 8 }, { label: '2026-09-11', total: 12 }], trendGranularity: 'day',
  } } }))
}

async function selectTheme(page: Page, id: string, label: string) {
  await page.getByLabel('选择界面主题', { exact: true }).click()
  await page.getByRole('option').filter({ hasText: label }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', id)
  await page.mouse.move(0, 0)
}

/** 检查实际画布内的图线与文字，不只判断 CSS 变量或模拟的 setOption 参数。 */
async function colorsDrawn(chart: Locator) {
  return chart.evaluate(container => {
    const canvas = container.querySelector('canvas')!
    const context = canvas.getContext('2d')!
    const probe = document.createElement('canvas').getContext('2d')!
    const styles = getComputedStyle(document.documentElement)
    const keys = ['--cw-chart-series-1', '--cw-chart-text']
    const colors = keys.map(key => {
      probe.clearRect(0, 0, 1, 1)
      probe.fillStyle = styles.getPropertyValue(key).trim()
      probe.fillRect(0, 0, 1, 1)
      return [...probe.getImageData(0, 0, 1, 1).data]
    })
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data
    const counts = [0, 0]
    const scale = canvas.width / container.getBoundingClientRect().width
    for (let i = 0; i < pixels.length; i += 4) {
      const x = (i / 4) % canvas.width, y = Math.floor(i / 4 / canvas.width)
      for (let color = 0; color < colors.length; color++) {
        // 小字号的字形边缘经过抗锯齿；文字纳入半透明像素，图线仍要求接近不透明。
        if (pixels[i + 3]! < (color === 0 ? 240 : 128)) continue
        // 系列色只统计绘图区，避免仅图例换色就错误地判为整张图已更新。
        if (color === 0 && (x < 60 * scale || x > canvas.width - 90 * scale || y < 65 * scale || y > canvas.height - 40 * scale)) continue
        if ([0, 1, 2].every(c => Math.abs(pixels[i + c]! - colors[color]![c]!) <= 1)) counts[color]!++
      }
    }
    return { series: counts[0], text: counts[1] }
  })
}

async function assertChart(page: Page, chart: Locator) {
  await expect(chart.locator('canvas')).toBeVisible()
  await chart.scrollIntoViewIfNeeded()
  await expect.poll(async () => (await colorsDrawn(chart)).series).toBeGreaterThan(20)
  await expect.poll(async () => (await colorsDrawn(chart)).text).toBeGreaterThan(20)
  const box = (await chart.boundingBox())!
  await page.mouse.move(box.x + box.width * 0.3, box.y + box.height * 0.55)
  await expect.poll(() => chart.evaluate(container => {
    const tooltip = [...container.querySelectorAll<HTMLDivElement>('div')].find(element => {
      const css = getComputedStyle(element)
      return css.position === 'absolute' && css.borderStyle === 'solid' && css.visibility !== 'hidden'
        && Number(css.opacity) > 0 && !!element.textContent?.trim()
    })
    if (!tooltip) return null
    const css = getComputedStyle(tooltip)
    const pairs = [
      ['--cw-chart-tooltip-bg', css.backgroundColor],
      ['--cw-chart-tooltip-border', css.borderTopColor],
      ['--cw-chart-text', css.color],
    ]
    const matches = pairs.map(([token, actual]) => {
      const sample = document.createElement('span')
      sample.style.setProperty('color', `var(${token})`, 'important')
      // 新建取色元素并禁用过渡，避免在同一帧连续改色时读到上一次的计算值。
      sample.style.setProperty('transition', 'none', 'important')
      document.body.append(sample)
      const expected = getComputedStyle(sample).color
      sample.remove()
      return actual === expected ? true : { token, actual, expected }
    })
    const bounds = tooltip.getBoundingClientRect()
    return { colors: matches, inViewport: bounds.left >= 0 && bounds.right <= window.innerWidth && bounds.top >= 0 }
  })).toEqual({ colors: [true, true, true], inViewport: true })
  await page.mouse.move(0, 0)
}

for (const [name, path] of hosts) {
  test(`${name} 实际图表在九主题、System 明暗及自定义色下同步绘制`, async ({ page }, info) => {
    test.setTimeout(120_000)
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' })
    const readPermission = name === 'calls' ? 'agent-call-stats:view'
      : name === 'content-guard' ? 'sensitive-hit-log:view'
      : name === 'evaluation' ? 'eval:view' : null
    if (readPermission) {
      await page.route('**/api/auth/permissions', route => route.fulfill({
        json: { code: 0, data: [readPermission] },
      }))
    }
    await data(page)
    await page.goto(path)
    const chart = page.locator('.trend-chart').first()
    for (const [id, label] of themes) {
      await selectTheme(page, id, label)
      await assertChart(page, chart)
      await chart.screenshot({ path: info.outputPath(`${name}-${id}.png`), animations: 'disabled' })
    }
    await selectTheme(page, 'system', 'System 随行')
    await page.emulateMedia({ colorScheme: 'dark' })
    await expect(page.locator('html')).toHaveClass(/dark/)
    await assertChart(page, chart)
    await chart.screenshot({ path: info.outputPath(`${name}-system-dark.png`), animations: 'disabled' })
    await page.emulateMedia({ colorScheme: 'light' })
    await expect(page.locator('html')).not.toHaveClass(/dark/)
    await assertChart(page, chart)
    for (const mode of ['light', 'dark']) {
      await page.evaluate(mode => {
        localStorage.setItem('customer-admin-theme-selection', JSON.stringify({ version: 1, kind: 'custom', primaryColor: '#d97706', mode }))
        localStorage.setItem('customer-admin-theme-color', '#d97706')
        localStorage.setItem('customer-admin-theme-mode', mode)
      }, mode)
      await page.reload()
      await expect(page.locator('html')).toHaveAttribute('data-theme', 'custom')
      await assertChart(page, chart)
      await chart.screenshot({ path: info.outputPath(`${name}-custom-${mode}.png`), animations: 'disabled' })
    }
    await page.setViewportSize({ width: 390, height: 844 })
    for (const [id, label] of [['ember', 'Ember 暖焰'], ['night', 'Night 夜航']] as const) {
      await selectTheme(page, id, label)
      await assertChart(page, chart)
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
      await page.screenshot({ path: info.outputPath(`${name}-${id}-390.png`), animations: 'disabled' })
    }
  })
}
