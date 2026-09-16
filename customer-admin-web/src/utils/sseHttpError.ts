/**
 * 带业务码的流式请求错误。
 *
 * <p>调用方据此区分"这一轮为什么失败"：额度用尽（{@link #QUOTA_EXCEEDED}）该作为一条消息
 * 留在对话流里，让用户看到它紧跟在自己那句话后面；而网络中断之类的才适合一闪而过的顶部提示。</p>
 */
export class SseHttpError extends Error {
  /** 额度用尽（后端 ResultCode.QUOTA_EXCEEDED）。 */
  static readonly QUOTA_EXCEEDED = 40043

  readonly status: number
  readonly code?: number

  constructor(message: string, status: number, code?: number) {
    super(message)
    this.name = 'SseHttpError'
    this.status = status
    this.code = code
  }

  get quotaExceeded(): boolean {
    return this.code === SseHttpError.QUOTA_EXCEEDED
  }
}
