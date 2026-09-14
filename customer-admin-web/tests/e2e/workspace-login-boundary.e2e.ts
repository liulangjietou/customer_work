import type { Page } from '@playwright/test'
import { expect, test } from './fixtures/adminTestFixture'
const ok = (data: unknown) => ({ code: 0, data })
const CAPTCHA_IMAGE = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9WnWQAAAAASUVORK5CYII='

async function loginFixtures(page: Page) {
  await page.route('**/api/login-images/list', route => route.fulfill({ json: ok([]) }))
  await page.route('**/api/auth/register-options', route => route.fulfill({ json: ok({
    selfServiceEnabled: false, captchaRequired: true, passwordResetEnabled: false,
  }) }))
  await page.route('**/api/auth/login-captcha/challenge', route => route.fulfill({ json: ok({
    challengeId: 'bootstrap-captcha', ttlSeconds: 60, backgroundImage: CAPTCHA_IMAGE,
    puzzlePieceImage: CAPTCHA_IMAGE, canvasWidth: 320, canvasHeight: 160,
    pieceWidth: 56, pieceHeight: 56, pieceY: 52,
  }) }))
}


test('重新登录后不能继承上一账号的对话、附件和编码草稿', async ({ page }) => {
  await loginFixtures(page)
  await page.goto('/workspace/java-assistant')
  await page.getByRole('textbox', { name: '消息内容' }).fill('上一账号私有草稿')
  await page.getByRole('tab', { name: '代码工作区', exact: true }).click()
  await page.getByRole('textbox', { name: '消息内容' }).fill('上一账号私有代码')
  const remaining = await page.evaluate(async () => {
    const authPath = '/src/store/auth.ts'
    const routerPath = '/src/router/index.ts'
    const chatPath = '/src/store/chatConversations.ts'
    const vibePath = '/src/store/vibeConversations.ts'
    const { default: router } = await import(routerPath)
    const { useAuthStore } = await import(authPath)
    const { useChatConversationsStore } = await import(chatPath)
    const { useVibeConversationsStore } = await import(vibePath)
    await router.replace({ name: 'Login' })
    useAuthStore().applyLoginResult({ token: 'new-workspace-login', nickname: '新账号', approvalStatus: 'APPROVED',
      forceChangePassword: false, approvalRemark: null }, 'new-workspace-user')
    return { chat: useChatConversationsStore().byAgent, vibe: useVibeConversationsStore().byAgent }
  })
  expect(remaining).toEqual({ chat: {}, vibe: {} })
})
