<script setup lang="ts">
// 待发送区附件条：Chat/VibeCoding 两个面板共用，替换原先单纯的 📎 文件名 el-tag 循环。
// 图片附件显示本地 objectURL 缩略图（零后端请求，见 useChatAttachments 里 uploadFile 的即时创建），
// 点击 el-image 走大图预览；非图片保持原有芯片样式；上传中/失败态与原逻辑一致。
import { isImageMimeType } from '@/utils/attachment'

export interface PendingAttachmentVM {
  localId: string
  name: string
  status: 'uploading' | 'success' | 'failed'
  errorMessage?: string
  mimeType?: string
  /** 图片附件的本地 objectURL，非图片恒为 undefined。 */
  previewUrl?: string
}

defineProps<{ attachments: PendingAttachmentVM[] }>()
const emit = defineEmits<{ remove: [localId: string] }>()
</script>

<template>
  <div class="attachment-pending-list">
    <div
      v-for="a in attachments"
      :key="a.localId"
      class="pending-item"
    >
      <!-- 图片：缩略图 + 悬浮移除按钮 + 上传中遮罩 -->
      <div
        v-if="isImageMimeType(a.mimeType) && a.previewUrl"
        class="pending-thumb"
        :class="{ 'is-failed': a.status === 'failed' }"
        :title="a.status === 'failed' ? a.errorMessage : a.name"
      >
        <el-image
          :src="a.previewUrl"
          :preview-src-list="a.status === 'uploading' ? [] : [a.previewUrl]"
          fit="cover"
          class="pending-thumb-img"
          preview-teleported
        />
        <div v-if="a.status === 'uploading'" class="pending-thumb-loading">
          <el-icon class="is-loading"><Loading /></el-icon>
        </div>
        <button
          v-if="a.status !== 'uploading'"
          type="button"
          class="pending-thumb-remove"
          title="移除"
          @click.stop="emit('remove', a.localId)"
        >
          <el-icon><Close /></el-icon>
        </button>
      </div>
      <!-- 非图片：原有芯片样式 -->
      <el-tag
        v-else
        :closable="a.status !== 'uploading'"
        :type="a.status === 'failed' ? 'danger' : undefined"
        size="small"
        :title="a.status === 'failed' ? a.errorMessage : undefined"
        @close="emit('remove', a.localId)"
      >
        <el-icon v-if="a.status === 'uploading'" class="is-loading"><Loading /></el-icon>
        📎 {{ a.name }}
      </el-tag>
    </div>
  </div>
</template>

<style scoped>
.attachment-pending-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.pending-item {
  display: flex;
  align-items: center;
}

.pending-thumb {
  position: relative;
  width: 44px;
  height: 44px;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
}

.pending-thumb.is-failed {
  border-color: var(--el-color-danger);
}

.pending-thumb-img {
  width: 100%;
  height: 100%;
  cursor: pointer;
}

.pending-thumb-img :deep(img) {
  object-fit: cover;
}

.pending-thumb-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.35);
  color: #fff;
}

.pending-thumb-remove {
  position: absolute;
  top: -1px;
  right: -1px;
  width: 16px;
  height: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 0 0 0 6px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  cursor: pointer;
  padding: 0;
  font-size: 10px;
  line-height: 1;
}

.pending-thumb-remove:hover {
  color: var(--cw-on-danger, #fff);
  background: var(--cw-danger-solid, #c2414b);
}

.pending-thumb-remove .el-icon {
  font-size: 10px;
}
</style>
