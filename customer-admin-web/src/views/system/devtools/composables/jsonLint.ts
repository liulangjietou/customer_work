/**
 * 极简 JSON 语法扫描器：只用于 JSON.parse 失败后定位错误的行/列与具体原因。
 *
 * 为什么不直接解析 JSON.parse 抛出的 error.message 拿位置：不同 JS 引擎/版本的报错文案差异很大，
 * 较新 V8 部分错误会带 "at position N (line X column Y)"，但另一部分（如 token 类错误）只给一段
 * 上下文摘要、完全不带数字位置，无法可靠跨浏览器解析。这里牺牲一点性能（只在报错分支才跑一遍
 * 手写扫描器）换取行列定位在任意浏览器上都稳定准确——合法 JSON 的主链路仍然用原生 JSON.parse，
 * 不受影响。
 */
export interface JsonSyntaxError {
  line: number
  col: number
  message: string
}

class ScanFailure {
  line: number
  col: number
  message: string

  constructor(line: number, col: number, message: string) {
    this.line = line
    this.col = col
    this.message = message
  }
}

class JsonScanner {
  private readonly text: string
  private i = 0
  private line = 1
  private col = 1

  constructor(text: string) {
    this.text = text
  }

  private fail(message: string): never {
    throw new ScanFailure(this.line, this.col, message)
  }

  private peek(): string | undefined {
    return this.text[this.i]
  }

  private advance(): string {
    const ch = this.text[this.i]
    this.i += 1
    if (ch === '\n') {
      this.line += 1
      this.col = 1
    } else {
      this.col += 1
    }
    return ch
  }

  private eof(): boolean {
    return this.i >= this.text.length
  }

  private skipWhitespace() {
    while (!this.eof() && /[ \t\n\r]/.test(this.peek() as string)) {
      this.advance()
    }
  }

  scan() {
    this.skipWhitespace()
    this.parseValue()
    this.skipWhitespace()
    if (!this.eof()) {
      this.fail(`结尾存在多余内容："${this.text.slice(this.i, this.i + 10)}"`)
    }
  }

  private parseValue() {
    this.skipWhitespace()
    if (this.eof()) {
      this.fail('内容为空或提前结束，此处应有一个值')
    }
    const ch = this.peek() as string
    if (ch === '{') return this.parseObject()
    if (ch === '[') return this.parseArray()
    if (ch === '"') return this.parseString()
    if (ch === '-' || (ch >= '0' && ch <= '9')) return this.parseNumber()
    if (this.text.startsWith('true', this.i)) return this.parseLiteral('true')
    if (this.text.startsWith('false', this.i)) return this.parseLiteral('false')
    if (this.text.startsWith('null', this.i)) return this.parseLiteral('null')
    this.fail(`意外字符 "${ch}"，此处应为值（对象/数组/字符串/数字/true/false/null 之一）`)
  }

  private parseLiteral(word: string) {
    for (let k = 0; k < word.length; k += 1) {
      this.advance()
    }
  }

  private parseObject() {
    this.advance() // {
    this.skipWhitespace()
    if (this.peek() === '}') {
      this.advance()
      return
    }
    for (;;) {
      this.skipWhitespace()
      if (this.peek() !== '"') {
        this.fail(`对象的键必须是双引号包裹的字符串，实际遇到 "${this.peek() ?? '文件结尾'}"`)
      }
      this.parseString()
      this.skipWhitespace()
      if (this.peek() !== ':') {
        this.fail(`键后面缺少冒号 ":"，实际遇到 "${this.peek() ?? '文件结尾'}"`)
      }
      this.advance() // :
      this.parseValue()
      this.skipWhitespace()
      const ch = this.peek()
      if (ch === ',') {
        this.advance()
        this.skipWhitespace()
        if (this.peek() === '}') {
          this.fail('多余的逗号：对象最后一个属性后不能再跟逗号')
        }
        continue
      }
      if (ch === '}') {
        this.advance()
        return
      }
      this.fail(`对象缺少逗号 "," 或右花括号 "}"，实际遇到 "${ch ?? '文件结尾'}"`)
    }
  }

