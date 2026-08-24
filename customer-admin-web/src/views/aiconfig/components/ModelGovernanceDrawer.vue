<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  getModel,
  getModelHealth,
  getModelImpact,
  listModelHealthEvents,
  rotateModelCredential,
  runModelHealthCheck,
  updateModelHealthOverride,
} from '@/api/model'
import type {
  ModelHealthEvent,
  ModelHealthSnapshot,
  ModelImpact,
  ModelVO,
} from '@/types/api'
import ModelCertificationPanel from './ModelCertificationPanel.vue'

const props = defineProps<{
  modelValue: boolean
  modelId: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  refreshed: []
}>()

const drawerVisible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})
const loading = ref(false)
const probing = ref(false)
const rotating = ref(false)
const overriding = ref(false)
const activeTab = ref('overview')
const detail = ref<ModelVO | null>(null)
const health = ref<ModelHealthSnapshot | null>(null)
const events = ref<ModelHealthEvent[]>([])
const impact = ref<ModelImpact | null>(null)
const impactAction = ref<'DELETE' | 'DISABLE' | 'ROTATE'>('DELETE')
const rotation = ref({ secretValue: '', expiresAt: null as string | null })
const healthOverride = ref({
  mode: 'FORCE_UNHEALTHY' as 'FORCE_HEALTHY' | 'FORCE_UNHEALTHY',
  reason: '',
  expiresAt: null as string | null,
})

const rail = computed(() => {
  const credentialStatus = detail.value?.credential?.status
  const healthStatus = health.value?.effectiveHealthStatus ?? 'UNKNOWN'
  const certificationStatus = detail.value?.certification?.effectiveStatus
    ?? (detail.value?.certificationRequired ? 'UNKNOWN' : 'NOT_REQUIRED')
  return [
    {
      label: '资产登记',
      value: detail.value?.assetCode ?? '待登记',
      state: detail.value?.assetId ? 'ready' : 'pending',
    },
    {
      label: '凭据引用',
      value: credentialStatus ? `${credentialStatus} · v${detail.value?.credential?.currentVersion}` : 'Legacy',
      state: credentialStatus === 'ACTIVE' ? 'ready' : credentialStatus ? 'danger' : 'pending',
    },
    {
      label: '持续健康',
      value: healthStatus,
      state: healthStatus === 'HEALTHY' ? 'ready'
        : healthStatus === 'UNKNOWN' ? 'pending' : 'danger',
    },
    {
      label: '上线认证',
      value: certificationStatus,
      state: certificationStatus === 'PASSED' || certificationStatus === 'NOT_REQUIRED'
        ? 'ready' : certificationStatus === 'UNKNOWN' ? 'pending' : 'danger',
    },
    {
      label: '变更影响',
      value: impact.value?.allowed ? '可执行' : `${impact.value?.blockerCount ?? 0} 个阻断`,
      state: impact.value?.allowed ? 'ready' : 'danger',
    },
  ]
})

watch(
  [() => props.modelValue, () => props.modelId],
  ([visible, id]) => {
    if (visible && id) {
      void loadAll(id)
    }
    if (!visible) {
      rotation.value = { secretValue: '', expiresAt: null }
      healthOverride.value = { mode: 'FORCE_UNHEALTHY', reason: '', expiresAt: null }
      activeTab.value = 'overview'
    }
  },
  { immediate: true },
)

async function loadAll(id = props.modelId) {
  if (!id) return
  loading.value = true
  try {
    const [model, snapshot, history, impactResult] = await Promise.all([
      getModel(id),
      getModelHealth(id),
      listModelHealthEvents(id),
      getModelImpact(id, impactAction.value),
    ])
    detail.value = model
    health.value = snapshot
    events.value = history
    impact.value = impactResult
  } finally {
    loading.value = false
  }
}

async function refreshImpact() {
  if (!props.modelId) return
  impact.value = await getModelImpact(props.modelId, impactAction.value)
}

async function handleCertificationUpdated() {
  await loadAll()
  emit('refreshed')
}

