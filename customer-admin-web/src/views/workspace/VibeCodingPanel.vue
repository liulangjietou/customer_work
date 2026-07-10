<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, type UploadRequestOptions } from 'element-plus'
import { listWorkspaceFiles, readWorkspaceFileContent, saveWorkspaceFileContent, streamVibeCoding } from '@/api/vibecoding'
import { getChatSessionMessages, parseChatAttachment } from '@/api/chat'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import TraceTimeline, { type TraceNode } from '@/components/TraceTimeline.vue'
import ChatHistorySidebar from '@/components/ChatHistorySidebar.vue'
import ThemeToolbar from '@/components/ThemeToolbar.vue'
import { useThemeStore } from '@/store/theme'
import { generateUuid } from '@/utils/uuid'
import type { WorkspaceFileContent, WorkspaceFileNode } from '@/types/api'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'

const props = defineProps<{ agentCode: string }>()

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  nodes: TraceNode[]
}

/** 追加一个执行轨迹节点：连续的 thinking 是同一段思考内容的增量分片，合并进上一个节点而不是各占一条；
 * 其余节点类型（开始思考/调用大模型/调用 Skill 等）后端已经做过去重，各自独立成一条时间线项。 */
function appendNode(msg: ChatMessage, kind: string, text: string) {
  const last = msg.nodes[msg.nodes.length - 1]
  if (last && last.kind === 'thinking' && kind === 'thinking') {
    last.text += text
  } else {
    msg.nodes.push({ kind, text })
  }
}

interface Attachment {
  name: string
  content: string
}

const sessionId = ref(generateUuid())
const messages = ref<ChatMessage[]>([])
const input = ref('')
const streaming = ref(false)
const historyLoading = ref(false)
const uploading = ref(false)
const attachments = ref<Attachment[]>([])
const scrollRef = ref<HTMLElement>()
const historySidebar = ref<InstanceType<typeof ChatHistorySidebar>>()
let abortStream: (() => void) | null = null
const themeStore = useThemeStore()

// 目录树相关
const fileNodes = ref<WorkspaceFileNode[]>([])
const filesLoading = ref(false)
const filesLoaded = ref(false)

// 文件预览抽屉
const previewVisible = ref(false)
const previewLoading = ref(false)
const previewFile = ref<WorkspaceFileContent | null>(null)
const previewCodeRef = ref<HTMLElement>()
// 编辑模式
const editMode = ref(false)
const editContent = ref('')
const saving = ref(false)

function newSession() {
  abortStream?.()
  streaming.value = false
  sessionId.value = generateUuid()
  messages.value = []
  input.value = ''
  attachments.value = []
  fileNodes.value = []
  filesLoaded.value = false
}

async function handleAttachmentUpload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    const file = options.file as File
    const content = await parseChatAttachment(props.agentCode, file)
    attachments.value.push({ name: file.name, content })
  } catch (error) {
    ElMessage.error('附件解析失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    uploading.value = false
  }
}

function removeAttachment(index: number) {
  attachments.value.splice(index, 1)
}

function buildMessageWithAttachments(text: string): string {
  if (attachments.value.length === 0) return text
  const attachmentText = attachments.value
    .map((a) => `【附件：${a.name}】\n---\n${a.content}\n---`)
    .join('\n\n')
  return `${attachmentText}\n\n${text}`
}

