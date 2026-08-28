import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { describe, expect, it } from 'vitest'
import WorkspaceConversationEmptyState from './WorkspaceConversationEmptyState.vue'
import chatPanelSource from '@/views/workspace/ChatPanel.vue?raw'
import vibeCodingPanelSource from '@/views/workspace/VibeCodingPanel.vue?raw'

const PANEL_SOURCES = [
  ['ChatPanel', chatPanelSource],
  ['VibeCodingPanel', vibeCodingPanelSource],
] as const

async function renderEmptyState(assistantName = 'Java助手'): Promise<string> {
  return renderToString(createSSRApp(WorkspaceConversationEmptyState, { assistantName }))
}

/** 只取消息容器到输入区之间的模板，避免 VibeCoding 产物栏自身的 el-empty 干扰断言。 */
function messageRegion(source: string): string {
  const start = source.indexOf('ref="scrollRef"')
  const end = source.indexOf('<div class="composer-wrap">', start)
  expect(start).toBeGreaterThanOrEqual(0)
  expect(end).toBeGreaterThan(start)
  return source.slice(start, end)
}

describe('WorkspaceConversationEmptyState', () => {
  it('统一展示精确的任务起点与通用任务文案', async () => {
    const html = await renderEmptyState()

    expect(html).toContain('>从一个任务开始</h2>')
    expect(html).toContain('描述目标，或直接补充资料、上下文和约束。')
    expect(html).toContain('例如：梳理需求 · 定位问题 · 完成一个可验证的结果')
    expect(html).not.toContain('从一个 Java 任务开始')
    expect(html).not.toContain('重构长方法')
    expect(html).not.toContain('补充单元测试')
  })

  it('动态展示当前助手名称，技术标识不写死 Java', async () => {
    const html = await renderEmptyState('OA考勤助手')

    expect(html).toContain('AGENT WORKBENCH / READY')
    expect(html).toContain('OA考勤助手 · READY')
    expect(html).not.toContain('Java助手')
    expect(html).not.toContain('JAVA WORKBENCH')
    expect(html).not.toContain('JAVA ASSISTANT')
  })

  it('让技术装饰层退出无障碍树', async () => {
    const html = await renderEmptyState()

    expect(html).toContain('aria-hidden="true"')
    expect(html).toContain('context.read();')
    expect(html).toContain('intent.resolve();')
    expect(html).toContain('answer.verify();')
  })
})

describe('工作区 Panel 空态接线契约', () => {
  it.each(PANEL_SOURCES)('%s 接入共享空态并透传当前助手名称', (_label, source) => {
    const messages = messageRegion(source)

    expect(messages).toContain('<WorkspaceConversationEmptyState')
    expect(messages).toContain(':assistant-name="assistantName"')
    expect(messages).toContain("'is-empty'")
    expect(messages).not.toContain('<el-empty')
    expect(messages).toContain('class="message-row"')
    expect(source).not.toContain('开始和智能体对话吧')
    expect(source).not.toContain('描述你想让智能体生成/修改的代码')
  })
})
