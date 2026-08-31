<script setup lang="ts">
import { ref, watch } from 'vue'
import { addSessionToProject, createProject, listProjects } from '@/api/project'
import type { ProjectVO } from '@/types/api'

const props = defineProps<{ agentCode: string; sessionId: string }>()
const visible = defineModel<boolean>({ default: false })

const loading = ref(false)
const projects = ref<ProjectVO[]>([])
const selectedId = ref<number | null>(null)
const newProjectName = ref('')
const creating = ref(false)
const submitting = ref(false)

async function load() {
  loading.value = true
  try {
    projects.value = await listProjects()
  } finally {
    loading.value = false
  }
}

watch(visible, (val) => {
  if (val) {
    selectedId.value = null
    newProjectName.value = ''
    load()
  }
})

async function quickCreate() {
  const name = newProjectName.value.trim()
  if (!name) return
  creating.value = true
  try {
    await createProject({ projectName: name })
    newProjectName.value = ''
    await load()
    const created = projects.value.find((p) => p.projectName === name)
    if (created) {
      selectedId.value = created.id
    }
  } finally {
    creating.value = false
  }
}

async function confirm() {
  if (!selectedId.value) {
    ElMessage.error('请先选择一个项目')
    return
  }
  submitting.value = true
  try {
    await addSessionToProject(selectedId.value, { agentCode: props.agentCode, sessionId: props.sessionId })
    ElMessage.success('已加入项目')
    visible.value = false
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="加入 Project" width="420px">
    <div class="quick-create">
      <el-input v-model="newProjectName" placeholder="新建一个项目…" @keyup.enter="quickCreate" />
      <el-button :loading="creating" @click="quickCreate">新建</el-button>
    </div>

    <el-scrollbar v-loading="loading" max-height="280px" class="project-list">
      <el-empty v-if="!loading && projects.length === 0" description="还没有项目，先新建一个" :image-size="50" />
      <el-radio-group v-else v-model="selectedId" class="project-radio-group">
        <el-radio v-for="p in projects" :key="p.id" :value="p.id" class="project-radio-item">
          <div class="project-radio-content">
            <span class="project-name">{{ p.projectName }}</span>
            <span class="project-count">{{ p.sessionCount }} 条会话</span>
          </div>
        </el-radio>
      </el-radio-group>
    </el-scrollbar>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="confirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.quick-create {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.project-list {
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  padding: 4px 12px;
  background: var(--cw-paper, var(--el-bg-color));
}

.project-radio-group {
  display: flex;
  flex-direction: column;
  width: 100%;
}

.project-radio-item {
  height: auto;
  padding: 8px 0;
  margin-right: 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.project-radio-item:last-child {
  border-bottom: none;
}

.project-radio-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  margin-left: 4px;
}

.project-name {
  font-size: 13px;
  color: var(--el-text-color-primary);
}

.project-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
