<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useCrudPage } from '@/composables/useCrudPage'
import CrudLoadState from '@/components/CrudLoadState.vue'
import type { FormInstance } from 'element-plus'
import {
  createChannelRobot,
  deleteChannelRobot,
  pageChannelRobots,
  updateChannelRobot,
} from '@/api/channel-robot'
import { pageAgents } from '@/api/agent'
import type {
  AgentVO,
  PageQuery,
  ChannelRobotSaveRequest,
  ChannelRobotVO,
  ChannelType,
} from '@/types/api'

// 渠道类型元数据（列表 tag 展示 + 表单下拉）：当前仅钉钉可接入，企业微信/微信占位待支持。
interface ChannelTypeMeta {
  value: ChannelType
  label: string
  tagType: 'success' | 'info' | 'warning'
  /** 是否可在新建/编辑时选择；false 表示尚未支持，下拉项 disabled。 */
  selectable: boolean
}
const channelTypes: ChannelTypeMeta[] = [
  { value: 'dingtalk', label: '钉钉', tagType: 'success', selectable: true },
  { value: 'wechat', label: '微信', tagType: 'warning', selectable: true },
  { value: 'wecom', label: '企业微信', tagType: 'info', selectable: false },
]
function channelMetaOf(type: ChannelType): ChannelTypeMeta {
  return channelTypes.find((c) => c.value === type) ?? channelTypes[0]
}

// 启用中的智能体下拉：value 取 agentCode（后端绑定关系存的是 agentCode，非 id）。
const agentOptions = ref<AgentVO[]>([])
function agentNameOf(agentCode: string): string {
  return agentOptions.value.find((a) => a.agentCode === agentCode)?.agentName ?? agentCode
}

async function loadAgentOptions() {
  const result = await pageAgents({ pageNum: 1, pageSize: 100 })
  agentOptions.value = result.list
}

const formRef = ref<FormInstance>()
// hasSecret 仅用于编辑时展示「已配置」标识，不随表单提交。
const editingHasSecret = ref(false)
const editingHasEncodingAesKey = ref(false)

function emptyForm(): ChannelRobotSaveRequest {
  return {
    channelType: 'dingtalk',
    robotName: '',
    appKey: '',
    appSecret: '',
    robotCode: '',
    callbackMode: 'plaintext',
    encodingAesKey: '',
    agentCode: '',
    sessionMode: 'continuous',
    status: 1,
    remark: '',
  }
}
const {
  loading, loadError, submitting, deletingId, list, total, query, dialogVisible, dialogMode, form,
  loadList, handleSearch, openCreate: openCreateBase, openEdit: openEditBase, handleSubmit, handleDelete,
} = useCrudPage<ChannelRobotVO, PageQuery & { channelType: string }, ChannelRobotSaveRequest>({
  page: async ({ pageNum = 1, pageSize = 10, keyword, channelType }) => {
    const result = await pageChannelRobots({ current: pageNum, size: pageSize, keyword, channelType })
    return { list: result.records, total: result.total, pageNum, pageSize }
  },
  formRef,
  create: value => createChannelRobot(toPayload(value)),
  update: (id, value) => updateChannelRobot(id, toPayload(value)),
  remove: row => deleteChannelRobot(row.id),
  initQuery: () => ({ pageNum: 1, pageSize: 10, channelType: '', keyword: '' }),
  initForm: emptyForm,
  toForm: row => ({ channelType: row.channelType, robotName: row.robotName, appKey: row.appKey, appSecret: '',
    robotCode: row.robotCode, callbackMode: row.callbackMode ?? 'plaintext', encodingAesKey: '', agentCode: row.agentCode,
    sessionMode: row.sessionMode ?? 'continuous', status: row.status, remark: row.remark }),
  beforeSubmit: (mode, value) => {
    if (mode === 'create' && !value.appSecret) {
      ElMessage.warning('新建渠道机器人必须填写 AppSecret')
      return false
    }
    if (value.channelType === 'wechat' && value.callbackMode === 'safe'
      && (mode === 'create' || !editingHasEncodingAesKey.value) && !value.encodingAesKey?.trim()) {
      ElMessage.warning('微信安全模式必须填写 EncodingAESKey')
      return false
    }
    return true
  },
  deleteConfirm: row => `确认删除渠道机器人「${row.robotName}」？`,
})

/** 渠道特有的空值与回调契约在 API 调用边界处理，不改写用户当前输入。 */
function toPayload(value: ChannelRobotSaveRequest): ChannelRobotSaveRequest {
  const wechat = value.channelType === 'wechat'
  return { ...value, robotCode: wechat ? value.robotCode?.trim() ?? '' : value.robotCode?.trim() || value.appKey.trim(),
    callbackMode: wechat ? value.callbackMode : 'plaintext',
    encodingAesKey: wechat && value.callbackMode === 'safe' ? value.encodingAesKey?.trim() : null }
}

