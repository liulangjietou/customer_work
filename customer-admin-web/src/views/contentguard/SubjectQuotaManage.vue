<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  assignAdminUserLevel,
  assignUserLevel,
  deleteSubjectQuotaLevel,
  fetchSubjectQuotaHitRank,
  fetchSubjectQuotaHits,
  fetchSubjectQuotaLevels,
  pageAdminQuotaUsers,
  pageSubjectQuotaUsers,
  saveSubjectQuotaLevel,
} from '@/api/subjectQuota'
import { useAuthStore } from '@/store/auth'
import type {
  AdminQuotaUserVO,
  PageQuery,
  SubjectQuotaHitRank,
  SubjectQuotaHitVO,
  SubjectQuotaLevelSaveRequest,
  SubjectQuotaLevelVO,
  SubjectQuotaUserVO,
} from '@/types/api'

const auth = useAuthStore()
const activeTab = ref('levels')

// ---------- 等级 ----------
const levelLoading = ref(false)
const levels = ref<SubjectQuotaLevelVO[]>([])
const levelDialogVisible = ref(false)
const levelDialogMode = ref<'create' | 'edit'>('create')
const levelFormRef = ref<FormInstance>()
const levelForm = reactive<SubjectQuotaLevelSaveRequest>(initLevelForm())

function initLevelForm(): SubjectQuotaLevelSaveRequest {
  return {
    levelCode: '', levelName: '', subjectType: 'USER', windowSeconds: 1800,
    tokenLimit: 50000, requestLimit: 100, exceedAction: 'BLOCK', enabled: true, remark: '',
  }
}

const levelRules = {
  levelCode: [{ required: true, message: '请填写等级编码', trigger: 'blur' }],
  levelName: [{ required: true, message: '请填写等级名称', trigger: 'blur' }],
  windowSeconds: [{ required: true, message: '请填写窗口长度', trigger: 'blur' }],
}

async function loadLevels() {
  levelLoading.value = true
  try {
    levels.value = await fetchSubjectQuotaLevels()
  } finally {
    levelLoading.value = false
  }
}

function openLevelCreate() {
  levelDialogMode.value = 'create'
  Object.assign(levelForm, initLevelForm())
  levelDialogVisible.value = true
}

function openLevelEdit(row: SubjectQuotaLevelVO) {
  levelDialogMode.value = 'edit'
  Object.assign(levelForm, {
    levelCode: row.levelCode, levelName: row.levelName, subjectType: row.subjectType,
    windowSeconds: row.windowSeconds, tokenLimit: row.tokenLimit, requestLimit: row.requestLimit,
    exceedAction: row.exceedAction, enabled: row.enabled, remark: row.remark ?? '',
  })
  levelDialogVisible.value = true
}

async function submitLevel() {
  if (!levelFormRef.value) return
  await levelFormRef.value.validate()
  await saveSubjectQuotaLevel({ ...levelForm })
  ElMessage.success('已保存，客服端最长 60 秒后生效')
  levelDialogVisible.value = false
  await loadLevels()
}

async function removeLevel(row: SubjectQuotaLevelVO) {
  await ElMessageBox.confirm(
    `确认删除等级「${row.levelName}」？删除后挂在该档的用户会落回配置里的默认档。`,
    '提示', { type: 'warning' },
  )
  await deleteSubjectQuotaLevel(row.levelCode)
  ElMessage.success('已删除')
  await loadLevels()
}

// ---------- 用户分档 ----------
const userLoading = ref(false)
const users = ref<SubjectQuotaUserVO[]>([])
const userTotal = ref(0)
const userQuery = reactive<PageQuery>({ pageNum: 1, pageSize: 10, keyword: '' })

async function loadUsers() {
  userLoading.value = true
  try {
    const page = await pageSubjectQuotaUsers(userQuery)
    users.value = page.list
    userTotal.value = page.total
  } finally {
    userLoading.value = false
  }
}

function searchUsers() {
  userQuery.pageNum = 1
  return loadUsers()
}

/** 改档立即提交：这个下拉本身就是操作，再加一个"保存"按钮只会让人以为没生效。 */
async function changeUserLevel(row: SubjectQuotaUserVO, levelCode: string | undefined) {
  await assignUserLevel({ userId: row.userId, levelCode: levelCode || undefined })
  ElMessage.success('已调整，客服端最长 60 秒后生效')
  await loadUsers()
}

// ---------- 后台用户分档 ----------
const adminUserLoading = ref(false)
const adminUsers = ref<AdminQuotaUserVO[]>([])
const adminUserTotal = ref(0)
const adminUserQuery = reactive<PageQuery>({ pageNum: 1, pageSize: 10, keyword: '' })

