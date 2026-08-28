import { defineConfig, devices } from '@playwright/test'
import { LOGIN_E2E_ORIGIN, LOGIN_E2E_PORT } from './tests/e2e/loginTestEnvironment'

export default defineConfig({
  testDir: './tests/e2e',
  // 使用独立后缀，避免 Vitest 把 Playwright 用例当作单元测试收集。
  testMatch: '**/*.e2e.ts',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: LOGIN_E2E_ORIGIN,
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chrome',
      // GitHub ubuntu-latest 与本机均已有 Chrome，避免 CI 每次额外下载浏览器。
      use: { ...devices['Desktop Chrome'], channel: 'chrome' },
    },
  ],
  webServer: {
    command: `npm run dev -- --host 127.0.0.1 --port ${LOGIN_E2E_PORT} --strictPort`,
    url: `${LOGIN_E2E_ORIGIN}/login?redirect=/home`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
