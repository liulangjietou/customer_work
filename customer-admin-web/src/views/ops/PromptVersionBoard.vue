<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listPromptVersions, type PromptVersion } from '@/api/ops'

// 提示词版本看板：版本历史 + 两版全文比对。
//
// 与 B4 的「配置版本」是两回事：那个记"发布下发了什么"，这个记"运行时实际生效的是什么"。
// 灰度未覆盖、推送未到达、有人直接改了 Nacos 没走发布流程，都会让两者不一致——
// 而能跟评测指标对上号的只有后者。

const loading = ref(false)
const list = ref<PromptVersion[]>([])
const selected = ref<PromptVersion[]>([])

async function loadList() {
  loading.value = true
  try {
    list.value = await listPromptVersions()
  } finally {
    loading.value = false
  }
}

function formatTime(ms: number): string {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function handleSelectionChange(rows: PromptVersion[]) {
  selected.value = rows
}

// ---------- 两版对比 ----------

const diffVisible = ref(false)
const leftVersion = ref<PromptVersion | null>(null)
const rightVersion = ref<PromptVersion | null>(null)

function openDiff() {
  if (selected.value.length !== 2) {
    ElMessage.warning('请勾选两个版本进行对比')
    return
  }
  const [a, b] = selected.value
  // 早的放左边，读起来才是"从旧到新"
  const ordered = a.capturedAtMs <= b.capturedAtMs ? [a, b] : [b, a]
  leftVersion.value = ordered[0]
  rightVersion.value = ordered[1]
  diffVisible.value = true
}

/** 逐行差异标记：提示词是纯文本，行级比对足以看出改了哪几句。 */
function diffLines(source: string, other: string) {
  const otherLines = new Set(other.split('\n'))
  return source.split('\n').map((line) => ({ text: line, changed: !otherLines.has(line) }))
}

const leftLines = computed(() =>
  leftVersion.value && rightVersion.value
    ? diffLines(leftVersion.value.content, rightVersion.value.content)
    : [],
)

const rightLines = computed(() =>
  leftVersion.value && rightVersion.value
    ? diffLines(rightVersion.value.content, leftVersion.value.content)
    : [],
)

onMounted(loadList)
</script>

<template>
  <div class="prompt-version-board">
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="这里记的是「运行时实际生效」的提示词，不是「发布下发了什么」"
      description="版本号取内容指纹（SHA-256 前 16 位）——内容变了指纹必变，跨环境稳定。
        评测报告里的 promptFingerprint 就是它：指标掉了先比这一位，没变就别再对着提示词逐字找原因。"
    />

    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" :loading="loading" @click="loadList">刷新</el-button>
        <el-button :disabled="selected.length !== 2" @click="openDiff">对比选中两版</el-button>
        <span class="hint">勾选两版即可比对全文差异</span>
      </div>

      <el-table
        v-loading="loading"
        :data="list"
        style="width: 100%"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column label="指纹" width="180">
          <template #default="{ row }">
            <el-tag type="info" effect="plain">{{ row.fingerprint }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上线时间" width="180">
          <template #default="{ row }">{{ formatTime(row.capturedAtMs) }}</template>
        </el-table-column>
        <el-table-column label="字数" width="100">
          <template #default="{ row }">{{ row.length }}</template>
        </el-table-column>
        <el-table-column prop="content" label="内容预览" show-overflow-tooltip />
      </el-table>

      <el-empty
        v-if="!loading && list.length === 0"
        description="暂无版本记录（需开启 prompt-version.store-mode=jdbc，且至少跑过一次评测）"
      />
    </el-card>

    <el-drawer v-model="diffVisible" title="提示词版本对比" size="80%">
      <div v-if="leftVersion && rightVersion" class="diff">
        <div class="diff-side">
          <div class="diff-title">
            旧版 <el-tag size="small" type="info">{{ leftVersion.fingerprint }}</el-tag>
            <span class="hint">{{ formatTime(leftVersion.capturedAtMs) }}</span>
          </div>
          <pre class="diff-body"><span
            v-for="(line, index) in leftLines"
            :key="index"
            :class="{ 'line-removed': line.changed }"
          >{{ line.text }}
</span></pre>
        </div>
        <div class="diff-side">
          <div class="diff-title">
            新版 <el-tag size="small">{{ rightVersion.fingerprint }}</el-tag>
            <span class="hint">{{ formatTime(rightVersion.capturedAtMs) }}</span>
          </div>
          <pre class="diff-body"><span
            v-for="(line, index) in rightLines"
            :key="index"
            :class="{ 'line-added': line.changed }"
          >{{ line.text }}
</span></pre>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.prompt-version-board {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.diff {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.diff-title {
  font-weight: 600;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.diff-body {
  margin: 0;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 70vh;
  overflow: auto;
}

.line-removed {
  background: var(--el-color-danger-light-8);
  display: inline-block;
  width: 100%;
}

.line-added {
  background: var(--el-color-success-light-8);
  display: inline-block;
  width: 100%;
}
</style>
