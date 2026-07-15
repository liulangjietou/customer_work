<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, type UploadRequestOptions } from 'element-plus'
import {
  generateCommitMessage,
  generatePrDescription,
  getGitDiffSummary,
  getSandboxMode,
  interruptVibeCoding,
  listWorkspaceFiles,
  readWorkspaceFileContent,
  saveWorkspaceFileContent,
  streamVibeCoding,
} from '@/api/vibecoding'
import { getChatSessionMessages, parseChatAttachment } from '@/api/chat'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import TraceTimeline, { type TraceNode } from '@/components/TraceTimeline.vue'
import ChatHistorySidebar from '@/components/ChatHistorySidebar.vue'
import { useThemeStore } from '@/store/theme'
import { generateUuid } from '@/utils/uuid'
import type { FileChangeEvent, GitDiffSummary, WorkspaceFileContent, WorkspaceFileNode } from '@/types/api'
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
const interrupting = ref(false) // 已点终止，等后端真正停下来（协作式中断，不保证立即生效）
const interrupted = ref(false) // 上一轮是被终止结束的，可以点"继续"续跑挂起的工具调用
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

// 沙箱模式（local/docker，全局配置，进面板时查一次即可，不随会话变化）
const sandboxMode = ref<'local' | 'docker' | null>(null)

// 实时文件变更时间线（本轮对话内累积，切会话/新建会话时清空）
const fileChanges = ref<Array<FileChangeEvent & { time: number }>>([])

// Git 助手抽屉
const gitDrawerVisible = ref(false)
const gitDiffLoading = ref(false)
const gitDiff = ref<GitDiffSummary | null>(null)
const commitStyle = ref<'conventional' | 'simple'>('conventional')
const commitLoading = ref(false)
const commitMessageText = ref('')
const prLoading = ref(false)
const prDescriptionText = ref('')

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
  interrupting.value = false
  interrupted.value = false
  sessionId.value = generateUuid()
  messages.value = []
  input.value = ''
  attachments.value = []
  fileNodes.value = []
  filesLoaded.value = false
  fileChanges.value = []
  // 新会话会用当前全局配置，不是上一个（可能是历史会话解析出的）沙箱模式
  loadCurrentSandboxMode()
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
  interrupting.value = false
  interrupted.value = false
  historyLoading.value = true
  try {
    const history = await getChatSessionMessages(props.agentCode, targetSessionId)
    sessionId.value = targetSessionId
    messages.value = history.map((msg) => ({ role: msg.role, text: msg.text, nodes: [] }))
    input.value = ''
    fileNodes.value = []
    filesLoaded.value = false
    fileChanges.value = []
    // 标签要反映"这条会话当时真正用的模式"，从首条用户消息里解析；更早期没有该前缀的历史记录
    // 解析不出来，此时不展示误导性的标签（不回退成当前全局配置，两者含义不同不能互相替代）
    const firstUserMessage = history.find((msg) => msg.role === 'user')
    sandboxMode.value = firstUserMessage ? parseSandboxModeFromMessage(firstUserMessage.text) : null
    scrollToBottom()
    // 切到历史会话时该会话可能已有产物文件，无需等用户手动点“刷新”
    loadFiles()
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
  interrupted.value = false
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
      if (event.event === 'file_change') {
        handleFileChange(event.data)
        return
      }
      if (event.event.startsWith('node:')) {
        appendNode(assistantMessage, event.event.slice('node:'.length), event.data)
      } else if (event.event === 'message') {
        assistantMessage.text += event.data
      }
      // 其余未知事件静默忽略：后端新增 SSE 事件类型（如 test_report/plan）时旧前端不受影响，
      // 避免把结构化 JSON 拼进对话正文（需求 §5.5 向后兼容）
      scrollToBottom()
    },
    onError: (error) => {
      streaming.value = false
      interrupting.value = false
      ElMessage.error('对话失败：' + (error instanceof Error ? error.message : String(error)))
    },
    onComplete: () => {
      streaming.value = false
      // 若这轮是用户主动点了"终止"后自然结束的，翻转成"可继续"状态，冒出继续按钮
      if (interrupting.value) {
        interrupting.value = false
        interrupted.value = true
      }
      historySidebar.value?.refresh()
    },
  })
}

/** 点击"终止"：只通知后端安全中断（协作式，不保证立即生效），不调 abortStream() 断开前端连接——
 * 让现有的 onComplete/onError 在后端真正停止、SSE 自然结束时收尾，避免界面显示"已停止"但后端其实
 * 还在跑的假象。 */
