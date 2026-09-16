<script setup lang="ts">
import { onMounted } from 'vue'
import { pageLogs } from '@/api/log'
import { usePagedList } from '@/composables/usePagedList'
import CrudLoadState from '@/components/CrudLoadState.vue'
import type { PageQuery, SysOperationLog } from '@/types/api'

const { loading, loadError, list, total, query, loadList, handleSearch } = usePagedList<SysOperationLog, PageQuery>({
  page: pageLogs,
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
})

onMounted(loadList)
</script>

<template>
  <div class="page">
    <CrudLoadState :error="loadError" :has-stale-data="list.length > 0" :loading="loading" @retry="loadList" />
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按操作人搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
      </div>

      <el-table v-if="!loadError || list.length > 0" v-loading="loading" :data="list" class="data-table" empty-text="暂无操作日志">
        <el-table-column prop="username" label="操作人" width="120" />
        <el-table-column prop="operation" label="操作内容" width="160" class-name="primary-column" />
        <el-table-column prop="target" label="操作对象" width="140" />
        <el-table-column prop="ip" label="IP" width="140" />
        <el-table-column label="结果" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.result === 1 ? 'success' : row.result === 2 ? 'warning' : 'danger'">
              {{ row.result === 1 ? '成功' : row.result === 2 ? '待确认' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="eventId" label="审计事件" width="150" show-overflow-tooltip />
        <el-table-column label="留存至" width="180">
          <template #default="{ row }">{{ row.retentionUntil || '-' }}</template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="错误信息" show-overflow-tooltip />
        <el-table-column prop="createTime" label="时间" width="180" />
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
