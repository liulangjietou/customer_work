<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { deleteAgentDraft, getAgentDraft, listAgentDrafts, type AgentDraft } from '@/api/agentDraft'
import { getRequestErrorMessage } from '@/api/request'
import { useAuthStore } from '@/store/auth'
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ restore: [draft: AgentDraft] }>()
const auth = useAuthStore()
const drafts = ref<AgentDraft[]>([])
const loading = ref(false)
const busy = ref('')
const error = ref('')
let generation = 0
onBeforeUnmount(() => {
  generation += 1
})
watch(visible, (open) => {
  generation += 1
  if (open) void load()
  else {
    drafts.value = []
    busy.value = ''
  }
})
watch(
  () => auth.token,
  () => {
    generation += 1
    visible.value = false
  },
  { flush: 'sync' },
)
async function load() {
  const request = ++generation
  loading.value = true
  error.value = ''
  try {
    const result = await listAgentDrafts()
    if (request === generation) drafts.value = result
  } catch (cause) {
    if (request === generation) error.value = getRequestErrorMessage(cause, '草稿暂时无法加载')
  } finally {
    if (request === generation) loading.value = false
  }
}
async function restore(row: AgentDraft) {
  if (busy.value) return
  busy.value = row.id
  const request = generation
  try {
    const draft = await getAgentDraft(row.id)
    if (request !== generation) return
    if (!draft.configuration) throw new Error('草稿内容缺失，请重新加载')
    emit('restore', draft)
    visible.value = false
  } catch (cause) {
    if (request === generation) error.value = getRequestErrorMessage(cause, '草稿恢复失败')
  } finally {
    if (request === generation) busy.value = ''
  }
}
async function remove(row: AgentDraft) {
  if (busy.value) return
  const request = generation
  busy.value = row.id
  try {
    await ElMessageBox.confirm(`删除个人草稿「${row.title}」？`, '删除草稿', { type: 'warning' })
    if (request !== generation || !visible.value) return
    try {
      await deleteAgentDraft(row.id, row.version)
      if (request === generation) drafts.value = drafts.value.filter((draft) => draft.id !== row.id)
    } catch (cause) {
      if (request === generation) error.value = getRequestErrorMessage(cause, '草稿删除失败')
    }
  } catch {
    /* 取消确认时保留草稿。 */
  } finally {
    if (request === generation) busy.value = ''
  }
}
</script>
<template>
  <el-drawer v-model="visible" title="我的配置草稿" size="560px">
    <p class="draft-intro">草稿仅自己可见。恢复后可以继续编辑，保存智能体后配置才会生效。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon>
      <el-button link type="primary" :disabled="loading" @click="load">重新加载</el-button>
    </el-alert>
    <div v-loading="loading" class="draft-list" :aria-busy="loading">
      <article v-for="draft in drafts" :key="draft.id">
        <div>
          <strong>{{ draft.title }}</strong
          ><span
            >{{ draft.agentId ? '修改已有智能体' : '新建智能体' }} ·
            {{ new Date(draft.updatedAtMs).toLocaleString() }}</span
          >
        </div>
        <div class="draft-actions">
          <el-button
            type="primary"
            :disabled="!!busy || !auth.hasPermission(draft.agentId ? 'agent:edit' : 'agent:add')"
            @click="restore(draft)"
            >继续编辑</el-button
          >
          <el-button :disabled="!!busy" @click="remove(draft)">删除</el-button>
        </div>
      </article>
      <el-empty
        v-if="!loading && !drafts.length"
        :description="error ? '未能读取草稿' : '暂无草稿，可以在配置页保存未完成的内容'"
      />
    </div>
  </el-drawer>
</template>
<style scoped>
.draft-intro {
  color: var(--cw-text-muted);
  line-height: 1.7;
  margin-top: 0;
}
.draft-list {
  min-height: 160px;
}
article {
  padding: 20px 0;
  border-bottom: 1px solid var(--cw-line);
}
article strong,
article span {
  display: block;
  overflow-wrap: anywhere;
}
article span {
  color: var(--cw-text-muted);
  font-size: 12px;
  margin-top: 8px;
}
.draft-actions {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}
</style>