async function handleInterrupt() {
  interrupting.value = true
  try {
    await interruptVibeCoding(props.agentCode, sessionId.value)
  } catch (error) {
    interrupting.value = false
    ElMessage.error('终止失败：' + (error instanceof Error ? error.message : String(error)))
  }
}

/** 点击"继续"：发一句非空续接文案触发框架续跑被打断的挂起工具调用（后端 ChatRequest.message 要求非空，
 * 且续跑逻辑本就挂在正常的 stream 调用里，无需专门的续跑接口）。 */
function resumeInterrupted() {
  interrupted.value = false
  input.value = '请继续刚才的任务。'
  send()
}

/** 解析 file_change SSE 事件，追加到变更时间线（不按路径去重，同一文件多次改动各自成一条，还原真实操作顺序）。 */
function handleFileChange(raw: string) {
  try {
    const parsed = JSON.parse(raw) as FileChangeEvent
    fileChanges.value.push({ ...parsed, time: Date.now() })
  } catch {
    // 解析失败不影响主对话流程，静默丢弃
  }
}

/** 打开 Git 助手抽屉，并立即加载一次 diff 摘要。 */
async function openGitAssistant() {
  gitDrawerVisible.value = true
  gitDiff.value = null
  commitMessageText.value = ''
  prDescriptionText.value = ''
  await loadGitDiff()
}