async function openSession(targetSessionId: string) {
  if (streaming.value) return
  abortStream?.()
  historyLoading.value = true
  try {
    const history = await getChatSessionMessages(props.agentCode, targetSessionId)
    sessionId.value = targetSessionId
    messages.value = history.map((msg) => ({ role: msg.role, text: msg.text, nodes: [] }))
    input.value = ''
    fileNodes.value = []
    filesLoaded.value = false
    scrollToBottom()
  } catch (error) {
    ElMessage.error('历史会话加载失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    historyLoading.value = false
  }
}

function scrollToBottom() {
  nextTick(() => {
    scrollRef.value?.scrollTo({ top: scrollRef.value.scrollHeight, behavior: 'smooth' })
  })
}

function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  const messageToSend = buildMessageWithAttachments(text)
  const attachedNames = attachments.value.map((a) => a.name)
  messages.value.push({
    role: 'user',
    text: attachedNames.length > 0 ? `${text}\n📎 ${attachedNames.join('、')}` : text,
    nodes: [],
  })
  messages.value.push({ role: 'assistant', text: '', nodes: [] })
  const assistantMessage = messages.value[messages.value.length - 1]
  input.value = ''
  attachments.value = []
  streaming.value = true
  scrollToBottom()

  abortStream = streamVibeCoding(props.agentCode, { sessionId: sessionId.value, message: messageToSend }, {
    onEvent: (event) => {
      if (event.event === 'done') {
        streaming.value = false
        // 对话结束后自动刷新文件目录树
        loadFiles()
        return
      }
      if (event.event.startsWith('node:')) {
        appendNode(assistantMessage, event.event.slice('node:'.length), event.data)
      } else {
        assistantMessage.text += event.data
      }
      scrollToBottom()
    },
    onError: (error) => {
      streaming.value = false
      ElMessage.error('对话失败：' + (error instanceof Error ? error.message : String(error)))
    },
    onComplete: () => {
      streaming.value = false
      historySidebar.value?.refresh()
    },
  })
}