async function probe() {
  if (!props.modelId) return
  probing.value = true
  try {
    const result = await runModelHealthCheck(props.modelId)
    if (result.testStatus === 1) {
      ElMessage.success(`健康探测通过 · ${result.latencyMs ?? 0} ms`)
    } else {
      ElMessage.error(result.message || '健康探测失败')
    }
    await loadAll()
    emit('refreshed')
  } finally {
    probing.value = false
  }
}

async function applyHealthOverride() {
  if (!props.modelId || !healthOverride.value.reason.trim()) {
    ElMessage.warning('请填写人工覆盖原因')
    return
  }
  if (!healthOverride.value.expiresAt) {
    ElMessage.warning('请设置覆盖到期时间')
    return
  }
  await ElMessageBox.confirm(
    `确认将有效路由状态设置为${healthOverride.value.mode === 'FORCE_HEALTHY' ? '强制健康' : '强制不可用'}？到期后会自动恢复状态机判断。`,
    '模型健康路由覆盖',
    { type: 'warning' },
  )
  overriding.value = true
  try {
    await updateModelHealthOverride(props.modelId, {
      mode: healthOverride.value.mode,
      reason: healthOverride.value.reason.trim(),
      expiresAt: healthOverride.value.expiresAt,
    })
    ElMessage.success('人工健康路由覆盖已生效')
    await loadAll()
    emit('refreshed')
  } finally {
    overriding.value = false
  }
}

async function clearHealthOverride() {
  if (!props.modelId) return
  overriding.value = true
  try {
    await updateModelHealthOverride(props.modelId, {
      mode: 'AUTO',
      reason: '清除人工健康路由覆盖',
      expiresAt: null,
    })
    ElMessage.success('已恢复自动健康路由')
    await loadAll()
    emit('refreshed')
  } finally {
    overriding.value = false
  }
}

async function rotateCredential() {
  if (!props.modelId || !rotation.value.secretValue.trim()) {
    ElMessage.warning('请输入新的凭据值')
    return
  }
  const rotationImpact = await getModelImpact(props.modelId, 'ROTATE')
  impactAction.value = 'ROTATE'
  impact.value = rotationImpact
  if (!rotationImpact.allowed) {
    activeTab.value = 'impact'
    ElMessage.error(`凭据轮换存在 ${rotationImpact.blockerCount} 个生效引用，请先解除阻断`)
    return
  }
  rotating.value = true
  try {
    await rotateModelCredential(props.modelId, {
      secretValue: rotation.value.secretValue,
      expiresAt: rotation.value.expiresAt,
    })
    rotation.value = { secretValue: '', expiresAt: null }
    ElMessage.success('凭据已轮换，旧版本已标记为失效')
    await loadAll()
    emit('refreshed')
  } finally {
    rotating.value = false
  }
}

function formatTime(value: string | null | undefined) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function healthTagType(status: string | null | undefined) {
  if (status === 'HEALTHY') return 'success'
  if (status === 'DEGRADED' || status === 'RECOVERING') return 'warning'
  if (status === 'UNHEALTHY') return 'danger'
  return 'info'
}
</script>