  private parseArray() {
    this.advance() // [
    this.skipWhitespace()
    if (this.peek() === ']') {
      this.advance()
      return
    }
    for (;;) {
      this.parseValue()
      this.skipWhitespace()
      const ch = this.peek()
      if (ch === ',') {
        this.advance()
        this.skipWhitespace()
        if (this.peek() === ']') {
          this.fail('多余的逗号：数组最后一个元素后不能再跟逗号')
        }
        continue
      }
      if (ch === ']') {
        this.advance()
        return
      }
      this.fail(`数组缺少逗号 "," 或右方括号 "]"，实际遇到 "${ch ?? '文件结尾'}"`)
    }
  }

  private parseString() {
    const startLine = this.line
    const startCol = this.col
    this.advance() // 开引号
    for (;;) {
      if (this.eof()) {
        this.line = startLine
        this.col = startCol
        this.fail('字符串缺少结束的双引号')
      }
      const ch = this.advance()
      if (ch === '"') return
      if (ch === '\\') {
        if (this.eof()) {
          this.fail('字符串末尾的转义符 "\\" 不完整')
        }
        const esc = this.advance()
        if (esc === 'u') {
          for (let k = 0; k < 4; k += 1) {
            if (this.eof() || !/[0-9a-fA-F]/.test(this.peek() as string)) {
              this.fail('\\u 转义后必须紧跟 4 位十六进制数字')
            }
            this.advance()
          }
        } else if (!'"\\/bfnrt'.includes(esc)) {
          this.fail(`非法的转义字符 "\\${esc}"`)
        }
      } else if (ch.charCodeAt(0) < 0x20) {
        this.fail('字符串中包含未转义的控制字符（如换行），需要用 \\n 等转义表示')
      }
    }
  }

  private parseNumber() {
    const start = this.i
    if (this.peek() === '-') this.advance()
    if (this.peek() === '0') {
      this.advance()
    } else if ((this.peek() ?? '') >= '1' && (this.peek() ?? '') <= '9') {
      while (!this.eof() && /[0-9]/.test(this.peek() as string)) this.advance()
    } else {
      this.fail('数字格式非法（负号后必须紧跟数字，且不能有多余前导零）')
    }
    if (this.peek() === '.') {
      this.advance()
      if (!/[0-9]/.test(this.peek() ?? '')) this.fail('小数点后必须至少有一位数字')
      while (!this.eof() && /[0-9]/.test(this.peek() as string)) this.advance()
    }
    if (this.peek() === 'e' || this.peek() === 'E') {
      this.advance()
      if (this.peek() === '+' || this.peek() === '-') this.advance()
      if (!/[0-9]/.test(this.peek() ?? '')) this.fail('指数部分必须至少有一位数字')
      while (!this.eof() && /[0-9]/.test(this.peek() as string)) this.advance()
    }
    if (this.i === start) this.fail('数字格式非法')
  }
}

/** 定位 JSON 文本中第一处语法错误；文本合法时返回 null。 */
export function locateJsonError(text: string): JsonSyntaxError | null {
  try {
    new JsonScanner(text).scan()
    return null
  } catch (e) {
    if (e instanceof ScanFailure) {
      return { line: e.line, col: e.col, message: e.message }
    }
    return { line: 1, col: 1, message: e instanceof Error ? e.message : String(e) }
  }
}

/** 取文本中某一行的原始内容，用于错误提示旁的定位预览（行号从 1 开始）。 */
export function getLineContent(text: string, line: number): string {
  const lines = text.split('\n')
  return lines[line - 1] ?? ''
}

/**
 * 把 (line, col) 换算成文本中的绝对字符下标（从 0 开始），用于在 textarea 里 setSelectionRange
 * 定位错误位置。col 从 1 开始计数，与扫描器保持一致。
 */
export function lineColToIndex(text: string, line: number, col: number): number {
  const lines = text.split('\n')
  let index = 0
  for (let i = 0; i < line - 1 && i < lines.length; i += 1) {
    index += lines[i].length + 1 // +1 是被 split 吃掉的换行符
  }
  return index + (col - 1)
}
