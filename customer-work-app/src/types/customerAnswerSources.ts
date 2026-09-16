/** 仅由服务端保存的检索来源生成，索引属于当前消息，不是知识库或文档 ID。 */
export interface CustomerAnswerSource {
  sourceIndex: number
  status: 'AVAILABLE' | 'UNAVAILABLE'
  title: string | null
  knowledgeBase: string | null
  versionNo: number | null
  sourceVersion: string | null
}

export interface CustomerAnswerSourcePreview extends CustomerAnswerSource {
  content: string | null
}
