import { defineConfig, devices } from '@playwright/test'

const port = 4176
const origin = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './tests/e2e',
  // 独立后缀避免被 Vitest 当成单元测试收集。
  testMatch: '**/*.e2e.ts',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: origin,
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    serviceWorkers: 'block',
  },
  projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
  webServer: {
    command: `npm run dev -- --host 127.0.0.1 --port ${port} --strictPort`,
    url: `${origin}/login`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