async function loadGitDiff() {
  gitDiffLoading.value = true
  try {
    gitDiff.value = await getGitDiffSummary(props.agentCode, sessionId.value)
  } catch (error) {
    ElMessage.error('diff 摘要加载失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    gitDiffLoading.value = false
  }
}

async function handleGenerateCommitMessage() {
  commitLoading.value = true
  try {
    const res = await generateCommitMessage(props.agentCode, { sessionId: sessionId.value, style: commitStyle.value })
    commitMessageText.value = res.message
  } catch (error) {
    ElMessage.error('commit message 生成失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    commitLoading.value = false
  }
}

async function handleGeneratePrDescription() {
  prLoading.value = true
  try {
    const res = await generatePrDescription(props.agentCode, sessionId.value)
    prDescriptionText.value = res.description
  } catch (error) {
    ElMessage.error('PR description 生成失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    prLoading.value = false
  }
}

async function copyToClipboard(text: string, label: string) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.error('复制失败，请手动选择文本复制')
  }
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

/**
 * 当前全局沙箱配置（admin.sandbox.mode），代表"新会话将会使用的模式"。
 * 仅用于 newSession/挂载时的预览——一旦切到某条历史会话，标签要改成从那条会话消息里解析出的
 * "当时真正用的模式"，不能一直显示"现在的全局配置"，否则历史记录和当前配置不一致时会互相矛盾
 * （比如切到一条 local 时期的历史记录，标题却显示当前是 docker，误导用户以为这条记录也在容器里）。
 */
function loadCurrentSandboxMode() {
  getSandboxMode(props.agentCode)
    .then((res) => { sandboxMode.value = res.mode })
    .catch(() => { sandboxMode.value = null })
}

/** 从会话首条用户消息里解析出发送时用的沙箱模式，解析不出来（更早期版本的历史记录）返回 null。 */
function parseSandboxModeFromMessage(text: string): 'local' | 'docker' | null {
  const match = text.match(/^\[VibeCoding指引-(docker|local)]/)
  return match ? (match[1] as 'local' | 'docker') : null
}

onMounted(() => {
  themeStore.apply()
  loadCurrentSandboxMode()
})

onUnmounted(() => {
  abortStream?.()
})

// newSession 供 WorkspaceView 上提后的工具栏"新建会话"按钮按激活 Tab 分发调用
defineExpose({ newSession })
</script>

<template>
  <div class="vibecoding-panel">
    <!-- 左列：对话区 -->
    <div class="chat-column">
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
        <el-button v-if="!streaming" type="primary" @click="send">发送</el-button>
        <el-button v-else type="danger" :loading="interrupting" @click="handleInterrupt">
          {{ interrupting ? '终止中…' : '终止' }}
        </el-button>
        <el-button v-if="interrupted && !streaming" link type="primary" @click="resumeInterrupted">继续</el-button>
      </div>
    </div>

    <!-- 中列：产物文件树 -->
    <div class="artifacts-column">
      <div class="artifacts-header">
        <span>
          产物文件
          <el-tag v-if="sandboxMode" size="small" :type="sandboxMode === 'docker' ? 'warning' : 'info'" class="sandbox-mode-tag">
            {{ sandboxMode === 'docker' ? 'docker' : 'local' }}
          </el-tag>
        </span>
        <div class="artifacts-header-actions">
          <el-button link type="primary" @click="openGitAssistant">Git 助手</el-button>
          <el-button link type="primary" :loading="filesLoading" @click="loadFiles">刷新</el-button>
        </div>
      </div>

      <!-- 实时文件变更时间线 -->
      <div v-if="fileChanges.length > 0" class="file-change-timeline">
        <div class="file-change-timeline-title">本轮变更</div>
        <el-scrollbar max-height="120px">
          <div v-for="(fc, idx) in fileChanges" :key="idx" class="file-change-item">
            <el-icon v-if="fc.operation === 'CREATE'" style="color:#67c23a"><CirclePlus /></el-icon>
            <el-icon v-else-if="fc.operation === 'MODIFY'" style="color:#e6a23c"><EditPen /></el-icon>
            <el-icon v-else style="color:#f56c6c"><Delete /></el-icon>
            <span class="file-change-path" :title="fc.path">{{ fc.path }}</span>
          </div>
        </el-scrollbar>
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

    <!-- Git 助手抽屉 -->
    <el-drawer v-model="gitDrawerVisible" direction="rtl" size="45%" title="Git 助手">
      <div class="git-assistant">
        <div class="git-section">
          <div class="git-section-header">
            <span>变更摘要</span>
            <el-button link type="primary" :loading="gitDiffLoading" @click="loadGitDiff">刷新</el-button>
          </div>
          <div v-if="gitDiffLoading" class="git-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>加载中…</span>
          </div>
          <template v-else-if="gitDiff">
            <p class="git-summary-text">{{ gitDiff.summary }}</p>
            <div v-if="gitDiff.changedFiles.length > 0" class="git-changed-files">
              <el-tag v-for="f in gitDiff.changedFiles" :key="f" size="small" class="git-changed-file-tag">{{ f }}</el-tag>
            </div>
          </template>
        </div>

        <el-divider />

        <div class="git-section">
          <div class="git-section-header">
            <span>Commit Message</span>
            <el-radio-group v-model="commitStyle" size="small">
              <el-radio-button value="conventional">Conventional</el-radio-button>
              <el-radio-button value="simple">Simple</el-radio-button>
            </el-radio-group>
          </div>
          <el-button type="primary" size="small" :loading="commitLoading" @click="handleGenerateCommitMessage">生成</el-button>
          <el-input
            v-if="commitMessageText"
            v-model="commitMessageText"
            type="textarea"
            :rows="3"
            readonly
            class="git-result-text"
          />
          <el-button v-if="commitMessageText" link type="primary" @click="copyToClipboard(commitMessageText, 'commit message')">
            复制
          </el-button>
        </div>

        <el-divider />

        <div class="git-section">
          <div class="git-section-header">
            <span>PR Description</span>
          </div>
          <el-button type="primary" size="small" :loading="prLoading" @click="handleGeneratePrDescription">生成</el-button>
          <div v-if="prDescriptionText" class="git-pr-description">
            <MarkdownRenderer :text="prDescriptionText" />
          </div>
          <el-button v-if="prDescriptionText" link type="primary" @click="copyToClipboard(prDescriptionText, 'PR description')">
            复制
          </el-button>
        </div>
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

/* :not(.is-link) 排除"继续"链接按钮——link 按钮的文字色本就用的是同一个主题蓝（靠透明背景显色），
   这条规则如果连它一起覆盖成纯色背景，文字会跟背景同色而"隐形"。 */
.input-bar :deep(.el-button--primary:not(.is-link)) {
  background-color: var(--theme-primary, #409eff);
  border-color: var(--theme-primary, #409eff);
}

.input-bar :deep(.el-button--primary:not(.is-link):hover) {
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

.artifacts-header-actions {
  display: flex;
  gap: 4px;
}

.sandbox-mode-tag {
  margin-left: 6px;
  font-weight: normal;
  vertical-align: middle;
}

/* 实时文件变更时间线 */
.file-change-timeline {
  flex-shrink: 0;
  margin-bottom: 8px;
  padding: 6px 8px;
  background: #f5f7fa;
  border-radius: 6px;
}

.file-change-timeline-title {
  font-size: 12px;
  font-weight: 600;
  color: #909399;
  margin-bottom: 4px;
}

.file-change-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  padding: 2px 0;
}

.file-change-path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Git 助手抽屉 */
.git-assistant {
  padding: 0 4px;
}

.git-section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 600;
}

.git-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #909399;
  font-size: 13px;
}

.git-summary-text {
  font-size: 13px;
  line-height: 1.6;
  color: #303133;
  margin: 0 0 8px;
}

.git-changed-files {
  margin-bottom: 8px;
}

.git-changed-file-tag {
  margin: 2px;
}

.git-result-text {
  margin: 8px 0;
}

.git-pr-description {
  margin: 8px 0;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 6px;
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
