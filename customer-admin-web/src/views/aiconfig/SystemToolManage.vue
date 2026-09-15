<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import { fetchSystemTools, updateSystemTool } from '@/api/system-tool'
import { useCrudPage } from '@/composables/useCrudPage'
import { useRowMutation } from '@/composables/useRowMutation'
import { useAuthStore } from '@/store/auth'
import CrudLoadState from '@/components/CrudLoadState.vue'
import type { PageQuery, SystemToolSaveRequest, SystemToolVO } from '@/types/api'

const formRef = ref<FormInstance>()
const auth = useAuthStore()
const { isPending: isToggling, run: toggleRow } = useRowMutation('system-tool:edit')

// 系统工具只有"编辑"能力（无新建/删除），只接 page/update 半套
const {
  loading, loadError, submitting, list, total, query,
  dialogVisible, form,
  loadList, handleSearch, openEdit, handleSubmit,
} = useCrudPage<SystemToolVO, PageQuery, SystemToolSaveRequest>({
  page: fetchSystemTools,
  formRef,
  update: updateSystemTool,
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  initForm: () => ({ toolName: '', description: '', enabled: 1, remark: '' }),
  toForm: (row) => ({
    toolName: row.toolName, description: row.description ?? '', enabled: row.enabled, remark: row.remark ?? '',
  }),
})

// 现有 PUT 契约要求完整字段；固定此次目标状态，进行中禁止同一行再次写入。
async function handleToggleEnabled(row: SystemToolVO) {
  const enabled = row.enabled
  const previous = enabled === 1 ? 0 : 1
  await toggleRow(row.id, () => updateSystemTool(row.id, {
    toolName: row.toolName, description: row.description, enabled, remark: row.remark,
  }), () => {
    ElMessage.success(enabled === 1 ? '已启用' : '已停用')
  }, () => { row.enabled = previous })
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <CrudLoadState :error="loadError" :has-stale-data="list.length > 0" :loading="loading" @retry="loadList" />
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按名称搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
      </div>

      <el-table v-if="!loadError || list.length > 0" v-loading="loading" :data="list" class="data-table" empty-text="暂无系统工具">
        <el-table-column prop="toolCode" label="编码" width="160" />
        <el-table-column prop="toolName" label="名称" width="180" class-name="primary-column" />
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-switch
              v-if="auth.hasPermission('system-tool:edit')"
              v-model="row.enabled"
              :active-value="1"
              :inactive-value="0"
              :disabled="isToggling(row.id)"
              :loading="isToggling(row.id)"
              :aria-label="`${row.toolName}启用状态`"
              @change="handleToggleEnabled(row)"
            />
            <el-tag v-else :type="row.enabled === 1 ? 'success' : 'info'">{{ row.enabled === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="180" />
        <el-table-column v-if="auth.hasPermission('system-tool:edit')" label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'system-tool:edit'" link type="primary" :disabled="isToggling(row.id)" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="!loadError || list.length > 0"
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        class="pagination"
        @current-change="loadList"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" title="编辑系统工具" width="520px">
      <el-form ref="formRef" :model="form" label-width="100px">
        <el-form-item label="名称" prop="toolName" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="form.toolName" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description!" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark!" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled!" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="primary" :loading="submitting" @click="handleSubmit">保存工具</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.data-table {
  min-width: 0;
  max-width: 100%;
}

:deep(.primary-column .cell) {
  color: var(--cw-text);
  font-weight: 650;
}
</style>
