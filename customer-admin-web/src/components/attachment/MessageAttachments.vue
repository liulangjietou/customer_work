<script setup lang="ts">
// 消息附件区（用户气泡下方）：Chat/VibeCoding 两个面板共用，服务两种来源——
// 刚发送成功的消息（attachments[].previewUrl 是本地 objectURL，零请求直接展示）与
// 历史消息（后端只给 id/mimeType/fileSize/parseStatus，图片缩略图要按需拉一次原文件 blob）。
// 非图片一律芯片，点击弹窗看解析出的文本；所有附件都带下载按钮（blob + a[download]，见 downloadChatAttachment）。
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { downloadChatAttachment, fetchChatAttachmentFile, getChatAttachmentDetail } from '@/api/chat'
import { formatFileSize, isImageMimeType, type MessageAttachmentVM } from '@/utils/attachment'

const props = defineProps<{ agentCode: string; attachments: MessageAttachmentVM[] }>()

// 后端 blob 懒加载出的图片 objectURL：key 是 attachment.id。ownedIds 记录"这个 URL 是本组件自己
// createObjectURL 出来的"——只有这些才在组件卸载时 revoke；props 传入的 previewUrl 归消息对象所有，
// 不属于本组件的生命周期管理范围（发送方 store 已经把所有权从待发送区转移过来了）。
const resolvedUrls = reactive<Record<string, string>>({})
const loadingIds = reactive(new Set<string>())
const failedIds = reactive(new Set<string>())
const ownedIds = new Set<string>()

function imageSrc(a: MessageAttachmentVM): string | undefined {
  return a.previewUrl ?? resolvedUrls[a.id]
}

async function loadImage(a: MessageAttachmentVM) {
  if (a.previewUrl || resolvedUrls[a.id] || loadingIds.has(a.id)) {
    return
  }
  loadingIds.add(a.id)
  try {
    const blob = await fetchChatAttachmentFile(props.agentCode, a.id)
    resolvedUrls[a.id] = URL.createObjectURL(blob)
    ownedIds.add(a.id)
  } catch {
    failedIds.add(a.id)
  } finally {
    loadingIds.delete(a.id)
  }
}

onMounted(() => {
  for (const a of props.attachments) {
    if (isImageMimeType(a.mimeType)) {
      loadImage(a)
    }
  }
})

onUnmounted(() => {
  for (const id of ownedIds) {
    URL.revokeObjectURL(resolvedUrls[id])
  }
})

/** 同一条消息内的多张图片共享一份大图预览列表，可左右切换。 */
const imagePreviewList = computed(() =>
  props.attachments
    .filter((a) => isImageMimeType(a.mimeType))
    .map((a) => imageSrc(a))
    .filter((url): url is string => !!url),
)

function imagePreviewIndex(a: MessageAttachmentVM): number {
  const src = imageSrc(a)
  return src ? imagePreviewList.value.indexOf(src) : 0
}

// 非图片附件文本预览弹窗（调详情接口拿解析出的 content）
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detailFileName = ref('')
const detailContent = ref('')

async function openDetail(a: MessageAttachmentVM) {
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  detailContent.value = ''
  detailFileName.value = a.fileName
  try {
    const result = await getChatAttachmentDetail(props.agentCode, a.id)
    if (result.parseStatus === 'FAILED') {
      detailError.value = result.errorMessage || '解析失败，无可预览的文本内容'
    } else {
      detailContent.value = result.content
    }
  } catch (error) {
    detailError.value = error instanceof Error ? error.message : String(error)
  } finally {
    detailLoading.value = false
  }
}

async function handleDownload(a: MessageAttachmentVM) {
  try {
    await downloadChatAttachment(props.agentCode, a.id, a.fileName)
  } catch {
    // 失败原因（网络异常/后端业务异常）已由 request.ts 拦截器统一 ElMessage 提示，这里只吞掉避免未处理的 rejection
  }
}
</script>

<template>
  <div class="message-attachments">
    <template v-for="a in attachments" :key="a.id">
      <!-- 图片：缩略图 + 悬浮下载按钮 -->
      <div v-if="isImageMimeType(a.mimeType)" class="msg-attach-image">
        <el-image
          v-if="imageSrc(a)"
          :src="imageSrc(a)"
          :preview-src-list="imagePreviewList"
          :initial-index="imagePreviewIndex(a)"
          fit="cover"
          class="msg-attach-image-img"
          preview-teleported
        />
        <div v-else-if="loadingIds.has(a.id)" class="msg-attach-image-placeholder">
          <el-icon class="is-loading"><Loading /></el-icon>
        </div>
        <div v-else class="msg-attach-image-placeholder" :title="failedIds.has(a.id) ? '原图加载失败' : ''">
          <el-icon><Picture /></el-icon>
        </div>
        <button type="button" class="msg-attach-download-btn" title="下载原文件" @click.stop="handleDownload(a)">
          <el-icon><Download /></el-icon>
        </button>
      </div>
      <!-- 非图片：芯片，点击看解析文本，右侧下载 -->
      <el-tag
        v-else
        :type="a.parseStatus === 'FAILED' ? 'danger' : undefined"
        size="small"
        class="msg-attach-chip"
        @click="openDetail(a)"
      >
        <el-icon><Document /></el-icon>
        <span class="msg-attach-chip-name">{{ a.fileName }}</span>
        <span v-if="a.fileSize" class="msg-attach-chip-size">{{ formatFileSize(a.fileSize) }}</span>
        <el-icon class="msg-attach-chip-download" title="下载" @click.stop="handleDownload(a)"><Download /></el-icon>
      </el-tag>
    </template>

    <!-- 文本类附件解析内容预览弹窗 -->
    <el-dialog v-model="detailVisible" :title="detailFileName" width="600px" append-to-body>
      <div v-if="detailLoading" class="msg-attach-detail-loading">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>加载中…</span>
      </div>
      <el-alert v-else-if="detailError" type="error" :closable="false" :title="detailError" show-icon />
      <el-scrollbar v-else max-height="60vh">
        <pre class="msg-attach-detail-content">{{ detailContent || '（无解析内容）' }}</pre>
      </el-scrollbar>
    </el-dialog>
  </div>
</template>

<style scoped>
.message-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.msg-attach-image {
  position: relative;
  width: 96px;
  height: 96px;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
}

.msg-attach-image-img {
  width: 100%;
  height: 100%;
  cursor: pointer;
}

.msg-attach-image-img :deep(img) {
  object-fit: cover;
}

.msg-attach-image-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  font-size: 20px;
}

.msg-attach-download-btn {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  cursor: pointer;
  padding: 0;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.msg-attach-image:hover .msg-attach-download-btn {
  opacity: 1;
}

.msg-attach-chip {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 220px;
}

.msg-attach-chip-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.msg-attach-chip-size {
  flex-shrink: 0;
  opacity: 0.7;
  font-size: 11px;
}

.msg-attach-chip-download {
  flex-shrink: 0;
  margin-left: 2px;
}

.msg-attach-chip-download:hover {
  color: var(--theme-primary, var(--el-color-primary));
}

.msg-attach-detail-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 0;
  color: var(--el-text-color-secondary);
}

.msg-attach-detail-content {
  margin: 0;
  padding: 4px 2px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--el-text-color-primary);
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
}
</style>
