import { describe, expect, it } from 'vitest'
import { parseSseBlock } from './sseParser'

describe('parseSseBlock', () => {
  it('只去掉 SSE 协议分隔空格，保留正文首尾空格和多行结构', () => {
    expect(parseSseBlock('event: message\ndata:  leading space\ndata: trailing space ')).toEqual({
      event: 'message',
      data: ' leading space\ntrailing space ',
    })
  })

  it('兼容 CRLF 行尾且不吞掉思考增量空格', () => {
    expect(parseSseBlock('event: node:thinking\r\ndata:  分析调用链 \r')).toEqual({
      event: 'node:thinking',
      data: ' 分析调用链 ',
    })
  })

  it('没有 data 字段时忽略该事件块', () => {
    expect(parseSseBlock('event: done')).toBeNull()
  })
})
