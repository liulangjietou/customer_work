<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  createRateLimitRule,
  deleteRateLimitRule,
  fetchRateLimitAlgorithms,
  fetchRateLimitDimensions,
  pageRateLimitRules,
  toggleRateLimitRule,
  updateRateLimitRule,
} from '@/api/contentGuard'
import { useCrudPage } from '@/composables/useCrudPage'
import CrudLoadState from '@/components/CrudLoadState.vue'
import type { PageQuery, RateLimitRuleSaveRequest, RateLimitRuleVO } from '@/types/api'

const formRef = ref<FormInstance>()
const dimensions = ref<string[]>([])
const algorithms = ref<string[]>([])

const {
  loading, loadError, submitting, deletingId, list, total, query,
  dialogVisible, dialogMode, form,
  loadList, handleSearch, openCreate, openEdit, handleSubmit, handleDelete,
} = useCrudPage<RateLimitRuleVO, PageQuery, RateLimitRuleSaveRequest>({
  page: pageRateLimitRules,
  formRef,
  create: createRateLimitRule,
  update: updateRateLimitRule,
  remove: (row) => deleteRateLimitRule(row.id),
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  initForm: () => ({
    ruleName: '', pathPrefix: '/api/', dimension: 'API_KEY', limitCount: 60,
    algorithm: 'FIXED_WINDOW', windowSeconds: 60, priority: 10, enabled: true,
  }),
  toForm: (row) => ({
    ruleName: row.ruleName, pathPrefix: row.pathPrefix, dimension: row.dimension,
    limitCount: row.limitCount, algorithm: row.algorithm, windowSeconds: row.windowSeconds,
    priority: row.priority, enabled: row.enabled,
  }),
  deleteConfirm: (row) => `确认删除限流规则「${row.ruleName}」？删除后该路径将落回全局兜底限流。`,
})

const dimensionLabels: Record<string, string> = {
  API_KEY: '按 API Key', IP: '按来源 IP', GLOBAL: '整条路径共享',
}

const algorithmLabels: Record<string, string> = {
  FIXED_WINDOW: '固定窗口', SLIDING_WINDOW: '滑动窗口',
}

function formatTime(ms: number | null): string {
  return ms ? new Date(ms).toLocaleString('zh-CN') : '-'
}

async function handleToggle(row: RateLimitRuleVO) {
  await toggleRateLimitRule(row.id, !row.enabled)
  ElMessage.success(row.enabled ? '已停用' : '已启用')
  await loadList()
}

onMounted(async () => {
  await loadList()
  dimensions.value = await fetchRateLimitDimensions()
  algorithms.value = await fetchRateLimitAlgorithms()
})
</script>

