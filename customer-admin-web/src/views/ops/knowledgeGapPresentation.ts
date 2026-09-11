import type { KnowledgeGapCategory, KnowledgeGapView } from '@/api/ops'

export const GAP_CATEGORY_LABELS: Record<KnowledgeGapCategory, string> = {
  PENDING: '待分类',
  KNOWLEDGE: '知识不足',
  DEPENDENCY: '工具 / 依赖异常',
  PROCESS: '流程问题',
  REALTIME: '实时查询',
  NON_BUSINESS: '闲聊 / 非业务',
}
export const GAP_VIEW_OPTIONS: { value: KnowledgeGapView; label: string }[] = [
  { value: 'WORK', label: '工作清单' },
  { value: 'ALL', label: '全部原始信号' },
  ...Object.entries(GAP_CATEGORY_LABELS).map(([value, label]) => ({
    value: value as KnowledgeGapCategory,
    label,
  })),
]
export const GAP_CHANNEL_LABELS: Record<string, string> = {
  'user-ws': '客户实时会话',
  'user-http': '客户 HTTP',
  admin: '后台工作区',
  api: '开放 API',
  internal: '内部调用',
  a2a: '智能体协作',
}
