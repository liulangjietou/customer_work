<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getUnreadMessageCount, markAllMessagesRead, markMessageRead, pageSiteMessages } from '@/api/message'
import type { SiteMessageVO } from '@/types/api'

// 未读数轮询间隔；弹层里只展示最近 N 条（分页第一页），不做"加载更多"——铃铛只是速览入口，
// 完整列表另有站内信管理页（不在本组件职责内）。
const UNREAD_POLL_INTERVAL_MS = 30000
const RECENT_MESSAGE_PAGE_SIZE = 10

const router = useRouter()
const unreadCount = ref(0)
const messages = ref<SiteMessageVO[]>([])
const loading = ref(false)
const popoverVisible = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

/** 拉取未读数刷新徽标；轮询场景静默失败即可，不打扰用户，下一轮会自然重试。 */
async function refreshUnreadCount() {
  try {
    unreadCount.value = await getUnreadMessageCount()
  } catch {
    // 静默忽略：见上方注释
  }
}

/** 弹层展开时加载最近消息（含已读+未读，不筛 readFlag）。 */
async function loadRecentMessages() {
  loading.value = true
  try {
    const page = await pageSiteMessages({ page: 1, size: RECENT_MESSAGE_PAGE_SIZE })
    messages.value = page.list
  } catch (error) {
    ElMessage.error('站内消息加载失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    loading.value = false
  }
}

function handlePopoverShow() {
  loadRecentMessages()
}

/** 点击某条消息：未读则先标记已读并刷新未读数；有跳转链接的再关闭弹层并导航。 */
async function handleMessageClick(msg: SiteMessageVO) {
  if (msg.readFlag === 0) {
    try {
      await markMessageRead(msg.id)
      msg.readFlag = 1
      await refreshUnreadCount()
    } catch (error) {
      ElMessage.error('标记已读失败：' + (error instanceof Error ? error.message : String(error)))
      return
    }
  }
  if (msg.link) {
    popoverVisible.value = false
    router.push(msg.link)
  }
}

async function handleMarkAllRead() {
  try {
    await markAllMessagesRead()
    messages.value.forEach((m) => { m.readFlag = 1 })
    await refreshUnreadCount()
    ElMessage.success('已全部标记已读')
  } catch (error) {
    ElMessage.error('操作失败：' + (error instanceof Error ? error.message : String(error)))
  }
}

/**
 * 相对时间展示（刚刚/n 分钟前/n 小时前/n 天前，超过 7 天直接显示日期）。
 * 兼容后端可能返回的 "yyyy-MM-dd HH:mm:ss" 与 ISO "yyyy-MM-ddTHH:mm:ss" 两种时间字符串——
 * 前者在部分浏览器里 `new Date()` 无法正确解析，统一补 T 再解析；解析失败原样兜底返回。
 */
function formatRelativeTime(createTime: string): string {
  const normalized = createTime.includes('T') ? createTime : createTime.replace(' ', 'T')
  const parsed = new Date(normalized)
  if (Number.isNaN(parsed.getTime())) {
    return createTime
  }
  const diffMinutes = Math.floor((Date.now() - parsed.getTime()) / 60000)
  if (diffMinutes < 1) return '刚刚'
  if (diffMinutes < 60) return `${diffMinutes} 分钟前`
  const diffHours = Math.floor(diffMinutes / 60)
  if (diffHours < 24) return `${diffHours} 小时前`
  const diffDays = Math.floor(diffHours / 24)
  if (diffDays < 7) return `${diffDays} 天前`
  return parsed.toLocaleDateString()
}

onMounted(() => {
  refreshUnreadCount()
  pollTimer = setInterval(refreshUnreadCount, UNREAD_POLL_INTERVAL_MS)
})

onUnmounted(() => {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<template>
  <el-popover
    v-model:visible="popoverVisible"
    trigger="click"
    width="360"
    placement="bottom-end"
    popper-class="notification-popover"
    @show="handlePopoverShow"
  >
    <template #reference>
      <el-badge :value="unreadCount" :max="99" :hidden="unreadCount === 0" class="notification-badge">
        <el-button class="bell-btn" text title="站内消息">
          <el-icon :size="18"><Bell /></el-icon>
        </el-button>
      </el-badge>
    </template>
    <div class="notification-panel" v-loading="loading">
      <div class="notification-header">
        <span class="notification-title">站内消息</span>
        <el-button link type="primary" size="small" :disabled="unreadCount === 0" @click="handleMarkAllRead">
          全部已读
        </el-button>
      </div>
      <el-empty v-if="!loading && messages.length === 0" description="暂无消息" :image-size="50" />
      <el-scrollbar v-else max-height="360px">
        <div
          v-for="msg in messages"
          :key="msg.id"
          class="notification-item"
          @click="handleMessageClick(msg)"
        >
          <span class="unread-dot" :class="{ 'is-visible': msg.readFlag === 0 }" />
          <div class="notification-item-body">
            <div class="notification-item-title" :class="{ 'is-unread': msg.readFlag === 0 }">{{ msg.title }}</div>
            <div class="notification-item-content">{{ msg.content }}</div>
            <div class="notification-item-time">{{ formatRelativeTime(msg.createTime) }}</div>
          </div>
        </div>
      </el-scrollbar>
    </div>
  </el-popover>
</template>

<style scoped>
.notification-badge {
  display: inline-flex;
  align-items: center;
}

.bell-btn {
  font-size: 18px;
  color: var(--el-text-color-regular);
  padding: 8px;
}

.bell-btn:hover {
  color: var(--el-color-primary);
  background: var(--el-fill-color-light);
}

.notification-panel {
  min-height: 80px;
}

.notification-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-weight: 600;
}

.notification-title {
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.notification-item {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}

.notification-item:last-child {
  border-bottom: none;
}

.notification-item:hover {
  background: var(--el-fill-color-light);
}

.unread-dot {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--el-color-danger);
  visibility: hidden;
}

.unread-dot.is-visible {
  visibility: visible;
}

.notification-item-body {
  flex: 1;
  min-width: 0;
}

.notification-item-title {
  font-size: 13px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notification-item-title.is-unread {
  font-weight: 600;
}

.notification-item-content {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notification-item-time {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin-top: 2px;
}
</style>