<template>
  <div class="page">
    <CrudLoadState :error="loadError" :has-stale-data="list.length > 0" :loading="loading" @retry="loadList" />
    <el-alert type="warning" :closable="false" show-icon class="notice">
      规则按<b>优先级升序首匹配即止</b>（不叠加），都不命中才落到客服端 yml 里的全局兜底参数。
      规则仅在客服端开启 <code>customer-work.security.rate-limit.rule-enabled=true</code> 且
      <code>store-mode=jdbc</code> 时才被读取——这里能配，不等于一定在跑。
    </el-alert>

    <el-card>
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="按规则名或路径搜索"
          style="width: 220px"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-select v-model="query.status" placeholder="全部状态" style="width: 120px" clearable>
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <div class="toolbar-actions">
          <el-button v-permission="'rate-limit-rule:add'" class="cw-final-action" type="primary" @click="openCreate">新增规则</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="list" class="data-table" empty-text="暂无符合条件的限流规则">
        <el-table-column prop="priority" label="优先级" width="90" sortable />
        <el-table-column prop="ruleName" label="规则名" min-width="140" show-overflow-tooltip class-name="primary-column" />
        <el-table-column prop="pathPrefix" label="路径前缀" min-width="180" show-overflow-tooltip />
        <el-table-column label="计数维度" width="130">
          <template #default="{ row }">{{ dimensionLabels[row.dimension] ?? row.dimension }}</template>
        </el-table-column>
        <el-table-column label="阈值" width="150">
          <template #default="{ row }">{{ row.limitCount }} 次 / {{ row.windowSeconds }} 秒</template>
        </el-table-column>
        <el-table-column label="算法" width="110">
          <template #default="{ row }">{{ algorithmLabels[row.algorithm] ?? row.algorithm }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">{{ formatTime(row.updatedAtMs) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'rate-limit-rule:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'rate-limit-rule:edit'" link type="primary" @click="handleToggle(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button v-permission="'rate-limit-rule:delete'" link type="danger" :loading="deletingId === row.id" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        class="pagination"
        @current-change="loadList"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新增限流规则' : '编辑限流规则'" width="520px">
      <el-form ref="formRef" :model="form" label-width="110px">
        <el-form-item label="规则名" prop="ruleName" :rules="[{ required: true, message: '请输入规则名' }]">
          <el-input v-model="form.ruleName" placeholder="如 chat-strict" />
        </el-form-item>
        <el-form-item label="路径前缀" prop="pathPrefix" :rules="[{ required: true, message: '请输入路径前缀' }]">
          <el-input v-model="form.pathPrefix" placeholder="如 /api/customer/chat" />
          <div class="form-hint">前缀匹配：请求路径以此开头即命中本规则</div>
        </el-form-item>
        <el-form-item label="计数维度" prop="dimension" :rules="[{ required: true, message: '请选择计数维度' }]">
          <el-select v-model="form.dimension" style="width: 100%">
            <el-option
              v-for="item in dimensions"
              :key="item"
              :label="dimensionLabels[item] ?? item"
              :value="item"
            />
          </el-select>
          <div class="form-hint">决定"谁跟谁共享配额"：按 Key 各算各的 / 按 IP 防单机刷量 / 整条路径共享一份总配额</div>
        </el-form-item>
        <el-form-item label="阈值" prop="limitCount" :rules="[{ required: true, message: '请输入阈值' }]">
          <el-input-number v-model="form.limitCount" :min="1" :max="1000000" style="width: 100%" />
        </el-form-item>
        <el-form-item label="时间窗（秒）" prop="windowSeconds" :rules="[{ required: true, message: '请输入时间窗' }]">
          <el-input-number v-model="form.windowSeconds" :min="1" :max="3600" style="width: 100%" />
        </el-form-item>
        <el-form-item label="算法" prop="algorithm" :rules="[{ required: true, message: '请选择算法' }]">
          <el-select v-model="form.algorithm" style="width: 100%">
            <el-option
              v-for="item in algorithms"
              :key="item"
              :label="algorithmLabels[item] ?? item"
              :value="item"
            />
          </el-select>
          <div class="form-hint">固定窗口最省，窗口边界最坏放过 2 倍瞬时流量；滑动窗口无突刺，代价是多留一份时间戳队列</div>
        </el-form-item>
        <el-form-item label="优先级" prop="priority" :rules="[{ required: true, message: '请输入优先级' }]">
          <el-input-number v-model="form.priority" :min="0" :max="9999" style="width: 100%" />
          <div class="form-hint">越小越先匹配。把最严格、最具体的路径规则排在前面</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="primary" :loading="submitting" @click="handleSubmit">保存规则</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.notice {
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
  align-items: center;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.data-table {
  min-width: 0;
  max-width: 100%;
}

:deep(.primary-column .cell) {
  color: var(--cw-text);
  font-weight: 650;
}

.form-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

@media (max-width: 767px) {
  .toolbar-actions {
    margin-left: 0;
  }

  .toolbar-actions > .el-button {
    flex: 1 1 auto;
    margin-left: 0;
  }
}
</style>
