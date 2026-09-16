import type { Page, WebSocketRoute } from '@playwright/test'

export const ticket = (id: string) => ({
  id,
  sessionId: `session-${id}`,
  userId: `客户-${id}`,
  title: `售后进度 ${id}`,
  status: 'PROCESSING',
  assignee: 'admin-shell-e2e',
  category: 'AFTER_SALE',
  priority: 'NORMAL',
  createdAtMs: 1789070400000,
  updatedAtMs: 1789070400000,
  handoffAtMs: 1789070400000,
  claimedAtMs: 1789070430000,
  reopenCount: 0,
})

export async function deskFixture(page: Page) {
  const sockets: WebSocketRoute[] = []
  await page.route('**/api/auth/permissions', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: ['user-ticket:view', 'user-ticket:reply', 'user-ticket:edit', 'user-ticket:transfer'],
      },
    }),
  )
  await page.route('**/api/ticket/page?**', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: { total: 2, items: [ticket('TK-1'), ticket('TK-2')] },
      },
    }),
  )
  await page.route('**/api/ticket/ws-credential', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          token: 'fixture',
          agentId: 'admin-shell-e2e',
          tenantId: 'default',
          wsUrl: 'ws://127.0.0.1:4174/ws/agent',
          expiresAtMs: Date.now() + 3600000,
        },
      },
    }),
  )
  await page.routeWebSocket('**/ws/agent?**', (socket) => {
    sockets.push(socket)
    socket.onMessage(() => {})
  })
  await page.route(/\/api\/ticket\/TK-[12]$/, (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          ticket: ticket(new URL(route.request().url()).pathname.split('/').pop()!),
          events: [],
        },
      },
    }),
  )
  await page.route('**/api/ticket/*/messages?**', (route) =>
    route.fulfill({ json: { code: 0, data: [] } }),
  )
  await page.route('**/api/ticket/TK-*/assist', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          ticketId: new URL(route.request().url()).pathname.split('/').at(-2),
          summary: {
            oneLineSummary: '',
            userIntent: '',
            emotion: null,
            triedSolutions: [],
            pendingIssues: [],
            suggestedNextStep: '',
            suggestedReply: '',
            fromModel: false,
            evidence: {
              version: 'summary-v1:empty',
              generatedAtMs: 1789070400000,
              historyLimit: 30,
              truncated: false,
              sources: [],
            },
          },
        },
      },
    }),
  )
  return sockets
}