async function loadAdminUsers() {
  adminUserLoading.value = true
  try {
    const page = await pageAdminQuotaUsers(adminUserQuery)
    adminUsers.value = page.list
    adminUserTotal.value = page.total
  } finally {
    adminUserLoading.value = false
  }
}

function searchAdminUsers() {
  adminUserQuery.pageNum = 1
  return loadAdminUsers()
}

async function changeAdminUserLevel(row: AdminQuotaUserVO, levelCode: string | undefined) {
  await assignAdminUserLevel({ userId: row.userId, levelCode: levelCode || undefined })
  ElMessage.success('已调整，最长 60 秒后生效')
  await loadAdminUsers()
}

// ---------- 超限命中 ----------
const hitLoading = ref(false)
const hitHours = ref(24)
const hits = ref<SubjectQuotaHitVO[]>([])
const hitRank = ref<SubjectQuotaHitRank[]>([])

async function loadHits() {
  hitLoading.value = true
  try {
    const [rank, detail] = await Promise.all([
      fetchSubjectQuotaHitRank(hitHours.value, 20),
      fetchSubjectQuotaHits(hitHours.value, 100),
    ])
    hitRank.value = rank
    hits.value = detail
  } finally {
    hitLoading.value = false
  }
}

// ---------- 公共 ----------
const subjectTypeLabels: Record<string, string> = {
  USER: '登录用户', ADMIN_USER: '后台用户', IP: '匿名 IP', API_KEY: '接入方',
}

const actionLabels: Record<string, string> = {
  BLOCK: '拦截', WARN: '仅记录',
}

const kindLabels: Record<string, string> = {
  TOKEN: 'token 用量', REQUEST: '请求次数',
}

function formatWindow(seconds: number): string {
  if (!seconds) return '-'
  return seconds % 60 === 0 ? `${seconds / 60} 分钟` : `${seconds} 秒`
}

function formatLimit(value: number, unit: string): string {
  return value > 0 ? `${value} ${unit}` : '不限'
}

function formatTime(ms?: number): string {
  return ms ? new Date(ms).toLocaleString('zh-CN') : '-'
}

onMounted(async () => {
  await Promise.all([loadLevels(), loadUsers(), loadAdminUsers()])
})
</script>

