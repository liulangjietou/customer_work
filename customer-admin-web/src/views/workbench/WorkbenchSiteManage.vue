<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  createWorkbenchSite,
  deleteWorkbenchSite,
  getWorkbenchSiteSecret,
  pageWorkbenchSites,
  updateWorkbenchSite,
} from '@/api/workbench'
import { useCrudPage } from '@/composables/useCrudPage'
import { copyText } from '@/views/system/devtools/composables/useCopy'
import type { PageQuery, WorkbenchSiteSaveRequest, WorkbenchSiteVO } from '@/types/api'

// url 校验正则与后端 @Pattern 保持一致
const URL_PATTERN = /^https?:\/\/.+/

const formRef = ref<FormInstance>()
const secretLoadingId = ref<number | null>(null)

const {
  loading, list, total, query,
  dialogVisible, dialogMode, form,
  loadList, handleSearch, openCreate, openEdit, handleSubmit, handleDelete,
} = useCrudPage<WorkbenchSiteVO, PageQuery, WorkbenchSiteSaveRequest>({
  page: pageWorkbenchSites,
  formRef,
  create: createWorkbenchSite,
  update: updateWorkbenchSite,
  remove: (row) => deleteWorkbenchSite(row.id),
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  initForm: () => ({ name: '', category: '', url: '', account: '', password: '', remark: '', enabled: true }),
  toForm: (row) => ({
    name: row.name, category: row.category, url: row.url, account: row.account, password: '',
    remark: row.remark, enabled: row.enabled,
  }),
  deleteConfirm: (row) => `确认删除站点「${row.name}」？`,
})

/** 在新标签页打开站点地址（noopener 防止被打开页反向操纵本页）。 */
function openSite(row: WorkbenchSiteVO) {
  window.open(row.url, '_blank', 'noopener')
}

/** 复制明文密码：先向后端换取解密后的明文（敏感读接口），再写入剪贴板。 */
async function copySecret(row: WorkbenchSiteVO) {
  secretLoadingId.value = row.id
  try {
    const secret = await getWorkbenchSiteSecret(row.id)
    await copyText(secret, '密码')
  } catch {
    // 错误提示已由 request.ts 拦截器统一弹出，这里不重复弹
  } finally {
    secretLoadingId.value = null
  }
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按名称/分类搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'workbench-site:add'" type="primary" @click="openCreate">新增站点</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="name" label="名称" width="160" show-overflow-tooltip />
        <el-table-column label="分类" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.category" type="info">{{ row.category }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="地址" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" @click="openSite(row)">{{ row.url }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="账号" width="180">
          <template #default="{ row }">
            <span>{{ row.account || '-' }}</span>
            <el-button v-if="row.account" link type="primary" @click="copyText(row.account, '账号')">复制</el-button>
          </template>
        </el-table-column>
        <el-table-column label="密码" width="170">
          <template #default="{ row }">
            <span>{{ row.hasPassword ? row.passwordMasked : '-' }}</span>
            <el-button
              link
              type="primary"
              :disabled="!row.hasPassword"
              :loading="secretLoadingId === row.id"
              @click="copySecret(row)"
            >
              复制密码
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openSite(row)">打开</el-button>
            <el-button v-permission="'workbench-site:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'workbench-site:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
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
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新增站点' : '编辑站点'" width="560px">
      <el-form ref="formRef" :model="form" label-width="80px">
        <el-form-item label="名称" prop="name" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category!" placeholder="如 git / jenkins / oa" />
        </el-form-item>
        <el-form-item
          label="地址"
          prop="url"
          :rules="[
            { required: true, message: '请输入访问地址' },
            { pattern: URL_PATTERN, message: 'url 必须以 http:// 或 https:// 开头' },
          ]"
        >
          <el-input v-model="form.url" placeholder="如 https://git.internal" />
        </el-form-item>
        <el-form-item label="账号">
          <el-input v-model="form.account!" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password!" type="password" show-password :placeholder="dialogMode === 'edit' ? '留空则不修改' : '可留空'" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark!" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
</style>