/** 加载（刷新）会话 workspace 目录树。 */
async function loadFiles() {
  filesLoading.value = true
  try {
    fileNodes.value = await listWorkspaceFiles(props.agentCode, sessionId.value)
    filesLoaded.value = true
  } catch (error) {
    ElMessage.error('目录加载失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    filesLoading.value = false
  }
}

/** 点击文件节点，打开预览抽屉并加载内容。 */
async function openFilePreview(node: WorkspaceFileNode) {
  if (node.directory) return
  previewVisible.value = true
  previewLoading.value = true
  previewFile.value = null
  editMode.value = false
  editContent.value = ''
  try {
    previewFile.value = await readWorkspaceFileContent(props.agentCode, sessionId.value, node.relativePath)
    // 等 DOM 更新后触发代码高亮
    await nextTick()
    highlightPreview()
  } catch (error) {
    ElMessage.error('文件读取失败：' + (error instanceof Error ? error.message : String(error)))
    previewVisible.value = false
  } finally {
    previewLoading.value = false
  }
}

/** 进入编辑模式。 */
function enterEditMode() {
  if (!previewFile.value || previewFile.value.truncated) return
  editContent.value = previewFile.value.content
  editMode.value = true
}

/** 取消编辑，还原到预览模式。 */
async function cancelEdit() {
  editMode.value = false
  editContent.value = ''
  await nextTick()
  highlightPreview()
}

/** 保存编辑内容到服务端文件。 */
async function saveEdit() {
  if (!previewFile.value) return
  saving.value = true
  try {
    await saveWorkspaceFileContent(props.agentCode, {
      sessionId: sessionId.value,
      relativePath: previewFile.value.relativePath,
      content: editContent.value,
    })
    // 更新本地缓存，切回预览模式并重新高亮
    previewFile.value = { ...previewFile.value, content: editContent.value }
    editMode.value = false
    ElMessage.success('保存成功')
    await nextTick()
    highlightPreview()
  } catch (error) {
    ElMessage.error('保存失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    saving.value = false
  }
}

function highlightPreview() {
  if (previewCodeRef.value && previewFile.value && !previewFile.value.truncated) {
    previewCodeRef.value.removeAttribute('data-highlighted')
    previewCodeRef.value.className = `language-${previewFile.value.language}`
    hljs.highlightElement(previewCodeRef.value)
  }
}

onMounted(() => {
  themeStore.apply()
})

onUnmounted(() => {
  abortStream?.()
})
</script>

<template>
  <div class="vibecoding-panel">
    <!-- 左列：对话区 -->
    <div class="chat-column">
      <div class="panel-header">
        <ThemeToolbar :on-new-session="newSession" />
      </div>
      <div ref="scrollRef" class="messages" v-loading="historyLoading">
        <div v-for="(msg, index) in messages" :key="index" class="message-row" :class="msg.role">
          <div class="bubble">
            <TraceTimeline
              v-if="msg.role === 'assistant' && msg.nodes.length > 0"
              :nodes="msg.nodes"
              :active="streaming && index === messages.length - 1 && !msg.text"
            />
            <MarkdownRenderer v-if="msg.role === 'assistant'" :text="msg.text" />
            <template v-else>{{ msg.text }}</template>
            <span v-if="msg.role === 'assistant' && !msg.text && msg.nodes.length === 0 && streaming && index === messages.length - 1">生成中…</span>
          </div>
        </div>
        <el-empty v-if="messages.length === 0" description="描述你想让智能体生成/修改的代码" />
      </div>
      <div v-if="attachments.length > 0" class="attachment-tags">
        <el-tag v-for="(a, idx) in attachments" :key="idx" closable size="small" @close="removeAttachment(idx)">
          📎 {{ a.name }}
        </el-tag>
      </div>
      <div class="input-bar">
        <el-upload :show-file-list="false" :http-request="handleAttachmentUpload" accept=".md,.txt">
          <el-button :loading="uploading" :disabled="streaming" title="上传 .md/.txt 附件，随消息一起发给智能体">
            <el-icon><Paperclip /></el-icon>
          </el-button>
        </el-upload>
        <el-input v-model="input" placeholder="描述需求，回车发送" :disabled="streaming" @keyup.enter="send" />
        <el-button type="primary" :loading="streaming" @click="send">发送</el-button>
      </div>
    </div>

    <!-- 中列：产物文件树 -->
    <div class="artifacts-column">
      <div class="artifacts-header">
        <span>产物文件</span>
        <el-button link type="primary" :loading="filesLoading" @click="loadFiles">刷新</el-button>
      </div>

      <!-- 空状态 -->
      <el-empty
        v-if="!filesLoaded"
        description="对话结束后自动刷新"
        :image-size="50"
      />
      <el-empty
        v-else-if="filesLoaded && fileNodes.length === 0"
        description="本次会话暂无产出文件"
        :image-size="50"
      />

      <!-- 目录树 -->
      <el-scrollbar v-else height="100%">
        <el-tree
          :data="fileNodes"
          :props="{ label: 'name', children: 'children', isLeaf: (n: WorkspaceFileNode) => !n.directory }"
          node-key="relativePath"
          default-expand-all
          highlight-current
          @node-click="openFilePreview"
        >
          <template #default="{ node, data }">
            <span class="tree-node">
              <el-icon v-if="data.directory" style="margin-right:4px;color:#e6a23c"><Folder /></el-icon>
              <el-icon v-else style="margin-right:4px;color:var(--theme-primary, #409eff)"><Document /></el-icon>
              <span :title="data.relativePath">{{ node.label }}</span>
            </span>
          </template>
        </el-tree>
      </el-scrollbar>
    </div>

    <!-- 右列：历史会话 -->
    <div class="history-column">
      <ChatHistorySidebar ref="historySidebar" :agent-code="agentCode" :active-session-id="sessionId" @select="openSession" />
    </div>

    <!-- 文件内容预览抽屉 -->
    <el-drawer
      v-model="previewVisible"
      direction="rtl"
      size="55%"
      :destroy-on-close="false"
    >
      <!-- 自定义标题：文件路径 + 右侧预览/编辑按鈕 -->
      <template #header>
        <div class="drawer-header">
          <span class="drawer-title">{{ previewFile?.relativePath ?? '文件预览' }}</span>
          <div class="drawer-actions">
            <template v-if="!editMode">
              <el-button
                size="small"
                :disabled="!previewFile || previewFile.truncated"
                :title="previewFile?.truncated ? '文件过大，不支持编辑' : '编辑文件'"
                @click="enterEditMode"
              >
                <el-icon style="margin-right:4px"><Edit /></el-icon>编辑
              </el-button>
            </template>
            <template v-else>
              <el-button size="small" @click="cancelEdit" :disabled="saving">取消</el-button>
              <el-button size="small" type="primary" :loading="saving" @click="saveEdit">保存</el-button>
            </template>
          </div>
        </div>
      </template>

      <div v-if="previewLoading" class="preview-loading">
        <el-icon class="is-loading" :size="24"><Loading /></el-icon>
        <span>加载中…</span>
      </div>
      <div v-else-if="previewFile">
        <div v-if="previewFile.truncated" class="preview-truncated">
          {{ previewFile.content }}
        </div>
        <!-- 预览模式 -->
        <el-scrollbar v-else-if="!editMode" height="calc(100vh - 120px)">
          <pre class="code-block"><code ref="previewCodeRef" :class="`language-${previewFile.language}`">{{ previewFile.content }}</code></pre>
        </el-scrollbar>
        <!-- 编辑模式 -->
        <el-scrollbar v-else height="calc(100vh - 120px)">
          <textarea
            v-model="editContent"
            class="code-editor"
            spellcheck="false"
            :placeholder="'请输入文件内容…'"
          />
        </el-scrollbar>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.vibecoding-panel {
  display: flex;
  gap: 16px;
  height: 60vh;
}

.chat-column {
  flex: 2;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background-color: var(--theme-page-bg, #fff);
  border-radius: 8px;
  padding: 12px;
  transition: background-color 0.3s ease;
}

.panel-header {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  border-radius: 6px;
}

.attachment-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.message-row {
  display: flex;
  margin-bottom: 12px;
}

.message-row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 90%;
  padding: 10px 14px;
  border-radius: 8px;
  word-break: break-word;
}

.message-row.user .bubble {
  background: var(--theme-primary, #409eff);
  color: #fff;
  white-space: pre-wrap;
}

.message-row.user .bubble:hover {
  background: var(--theme-primary-light, #79bbff);
}

.message-row.assistant .bubble {
  background: #f0f2f5;
  color: #333;
}

.input-bar {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.input-bar :deep(.el-button--primary) {
  background-color: var(--theme-primary, #409eff);
  border-color: var(--theme-primary, #409eff);
}

.input-bar :deep(.el-button--primary:hover) {
  background-color: var(--theme-primary-light, #79bbff);
  border-color: var(--theme-primary-light, #79bbff);
}

/* 产物文件树列 */
.artifacts-column {
  flex: 1;
  border-left: 1px solid #eee;
  padding-left: 16px;
  display: flex;
  flex-direction: column;
  min-width: 200px;
  overflow: hidden;
}

.artifacts-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 600;
  flex-shrink: 0;
}

.tree-node {
  display: flex;
  align-items: center;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 160px;
}

.tree-node:hover {
  color: var(--theme-primary, #409eff);
}

/* 历史会话列 */
.history-column {
  flex: 1;
  border-left: 1px solid #eee;
  padding-left: 16px;
  min-width: 180px;
}

/* 预览抽屉 */
.preview-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 24px;
  color: #909399;
}

.preview-truncated {
  padding: 16px;
  background: #f5f7fa;
  border-radius: 4px;
  color: #909399;
  font-size: 13px;
}

.code-block {
  margin: 0;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
}

.code-block code {
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
}

/* 抽屉标题行 */
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 12px;
}

.drawer-title {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

/* 编辑器文本域 */
.code-editor {
  width: 100%;
  height: calc(100vh - 130px);
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  padding: 12px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  resize: none;
  outline: none;
  background: #fafafa;
  color: #303133;
  box-sizing: border-box;
}

.code-editor:focus {
  border-color: var(--theme-primary, #409eff);
  background: #fff;
}
</style>