<template>
  <div class="page">
    <el-alert type="warning" :closable="false" show-icon class="notice">
      按<b>调用者</b>限流：每个登录用户 / 每个匿名 IP / 每把 API Key 在最近一段时间内的 token 量与请求次数上限。
      与同级的<b>限流规则</b>（按路径限）、<b>配额与计费</b>（按租户限月度花费）三者并存，任一触顶都会被拦。
      客服端需开 <code>customer-work.subject-quota.enabled=true</code>、后台需开
      <code>admin.subject-quota.enabled=true</code> 才真正生效——这里能配，不等于一定在跑。
      改动经指纹轮询与本地缓存下发，<b>最长 60 秒生效</b>。
    </el-alert>

    <el-tabs v-model="activeTab">
      <!-- 等级 -->
      <el-tab-pane label="额度等级" name="levels">
        <el-card>
          <div class="toolbar">
            <el-button v-permission="'subject-quota:level-edit'" type="primary" @click="openLevelCreate">
              新增等级
            </el-button>
            <el-button @click="loadLevels">刷新</el-button>
          </div>

          <el-table v-loading="levelLoading" :data="levels" style="width: 100%">
            <el-table-column prop="levelCode" label="等级编码" width="130" />
            <el-table-column prop="levelName" label="等级名称" min-width="130" show-overflow-tooltip />
            <el-table-column label="适用主体" width="110">
              <template #default="{ row }">{{ subjectTypeLabels[row.subjectType] ?? row.subjectType }}</template>
            </el-table-column>
            <el-table-column label="滚动窗口" width="110">
              <template #default="{ row }">{{ formatWindow(row.windowSeconds) }}</template>
            </el-table-column>
            <el-table-column label="token 上限" width="130">
              <template #default="{ row }">{{ formatLimit(row.tokenLimit, 'token') }}</template>
            </el-table-column>
            <el-table-column label="次数上限" width="110">
              <template #default="{ row }">{{ formatLimit(row.requestLimit, '次') }}</template>
            </el-table-column>
            <el-table-column label="超限处置" width="100">
              <template #default="{ row }">
                <el-tag :type="row.exceedAction === 'BLOCK' ? 'danger' : 'warning'">
                  {{ actionLabels[row.exceedAction] ?? row.exceedAction }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button v-permission="'subject-quota:level-edit'" link type="primary" @click="openLevelEdit(row)">
                  编辑
                </el-button>
                <el-button v-permission="'subject-quota:level-edit'" link type="danger" @click="removeLevel(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 用户分档 -->
      <el-tab-pane label="用户分档" name="users">
        <el-card>
          <div class="toolbar">
            <el-input
              v-model="userQuery.keyword"
              placeholder="按用户名或昵称搜索"
              style="width: 220px"
              clearable
              @keyup.enter="searchUsers"
            />
            <el-button type="primary" @click="searchUsers">搜索</el-button>
          </div>

          <el-table v-loading="userLoading" :data="users" style="width: 100%">
            <el-table-column prop="username" label="用户名" min-width="140" show-overflow-tooltip />
            <el-table-column prop="nickname" label="昵称" min-width="120" show-overflow-tooltip />
            <el-table-column prop="userId" label="用户 ID" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="注册时间" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAtMs) }}</template>
            </el-table-column>
            <el-table-column label="额度等级" width="200" fixed="right">
              <template #default="{ row }">
                <el-select
                  :model-value="row.levelCode ?? ''"
                  placeholder="默认档"
                  clearable
                  style="width: 100%"
                  :disabled="!auth.hasPermission('subject-quota:user-edit')"
                  @change="(value: string) => changeUserLevel(row, value)"
                >
                  <el-option
                    v-for="level in levels.filter((l) => l.subjectType === 'USER')"
                    :key="level.levelCode"
                    :label="`${level.levelName}（${level.levelCode}）`"
                    :value="level.levelCode"
                  />
                </el-select>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="userQuery.pageNum"
            v-model:page-size="userQuery.pageSize"
            :total="userTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            class="pager"
            @current-change="loadUsers"
            @size-change="searchUsers"
          />
        </el-card>
      </el-tab-pane>

      <!-- 后台用户分档 -->
      <el-tab-pane label="后台用户" name="admin-users">
        <el-card>
          <el-alert type="info" :closable="false" show-icon class="notice">
            后台账号跑的是智能体调试、VibeCoding 这类单次很重、频次很低的任务，
            所以默认档（<code>admin-default</code>）的窗口是 1 小时、额度比 C 端宽得多。
            只统计真正调模型的入口，翻列表、看详情不占额度。
          </el-alert>
          <div class="toolbar">
            <el-input
              v-model="adminUserQuery.keyword"
              placeholder="按用户名或昵称搜索"
              style="width: 220px"
              clearable
              @keyup.enter="searchAdminUsers"
            />
            <el-button type="primary" @click="searchAdminUsers">搜索</el-button>
          </div>

          <el-table v-loading="adminUserLoading" :data="adminUsers" style="width: 100%">
            <el-table-column prop="username" label="用户名" min-width="140" show-overflow-tooltip />
            <el-table-column prop="nickname" label="昵称" min-width="120" show-overflow-tooltip />
            <el-table-column prop="userId" label="用户 ID" width="100" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'">
                  {{ row.status === 1 ? '启用' : '禁用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createTime" label="创建时间" width="180" />
            <el-table-column label="额度等级" width="200" fixed="right">
              <template #default="{ row }">
                <el-select
                  :model-value="row.levelCode ?? ''"
                  placeholder="默认档"
                  clearable
                  style="width: 100%"
                  :disabled="!auth.hasPermission('subject-quota:user-edit')"
                  @change="(value: string) => changeAdminUserLevel(row, value)"
                >
                  <el-option
                    v-for="level in levels.filter((l) => l.subjectType === 'ADMIN_USER')"
                    :key="level.levelCode"
                    :label="`${level.levelName}（${level.levelCode}）`"
                    :value="level.levelCode"
                  />
                </el-select>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="adminUserQuery.pageNum"
            v-model:page-size="adminUserQuery.pageSize"
            :total="adminUserTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            class="pager"
            @current-change="loadAdminUsers"
            @size-change="searchAdminUsers"
          />
        </el-card>
      </el-tab-pane>

      <!-- 超限记录 -->
      <el-tab-pane label="超限记录" name="hits">
        <el-card>
          <div class="toolbar">
            <el-select v-model="hitHours" style="width: 140px" @change="loadHits">
              <el-option label="最近 1 小时" :value="1" />
              <el-option label="最近 24 小时" :value="24" />
              <el-option label="最近 7 天" :value="168" />
            </el-select>
            <el-button type="primary" @click="loadHits">查询</el-button>
            <span class="hint">只在真的触顶时才记录，正常流量不产生任何数据。</span>
          </div>

          <el-divider content-position="left">谁在刷（命中次数排行）</el-divider>
          <el-table v-loading="hitLoading" :data="hitRank" style="width: 100%">
            <el-table-column label="主体类型" width="110">
              <template #default="{ row }">{{ subjectTypeLabels[row.subjectType] ?? row.subjectType }}</template>
            </el-table-column>
            <el-table-column prop="subjectId" label="主体标识" min-width="220" show-overflow-tooltip />
            <el-table-column prop="levelCode" label="等级" width="120" />
            <el-table-column prop="hitCount" label="命中次数" width="110" sortable />
            <el-table-column label="最近命中" width="180">
              <template #default="{ row }">{{ formatTime(row.lastHitAtMs) }}</template>
            </el-table-column>
          </el-table>

          <el-divider content-position="left">命中明细</el-divider>
          <el-table v-loading="hitLoading" :data="hits" style="width: 100%">
            <el-table-column label="时间" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAtMs) }}</template>
            </el-table-column>
            <el-table-column label="主体" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                {{ subjectTypeLabels[row.subjectType] ?? row.subjectType }} · {{ row.subjectId }}
              </template>
            </el-table-column>
            <el-table-column prop="levelCode" label="等级" width="110" />
            <el-table-column label="触顶维度" width="120">
              <template #default="{ row }">{{ kindLabels[row.limitKind] ?? row.limitKind }}</template>
            </el-table-column>
            <el-table-column label="用量 / 上限" width="150">
              <template #default="{ row }">{{ row.used }} / {{ row.limitValue }}</template>
            </el-table-column>
            <el-table-column label="处置" width="100">
              <template #default="{ row }">
                <el-tag :type="row.action === 'BLOCK' ? 'danger' : 'warning'">
                  {{ actionLabels[row.action] ?? row.action }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="resource" label="触发位置" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 等级编辑弹窗 -->
    <el-dialog
      v-model="levelDialogVisible"
      :title="levelDialogMode === 'create' ? '新增等级' : '编辑等级'"
      width="560px"
    >
      <el-form ref="levelFormRef" :model="levelForm" :rules="levelRules" label-width="110px">
        <el-form-item label="等级编码" prop="levelCode">
          <el-input
            v-model="levelForm.levelCode"
            :disabled="levelDialogMode === 'edit'"
            placeholder="如 free / vip"
          />
        </el-form-item>
        <el-form-item label="等级名称" prop="levelName">
          <el-input v-model="levelForm.levelName" placeholder="如 免费用户" />
        </el-form-item>
        <el-form-item label="适用主体">
          <el-select v-model="levelForm.subjectType" style="width: 100%">
            <el-option label="登录用户（按 userId 计）" value="USER" />
            <el-option label="后台用户（按 sys_user 登录 ID 计）" value="ADMIN_USER" />
            <el-option label="匿名访客（按来源 IP 计）" value="IP" />
            <el-option label="接入方（按 API Key 指纹计）" value="API_KEY" />
          </el-select>
        </el-form-item>
        <el-form-item label="滚动窗口" prop="windowSeconds">
          <el-input-number v-model="levelForm.windowSeconds" :min="60" :step="300" style="width: 100%" />
          <div class="form-hint">秒。1800 = 30 分钟，3600 = 1 小时。窗口是滚动的，不在整点归零。</div>
        </el-form-item>
        <el-form-item label="token 上限">
          <el-input-number v-model="levelForm.tokenLimit" :min="0" :step="10000" style="width: 100%" />
          <div class="form-hint">0 = 不限。只统计模型实际消耗，查询类请求不计入。</div>
        </el-form-item>
        <el-form-item label="次数上限">
          <el-input-number v-model="levelForm.requestLimit" :min="0" :step="10" style="width: 100%" />
          <div class="form-hint">0 = 不限。口径是 HTTP 请求数与 WS 对话数，含查询类请求。</div>
        </el-form-item>
        <el-form-item label="超限处置">
          <el-radio-group v-model="levelForm.exceedAction">
            <el-radio label="BLOCK">拦截</el-radio>
            <el-radio label="WARN">仅记录</el-radio>
          </el-radio-group>
          <div class="form-hint">新等级上线可先用「仅记录」观察几天，确认阈值合理再收紧。</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="levelForm.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="levelForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="levelDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitLevel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
}

.notice {
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.form-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
</style>
