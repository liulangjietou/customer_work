<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getVersion,
  grayRelease,
  pageVersions,
  rollbackVersion,
  type ConfigVersionVO,
} from '@/api/config-version'
import { listTenantOptions, type TenantVO } from '@/api/tenant'

// 配置版本：看历史、比两版差异、安全回滚、按租户安全灰度。
// 历史只提供提示词/maxIters 补丁；目标模型、凭据、MCP、路由和实验始终取当前权威配置。

const STATUS_LABELS: Record<string, { text: string; type: 'success' | 'info' | 'danger' | 'warning' }> = {
  PUBLISHED: { text: '已投递，待实例确认', type: 'warning' },
  SUPERSEDED: { text: '已有后续投递', type: 'info' },
  FAILED: { text: '发布失败', type: 'danger' },
}

const loading = ref(false)
const list = ref<ConfigVersionVO[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10, configType: '', targetCode: '' })
const tenants = ref<TenantVO[]>([])

async function loadList() {
  loading.value = true
  try {
    const data = await pageVersions(query)
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  return loadList()
}

// ---------- 版本对比 ----------

const diffVisible = ref(false)
const leftVersion = ref<ConfigVersionVO | null>(null)
const rightVersion = ref<ConfigVersionVO | null>(null)
const selected = ref<ConfigVersionVO[]>([])

function handleSelectionChange(rows: ConfigVersionVO[]) {
  selected.value = rows
}

async function openDiff() {
  if (selected.value.length !== 2) {
    ElMessage.warning('请勾选两个版本进行对比')
    return
  }
  // 列表不返回 content；对比时按需拉取服务端结构化脱敏后的快照。
  const [a, b] = selected.value
  const [left, right] = await Promise.all([getVersion(a.id), getVersion(b.id)])
  // 版本号小的放左边，读起来才是"从旧到新"
  const ordered = left.version <= right.version ? [left, right] : [right, left]
  leftVersion.value = ordered[0]
  rightVersion.value = ordered[1]
  diffVisible.value = true
}

/** 简易逐行差异标记：内容是 JSON，行级比对足以看出改了哪个字段。 */
function diffLines(a: string | null, b: string | null) {
  const left = (a ?? '').split('\n')
  const right = (b ?? '').split('\n')
  const max = Math.max(left.length, right.length)
  const rows: { no: number; left: string; right: string; changed: boolean }[] = []
  for (let i = 0; i < max; i++) {
    const l = left[i] ?? ''
    const r = right[i] ?? ''
    rows.push({ no: i + 1, left: l, right: r, changed: l !== r })
  }
  return rows
}

const diffRows = computed(() => diffLines(leftVersion.value?.content ?? '', rightVersion.value?.content ?? ''))
const changedCount = computed(() => diffRows.value.filter((r) => r.changed).length)

// ---------- 回滚 ----------

async function handleRollback(row: ConfigVersionVO) {
  const { value } = await ElMessageBox.prompt(
    `将只提取 v${row.version} 的提示词和最大迭代次数；模型、凭据、MCP、路由与实验使用当前配置。请填写回滚原因：`,
    `回滚到 v${row.version}`,
    { inputPlaceholder: '如：v5 的提示词导致答非所问', confirmButtonText: '创建安全回滚任务', type: 'warning' },
  )
  const operation = await rollbackVersion(row.id, value)
  ElMessage.success(
    `安全回滚任务已入队（${operation.tasks.length} 个，${operation.status}），实例 ACK APPLIED 后生效`,
  )
  await loadList()
}

// ---------- 灰度发布 ----------

const grayVisible = ref(false)
const grayTarget = ref<ConfigVersionVO | null>(null)
const grayForm = reactive<{ tenantCodes: string[]; remark: string }>({ tenantCodes: [], remark: '' })

function openGray(row: ConfigVersionVO) {
  grayTarget.value = row
  grayForm.tenantCodes = []
  grayForm.remark = ''
  grayVisible.value = true
}

async function submitGray() {
  if (!grayTarget.value) return
  if (grayForm.tenantCodes.length === 0) {
    ElMessage.warning('请至少选择一个租户')
    return
  }
  const operation = await grayRelease(grayTarget.value.id, grayForm.tenantCodes, grayForm.remark)
  ElMessage.success(
    `安全灰度任务已整批入队（${operation.tasks.length} 个，${operation.status}），实例 ACK APPLIED 后生效`,
  )
  grayVisible.value = false
  await loadList()
}

function formatGrayTenants(raw: string | null) {
  if (!raw) return '-'
  try {
    return (JSON.parse(raw) as string[]).join('、')
  } catch {
    return raw
  }
}

onMounted(async () => {
  await Promise.all([loadList(), listTenantOptions().then((t) => (tenants.value = t))])
})
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input
          v-model="query.targetCode"
          placeholder="按目标编码搜索（如 agentCode）"
          style="width: 260px"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-select v-model="query.configType" placeholder="全部类型" clearable style="width: 140px">
          <el-option label="智能体" value="AGENT" />
          <el-option label="模型" value="MODEL" />
        </el-select>
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button :disabled="selected.length !== 2" @click="openDiff">对比选中两版</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="list"
        style="width: 100%"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column prop="targetCode" label="目标" width="180" />
        <el-table-column prop="configType" label="类型" width="100" />
        <el-table-column label="版本" width="90">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="STATUS_LABELS[row.status]?.type ?? 'info'">
              {{ STATUS_LABELS[row.status]?.text ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="范围" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.publishScope === 'GRAY'" type="warning">
              灰度：{{ formatGrayTenants(row.grayTenants) }}
            </el-tag>
            <span v-else>全量</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="110">
          <template #default="{ row }">
            <span v-if="row.sourceVersion">回滚自 v{{ row.sourceVersion }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="说明" show-overflow-tooltip />
        <el-table-column prop="createTime" label="发布时间" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              v-permission="'config-version:rollback'"
              link
              type="primary"
              :disabled="row.status === 'FAILED'"
              @click="handleRollback(row)"
            >
              回滚至此
            </el-button>
            <el-button
              v-permission="'config-version:gray'"
              link
              type="warning"
              :disabled="row.status === 'FAILED'"
              @click="openGray(row)"
            >
              灰度
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="loadList"
      />

      <div class="tip">
        安全回滚不会重放历史模型密文、MCP 请求头、路由或实验盐，只回退提示词和最大迭代次数。
        操作先进入可靠任务并经过评测门禁；“已投递”不代表已生效，只有实例 ACK APPLIED 才算完成。
      </div>
    </el-card>

    <el-dialog v-model="diffVisible" title="版本对比" width="90%" top="5vh">
      <div class="diff-head">
        <span>左：v{{ leftVersion?.version }}（{{ leftVersion?.createTime }}）</span>
        <span>右：v{{ rightVersion?.version }}（{{ rightVersion?.createTime }}）</span>
        <el-tag :type="changedCount ? 'warning' : 'success'">
          {{ changedCount ? `${changedCount} 行有差异` : '两版内容一致' }}
        </el-tag>
      </div>
      <div class="diff-body">
        <div v-for="row in diffRows" :key="row.no" class="diff-row" :class="{ changed: row.changed }">
          <span class="diff-no">{{ row.no }}</span>
          <pre class="diff-cell">{{ row.left }}</pre>
          <pre class="diff-cell">{{ row.right }}</pre>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="grayVisible" :title="`灰度发布 v${grayTarget?.version ?? ''}`" width="560px">
      <el-form label-width="100px">
        <el-form-item label="目标租户">
          <el-select v-model="grayForm.tenantCodes" multiple filterable style="width: 100%">
            <el-option
              v-for="t in tenants"
              :key="t.tenantCode"
              :label="`${t.tenantName}（${t.tenantCode}）`"
              :value="t.tenantCode"
            />
          </el-select>
          <div class="form-tip">
            每个租户都会使用自己的当前模型与凭据重组候选。任一租户预校验失败时整批不创建任务。
          </div>
        </el-form-item>
        <el-form-item label="发布说明">
          <el-input v-model="grayForm.remark" type="textarea" placeholder="建议写清灰度目的，事后翻历史时最有用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="grayVisible = false">取消</el-button>
        <el-button type="primary" @click="submitGray">创建安全灰度任务</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.tip {
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}

.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.diff-head {
  display: flex;
  gap: 24px;
  align-items: center;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.diff-body {
  max-height: 65vh;
  overflow: auto;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
}

.diff-row {
  display: grid;
  grid-template-columns: 56px 1fr 1fr;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.diff-row.changed {
  background: var(--el-color-warning-light-9);
}

.diff-no {
  padding: 2px 8px;
  color: var(--el-text-color-placeholder);
  text-align: right;
  font-size: 12px;
  border-right: 1px solid var(--el-border-color-lighter);
}

.diff-cell {
  margin: 0;
  padding: 2px 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  border-right: 1px solid var(--el-border-color-lighter);
}
</style>