// 当前表单选中的是否为微信：微信下 RobotCode 语义为「回调 Token」，必填且不自动回填 AppKey。
const isWechat = computed(() => form.channelType === 'wechat')
const isWechatSafe = computed(() => isWechat.value && form.callbackMode === 'safe')

function openCreate() {
  editingHasSecret.value = false
  editingHasEncodingAesKey.value = false
  openCreateBase()
}

function openEdit(row: ChannelRobotVO) {
  editingHasSecret.value = row.hasSecret
  editingHasEncodingAesKey.value = row.hasEncodingAesKey
  openEditBase(row)
}

const dialogTitle = computed(() => (dialogMode.value === 'edit' ? '编辑渠道机器人' : '新建渠道机器人'))

onMounted(() => {
  loadList()
  loadAgentOptions()
})
</script>

<template>
  <div class="page">
    <CrudLoadState :error="loadError" :has-stale-data="list.length > 0" :loading="loading" @retry="loadList" />
    <el-alert type="info" :closable="false" show-icon title="钉钉机器人接入指引">
      <template #default>
        <div>1. 到钉钉开放平台创建「企业内部应用」。</div>
        <div>2. 为应用添加「机器人」能力。</div>
        <div>3. 消息接收模式选择 <strong>Stream 模式</strong>（无需公网回调地址）。</div>
        <div>4. 把应用的 AppKey / AppSecret / RobotCode 填入本页并绑定要对话的智能体即可。</div>
      </template>
    </el-alert>

    <el-alert type="warning" :closable="false" show-icon title="微信公众号接入指引">
      <template #default>
        <div>
          1. 申请
          <a href="https://mp.weixin.qq.com/debug/cgi-bin/sandbox?t=sandbox/login" target="_blank" rel="noreferrer"
            >微信公众平台测试号</a
          >（或使用已认证服务号），拿到 AppID / AppSecret。
        </div>
        <div>
          2. 在「接口配置信息」填写 URL 与 Token：URL =
          <code>https://&lt;公网域名&gt;/api/channels/wechat/&lt;AppID&gt;/callback</code>，Token 自定义。
        </div>
        <div>3. 建议在公众平台选择「安全模式」，并把 Token 与 43 位 EncodingAESKey 一并填入本页；明文模式仅用于兼容。</div>
        <div>4. 回调需公网可达，本地开发请用内网穿透（如 ngrok / natapp）把 8081 暴露出去。</div>
      </template>
    </el-alert>

    <el-card>
      <div class="toolbar">
        <el-select v-model="query.channelType" placeholder="全部渠道" clearable style="width: 140px" @change="handleSearch">
          <el-option v-for="c in channelTypes" :key="c.value" :label="c.label" :value="c.value" />
        </el-select>
        <el-input
          v-model="query.keyword"
          placeholder="按机器人名称/AppKey搜索"
          style="width: 240px"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <div class="toolbar-actions">
          <el-button v-permission="'channel-robot:add'" class="cw-final-action" type="primary" @click="openCreate">新增机器人</el-button>
        </div>
      </div>

      <el-table v-if="!loadError || list.length > 0" v-loading="loading" :data="list" class="data-table" empty-text="暂无符合条件的渠道机器人">
        <el-table-column label="渠道类型" width="110" align="center">
          <template #default="{ row }: { row: ChannelRobotVO }">
            <el-tag :type="channelMetaOf(row.channelType).tagType">{{ channelMetaOf(row.channelType).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="robotName" label="机器人名称" min-width="140" show-overflow-tooltip class-name="primary-column" />
        <el-table-column prop="appKey" label="AppKey" min-width="160" show-overflow-tooltip />
        <el-table-column prop="robotCode" label="RobotCode" min-width="160" show-overflow-tooltip />
        <el-table-column label="回调模式" width="100" align="center">
          <template #default="{ row }: { row: ChannelRobotVO }">
            <el-tag v-if="row.channelType === 'wechat'" :type="row.callbackMode === 'safe' ? 'success' : 'warning'" effect="plain">
              {{ row.callbackMode === 'safe' ? '安全' : '明文' }}
            </el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="绑定智能体" min-width="140">
          <template #default="{ row }: { row: ChannelRobotVO }">
            {{ agentNameOf(row.agentCode) }}
          </template>
        </el-table-column>
        <el-table-column label="会话模式" width="100" align="center">
          <template #default="{ row }: { row: ChannelRobotVO }">
            <el-tag :type="row.sessionMode === 'per_message' ? 'warning' : 'success'" effect="plain">
              {{ row.sessionMode === 'per_message' ? '单次问答' : '持续会话' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }: { row: ChannelRobotVO }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }: { row: ChannelRobotVO }">
            <el-button v-permission="'channel-robot:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'channel-robot:delete'" link type="danger" :loading="deletingId === row.id" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px">
      <el-form ref="formRef" :disabled="submitting" :model="form" label-width="100px">
        <el-form-item label="渠道类型" prop="channelType" :rules="[{ required: true, message: '请选择渠道类型' }]">
          <el-select v-model="form.channelType" style="width: 100%">
            <el-option
              v-for="c in channelTypes"
              :key="c.value"
              :label="c.label"
              :value="c.value"
              :disabled="!c.selectable"
            >
              <span>{{ c.label }}</span>
              <span v-if="!c.selectable" style="float: right; color: var(--el-text-color-placeholder); font-size: 12px">即将支持</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="机器人名称" prop="robotName" :rules="[{ required: true, message: '请输入机器人名称' }]">
          <el-input v-model="form.robotName" placeholder="用于识别，如「客服钉钉机器人」" />
        </el-form-item>
        <el-form-item
          :label="isWechat ? 'AppID' : 'AppKey'"
          prop="appKey"
          :rules="[{ required: true, message: isWechat ? '请输入 AppID' : '请输入 AppKey' }]"
        >
          <el-input v-model="form.appKey" :placeholder="isWechat ? '公众号 AppID' : '钉钉应用 AppKey / ClientId'" />
        </el-form-item>
        <el-form-item label="AppSecret">
          <el-input
            v-model="form.appSecret!"
            type="password"
            show-password
            :placeholder="dialogMode === 'edit' ? '留空表示不修改' : '必填'"
          />
          <div v-if="dialogMode === 'edit' && editingHasSecret" class="form-tip">
            <el-tag type="success" size="small">已配置</el-tag>
            <span>已保存 AppSecret，留空则沿用现有值</span>
          </div>
        </el-form-item>
        <el-form-item
          :label="isWechat ? '回调 Token' : 'RobotCode'"
          prop="robotCode"
          :rules="isWechat ? [{ required: true, message: '请输入回调 Token' }] : []"
        >
          <el-input
            v-model="form.robotCode!"
            :placeholder="isWechat ? '与公众平台「接口配置信息」里的 Token 完全一致' : '留空则与 AppKey 一致'"
          />
          <div class="form-tip">
            {{
              isWechat
                ? '微信语义下即公众平台「接口配置信息」里的 Token，必填且需与其完全一致（参与回调签名校验）。'
                : '钉钉 Stream 模式下 RobotCode 通常与 AppKey 相同，留空提交时自动填为 AppKey。'
            }}
          </div>
        </el-form-item>
        <el-form-item v-if="isWechat" label="回调模式">
          <el-radio-group v-model="form.callbackMode!">
            <el-radio value="safe">安全模式</el-radio>
            <el-radio value="plaintext">明文兼容</el-radio>
          </el-radio-group>
          <div class="form-tip">生产建议安全模式：消息签名校验后使用 AES 解密，并核对密文中的 AppID。</div>
        </el-form-item>
        <el-form-item v-if="isWechatSafe" label="EncodingAESKey">
          <el-input
            v-model="form.encodingAesKey!"
            type="password"
            show-password
            maxlength="43"
            :placeholder="dialogMode === 'edit' ? '留空表示不修改' : '公众平台生成的 43 位 EncodingAESKey'"
          />
          <div v-if="dialogMode === 'edit' && editingHasEncodingAesKey" class="form-tip">
            <el-tag type="success" size="small">已配置</el-tag>
            <span>已加密保存，留空则沿用现有值</span>
          </div>
        </el-form-item>
        <el-form-item label="绑定智能体" prop="agentCode" :rules="[{ required: true, message: '请选择要绑定的智能体' }]">
          <el-select v-model="form.agentCode" placeholder="请选择智能体" style="width: 100%">
            <el-option
              v-for="agent in agentOptions"
              :key="agent.agentCode"
              :label="agent.agentName"
              :value="agent.agentCode"
              :disabled="agent.status !== 1"
            >
              <span>{{ agent.agentName }}</span>
              <span v-if="agent.status !== 1" style="float: right; color: var(--el-text-color-placeholder); font-size: 12px">已停用</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="会话模式">
          <el-radio-group v-model="form.sessionMode!">
            <el-radio value="continuous">持续会话</el-radio>
            <el-radio value="per_message">单次问答</el-radio>
          </el-radio-group>
          <div class="form-tip">
            持续会话：同一用户多轮对话携带历史上下文，发「/new」可重置；单次问答：每条消息都是全新上下文，响应更快。
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status!" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark!" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button class="cw-final-action" type="primary" :loading="submitting" @click="handleSubmit">保存机器人</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 长回调地址可以换行，避免说明区域被 el-alert 的边界裁切。 */
:deep(.el-alert__content) {
  min-width: 0;
  overflow-wrap: anywhere;
}

:deep(.el-alert code) {
  white-space: normal;
  overflow-wrap: anywhere;
}

.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
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

.form-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
  margin-top: 4px;
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
