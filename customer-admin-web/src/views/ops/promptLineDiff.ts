export interface PromptDiffLine {
  text: string
  changed: boolean
}

export interface PromptLineDiff {
  left: PromptDiffLine[]
  right: PromptDiffLine[]
}

/**
 * 基于最长公共子序列生成行级差异。
 *
 * 同一份 LCS 对齐同时产出左右两侧结果，保证行顺序和重复次数都不会被集合去重破坏。
 * 多个最长子序列等价时固定优先消费左侧行，使结果在相同输入下保持稳定。
 */
export function diffPromptLines(leftText: string, rightText: string): PromptLineDiff {
  const leftLines = leftText.split('\n')
  const rightLines = rightText.split('\n')
  const lcsLengths = Array.from(
    { length: leftLines.length + 1 },
    () => new Uint32Array(rightLines.length + 1),
  )

  for (let leftIndex = leftLines.length - 1; leftIndex >= 0; leftIndex -= 1) {
    for (let rightIndex = rightLines.length - 1; rightIndex >= 0; rightIndex -= 1) {
      lcsLengths[leftIndex][rightIndex] = leftLines[leftIndex] === rightLines[rightIndex]
        ? lcsLengths[leftIndex + 1][rightIndex + 1] + 1
        : Math.max(lcsLengths[leftIndex + 1][rightIndex], lcsLengths[leftIndex][rightIndex + 1])
    }
  }

  const left: PromptDiffLine[] = []
  const right: PromptDiffLine[] = []
  let leftIndex = 0
  let rightIndex = 0

  while (leftIndex < leftLines.length && rightIndex < rightLines.length) {
    if (leftLines[leftIndex] === rightLines[rightIndex]) {
      left.push({ text: leftLines[leftIndex], changed: false })
      right.push({ text: rightLines[rightIndex], changed: false })
      leftIndex += 1
      rightIndex += 1
      continue
    }

    if (lcsLengths[leftIndex + 1][rightIndex] >= lcsLengths[leftIndex][rightIndex + 1]) {
      left.push({ text: leftLines[leftIndex], changed: true })
      leftIndex += 1
    } else {
      right.push({ text: rightLines[rightIndex], changed: true })
      rightIndex += 1
    }
  }

  while (leftIndex < leftLines.length) {
    left.push({ text: leftLines[leftIndex], changed: true })
    leftIndex += 1
  }
  while (rightIndex < rightLines.length) {
    right.push({ text: rightLines[rightIndex], changed: true })
    rightIndex += 1
  }

  return { left, right }
}