<template>
  <el-drawer v-model="drawerVisible" size="min(760px, 92vw)" destroy-on-close>
    <template #header>
      <div class="drawer-title">
        <div>
          <p class="eyebrow">MODEL GOVERNANCE</p>
          <h2>{{ detail?.modelName ?? '模型部署详情' }}</h2>
        </div>
        <el-tag v-if="detail?.environment" effect="plain">{{ detail.environment }}</el-tag>
      </div>
    </template>

    <div v-loading="loading" class="governance-drawer">
      <section class="status-rail">
        <div v-for="step in rail" :key="step.label" class="rail-item" :class="`is-${step.state}`">
          <span class="rail-dot" />
          <div>
            <span class="rail-label">{{ step.label }}</span>
            <strong>{{ step.value }}</strong>
          </div>
        </div>
      </section>

      <el-tabs v-model="activeTab" class="governance-tabs">
        <el-tab-pane label="资产与部署" name="overview">
          <div class="section-heading">
            <div>
              <h3>资产目录</h3>
              <p>模型能力与运行端点分离管理，Agent 仍稳定引用部署 ID。</p>
            </div>
          </div>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="资产编码">{{ detail?.assetCode ?? '待登记' }}</el-descriptions-item>
            <el-descriptions-item label="资产名称">{{ detail?.assetName ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="厂商 / 家族">{{ detail?.vendor ?? '—' }} / {{ detail?.family ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="能力模态">{{ detail?.modality ?? 'TEXT' }}</el-descriptions-item>
            <el-descriptions-item label="上下文窗口">{{ detail?.contextWindow ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="最大输出">{{ detail?.maxOutputTokens ?? '—' }}</el-descriptions-item>
          </el-descriptions>

          <div class="section-heading split-heading">
            <div>
              <h3>部署端点</h3>
              <p>端点修订号用于识别运行时配置变化。</p>
            </div>
          </div>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="部署编码">{{ detail?.deploymentCode ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="修订号">r{{ detail?.endpointRevision ?? 1 }}</el-descriptions-item>
            <el-descriptions-item label="协议">{{ detail?.protocolAdapter ?? detail?.provider }}</el-descriptions-item>
            <el-descriptions-item label="地域">{{ detail?.region ?? 'GLOBAL' }}</el-descriptions-item>
            <el-descriptions-item label="模型标识">{{ detail?.model }}</el-descriptions-item>
            <el-descriptions-item label="生命周期">{{ detail?.lifecycleStatus ?? 'ACTIVE' }}</el-descriptions-item>
            <el-descriptions-item label="Base URL" :span="2">{{ detail?.baseUrl }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>

        <el-tab-pane label="凭据治理" name="credential">
          <el-alert
            title="系统只回显 SecretRef 元数据，不会返回密文、明文或明文片段。"
            type="info"
            :closable="false"
            show-icon
          />
          <el-descriptions class="section-body" :column="2" border>
            <el-descriptions-item label="引用编码">{{ detail?.credential?.refCode ?? 'Legacy 双读' }}</el-descriptions-item>
            <el-descriptions-item label="后端">{{ detail?.credential?.providerType ?? 'LOCAL_AES' }}</el-descriptions-item>
            <el-descriptions-item label="当前版本">v{{ detail?.credential?.currentVersion ?? 1 }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="detail?.credential?.status === 'ACTIVE' ? 'success' : 'danger'">
                {{ detail?.credential?.status ?? '待迁移' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="到期时间">{{ formatTime(detail?.credential?.expiresAt) }}</el-descriptions-item>
            <el-descriptions-item label="最近轮换">{{ formatTime(detail?.credential?.lastRotatedAt) }}</el-descriptions-item>
          </el-descriptions>

          <div class="rotation-panel">
            <h3>轮换凭据</h3>
            <p>新值只用于本次请求；成功或关闭抽屉后会立即从表单状态清除。</p>
            <el-form label-position="top">
              <el-form-item label="新凭据">
                <el-input v-model="rotation.secretValue" type="password" show-password autocomplete="new-password" />
              </el-form-item>
              <el-form-item label="到期时间（可选）">
                <el-date-picker
                  v-model="rotation.expiresAt"
                  type="datetime"
                  value-format="YYYY-MM-DDTHH:mm:ss"
                  placeholder="不设置到期时间"
                  style="width: 100%"
                />
              </el-form-item>
              <el-button v-permission="'model:edit'" type="primary" :loading="rotating" @click="rotateCredential">
                确认轮换
              </el-button>
            </el-form>
          </div>
        </el-tab-pane>

        <el-tab-pane label="健康历史" name="health">
          <div class="health-hero">
            <div>
              <span>有效路由状态</span>
              <strong>{{ health?.effectiveHealthStatus ?? 'UNKNOWN' }}</strong>
              <small>状态机：{{ health?.healthStatus ?? 'UNKNOWN' }} · {{ health?.routingAvailable ? '可路由' : '已摘除' }}</small>
            </div>
            <el-button v-permission="'model:health-test'" type="primary" :loading="probing" @click="probe">
              立即探测
            </el-button>
          </div>
          <div class="metric-grid">
            <div><span>认证</span><strong>{{ health?.authStatus ?? 'UNKNOWN' }}</strong></div>
            <div><span>最近延迟</span><strong>{{ health?.lastLatencyMs == null ? '—' : `${health.lastLatencyMs} ms` }}</strong></div>
            <div><span>连续失败</span><strong>{{ health?.consecutiveFailures ?? 0 }}</strong></div>
            <div><span>连续恢复</span><strong>{{ health?.consecutiveSuccesses ?? 0 }}</strong></div>
            <div><span>冷却截止</span><strong>{{ formatTime(health?.cooldownUntil) }}</strong></div>
            <div><span>下次巡检</span><strong>{{ formatTime(health?.nextProbeAt) }}</strong></div>
            <div><span>人工覆盖</span><strong>{{ health?.overrideMode ?? 'AUTO' }}</strong></div>
            <div><span>覆盖截止</span><strong>{{ formatTime(health?.overrideUntil) }}</strong></div>
          </div>
          <el-alert
            v-if="health?.overrideMode !== 'AUTO'"
            :title="`人工覆盖生效中：${health?.overrideReason ?? '未填写原因'}`"
            :description="`操作人：${health?.overrideOperatorName ?? health?.overrideOperatorId ?? '—'}；到期：${formatTime(health?.overrideUntil)}`"
            type="warning"
            :closable="false"
            show-icon
          />
          <div class="health-override-panel">
            <div class="section-heading">
              <div>
                <h3>人工路由覆盖</h3>
                <p>只覆盖有效路由判断，不改写探测事实；到期后自动恢复 AUTO。</p>
              </div>
              <el-button
                v-if="health?.overrideMode !== 'AUTO'"
                v-permission="'model:health-override'"
                :loading="overriding"
                @click="clearHealthOverride"
              >恢复自动</el-button>
            </div>
            <el-form label-position="top">
              <el-form-item label="覆盖模式">
                <el-radio-group v-model="healthOverride.mode">
                  <el-radio-button value="FORCE_UNHEALTHY">强制摘除</el-radio-button>
                  <el-radio-button value="FORCE_HEALTHY">强制可用</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="原因">
                <el-input
                  v-model="healthOverride.reason"
                  type="textarea"
                  :rows="2"
                  maxlength="500"
                  show-word-limit
                  placeholder="填写审批、故障或恢复依据"
                />
              </el-form-item>
              <el-form-item label="到期时间">
                <el-date-picker
                  v-model="healthOverride.expiresAt"
                  type="datetime"
                  value-format="YYYY-MM-DDTHH:mm:ss"
                  placeholder="到期后自动恢复"
                  style="width: 100%"
                />
              </el-form-item>
              <el-button
                v-permission="'model:health-override'"
                type="primary"
                :loading="overriding"
                @click="applyHealthOverride"
              >应用覆盖</el-button>
            </el-form>
          </div>
          <el-table :data="events" max-height="360" empty-text="暂无健康事件">
            <el-table-column prop="occurredAt" label="时间" width="180">
              <template #default="{ row }">{{ formatTime(row.occurredAt) }}</template>
            </el-table-column>
            <el-table-column prop="source" label="来源" width="100" />
            <el-table-column prop="eventType" label="事件" width="150" />
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="healthTagType(row.effectiveHealthStatus)">{{ row.effectiveHealthStatus }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="latencyMs" label="延迟(ms)" width="100" />
            <el-table-column prop="errorCategory" label="错误分类" width="120" />
            <el-table-column prop="message" label="脱敏摘要" min-width="180" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="上线认证" name="certification">
          <ModelCertificationPanel
            :model-id="props.modelId"
            :model="detail"
            @updated="handleCertificationUpdated"
          />
        </el-tab-pane>

        <el-tab-pane label="影响预检" name="impact">
          <div class="section-heading">
            <div>
              <h3>变更影响图</h3>
              <p>汇聚主/备 Agent、渠道、任务、路由策略、配置版本与运行时发布任务。</p>
            </div>
            <el-radio-group v-model="impactAction" @change="refreshImpact">
              <el-radio-button value="DELETE">删除</el-radio-button>
              <el-radio-button value="DISABLE">禁用</el-radio-button>
              <el-radio-button value="ROTATE">轮换凭据</el-radio-button>
            </el-radio-group>
          </div>
          <el-alert
            :title="impact?.allowed ? '预检通过，可执行该变更' : `存在 ${impact?.blockerCount ?? 0} 个生效引用，变更将被后端阻断`"
            :type="impact?.allowed ? 'success' : 'error'"
            :closable="false"
            show-icon
          />
          <el-table class="section-body" :data="impact?.items ?? []" max-height="430">
            <el-table-column prop="tenantId" label="租户" width="120" />
            <el-table-column prop="resourceType" label="资源" width="140" />
            <el-table-column prop="relationType" label="关系" width="140" />
            <el-table-column prop="resourceName" label="名称" min-width="160" />
            <el-table-column label="阻断" width="80">
              <template #default="{ row }">
                <el-tag :type="row.blocking ? 'danger' : 'info'">{{ row.blocking ? '是' : '否' }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-title {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  width: 100%;
  padding-right: 12px;
}

.drawer-title h2,
.section-heading h3,
.rotation-panel h3 {
  margin: 0;
  color: #172033;
}

.eyebrow {
  margin: 0 0 4px;
  color: #64748b;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .14em;
}

.status-rail {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 22px;
}

.rail-item {
  display: flex;
  gap: 10px;
  min-height: 66px;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #f8fafc;
}

.rail-dot {
  width: 9px;
  height: 9px;
  margin-top: 5px;
  border-radius: 50%;
  background: #94a3b8;
  box-shadow: 0 0 0 4px #e2e8f0;
}

.rail-item.is-ready .rail-dot { background: #0f9f78; box-shadow: 0 0 0 4px #d1fae5; }
.rail-item.is-danger .rail-dot { background: #dc4c4c; box-shadow: 0 0 0 4px #fee2e2; }
.rail-label,
.metric-grid span,
.health-hero span {
  display: block;
  margin-bottom: 5px;
  color: #64748b;
  font-size: 12px;
}

.rail-item strong { display: block; overflow: hidden; color: #25314a; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.governance-tabs { --el-tabs-header-height: 48px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin: 14px 0; }
.section-heading p,
.rotation-panel p { margin: 5px 0 0; color: #64748b; font-size: 13px; }
.split-heading { margin-top: 26px; }
.section-body { margin-top: 16px; }
.rotation-panel { margin-top: 22px; padding: 18px; border: 1px solid #e2e8f0; border-radius: 12px; background: #f8fafc; }
.rotation-panel .el-form { margin-top: 16px; }
.health-hero { display: flex; align-items: center; justify-content: space-between; margin: 10px 0 16px; padding: 18px 20px; border-radius: 12px; color: white; background: linear-gradient(120deg, #172033, #344565); }
.health-hero strong { display: block; font-size: 24px; letter-spacing: .04em; }
.health-hero span { color: #cbd5e1; }
.health-hero small { display: block; margin-top: 6px; color: #cbd5e1; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-bottom: 18px; }
.metric-grid > div { padding: 14px; border: 1px solid #e2e8f0; border-radius: 10px; background: #fff; }
.metric-grid strong { color: #172033; font-size: 14px; }
.health-override-panel { margin: 18px 0; padding: 18px; border: 1px solid #e2e8f0; border-radius: 12px; background: #f8fafc; }
.health-override-panel .section-heading { margin-top: 0; }

@media (max-width: 760px) {
  .status-rail,
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .section-heading { align-items: flex-start; flex-direction: column; }
}
</style>
