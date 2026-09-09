<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import {
  deleteMyMemories,
  fetchMemoryConsent,
  fetchMyMemories,
  updateMemoryConsent,
} from '@/api/privacy'
import type { MemoryConsent, MemoryList } from '@/types/api'

/**
 * 个性化记忆的授权与管理。
 *
 * 生产强制 consent-required，服务端查不到同意记录时 fail-closed。此前后端这套接口
 * （查询/授权/查看/导出/撤回）全部齐备，而 H5 没有任何一处调用它——
 * 结果是每个真实用户的长期记忆写入都走空、召回都返回空串，且不报任何错。
 * 这个页面补的就是那半个开关：用户侧的授权入口。
 */
const router = useRouter()

const consent = ref<MemoryConsent | null>(null)
const memories = ref<MemoryList | null>(null)
const loading = ref(true)
const loadFailed = ref(false)
const updating = ref(false)

const granted = computed(() => consent.value?.granted === true)

async function loadAll() {
  loading.value = true
  loadFailed.value = false
  try {
    consent.value = await fetchMemoryConsent()
    // 未授权时后端本就没有可展示的内容，不必多发一次请求
    memories.value = consent.value.granted ? await fetchMyMemories() : null
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)

async function onToggle(next: boolean) {
  if (updating.value) {
    return
  }
  // 撤回是真删除，不是只关一个开关位，必须让用户明确知道
  if (!next) {
    try {
      await showConfirmDialog({
        title: '关闭个性化记忆',
        message: '关闭后将删除已记住的全部内容，且无法恢复。之后的对话不再跨会话记住你的偏好。',
        confirmButtonText: '关闭并删除',
        cancelButtonText: '再想想',
      })
    } catch {
      return
    }
  }
  updating.value = true
  try {
    consent.value = await updateMemoryConsent(next)
    memories.value = next ? await fetchMyMemories() : null
    showToast(next ? '已开启个性化记忆' : '已关闭并删除记忆')
  } catch {
    // request 层已提示具体错误，这里只需把开关拨回真实状态
    await loadAll()
  } finally {
    updating.value = false
  }
}

async function onClear() {
  try {
    await showConfirmDialog({
      title: '清空记忆',
      message: '将删除已记住的全部内容，但保留开启状态，之后的对话会重新开始积累。',
      confirmButtonText: '清空',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteMyMemories()
    memories.value = await fetchMyMemories()
    showToast('已清空')
  } catch {
    // 同上，错误提示由 request 层统一处理
  }
}
</script>

<template>
  <div class="privacy-page">
    <van-nav-bar title="个性化记忆" left-arrow @click-left="router.back()" />

    <div v-if="loading" class="state-block">
      <van-loading size="20">加载中…</van-loading>
    </div>

    <div v-else-if="loadFailed" class="state-block">
      <p>加载失败</p>
      <van-button size="small" @click="loadAll">重试</van-button>
    </div>

    <template v-else>
      <van-cell-group inset title="授权">
        <van-cell center title="记住我的偏好">
          <template #label>
            开启后，客服助手会跨会话记住你的常用信息（如常用收货地址、偏好的沟通方式），
            下次不用重复说明。关闭即删除。
          </template>
          <template #right-icon>
            <van-switch
              :model-value="granted"
              :loading="updating"
              size="22"
              aria-label="个性化记忆开关"
              @update:model-value="onToggle"
            />
          </template>
        </van-cell>
      </van-cell-group>

      <van-cell-group v-if="granted" inset title="已记住的内容">
        <van-cell v-if="!memories || memories.count === 0" title="暂无内容">
          <template #label>随着对话进行会逐步积累。</template>
        </van-cell>
        <template v-else>
          <van-cell v-for="(item, i) in memories.memories" :key="'m' + i" :title="item" />
          <van-cell v-for="(item, i) in memories.facts" :key="'f' + i" :title="item">
            <template #right-icon><van-tag type="primary" plain>事实</van-tag></template>
          </van-cell>
        </template>
      </van-cell-group>

      <div v-if="granted && memories && memories.count > 0" class="actions">
        <van-button block plain type="danger" @click="onClear">清空已记住的内容</van-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.privacy-page {
  min-height: 100vh;
  background: var(--van-background, #f7f8fa);
  padding-bottom: 24px;
}

.state-block {
  padding: 48px 16px;
  text-align: center;
  color: #969799;
}

.actions {
  padding: 16px;
}
</style>
