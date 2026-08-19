<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  clearCacheScope,
  evictCacheEntry,
  listCacheEntries,
  listCacheScopes,
  type SemanticCacheEntry,
  type SemanticCacheScope,
} from '@/api/ops'

// 语义缓存看板：看清楚缓存了什么、哪些真的在被复用、清掉答得不对的。
//
// 按命中次数降序排——命中 0 次的条目只是白占容量，而 MySQL 没有原生向量索引、
// 相似度是在应用层逐条算的，容量越大查缓存越慢。

const loading = ref(false)
const scopesLoading = ref(false)
const list = ref<SemanticCacheEntry[]>([])
const scopes = ref<SemanticCacheScope[]>([])
// 分区键是用户级隔离键（形如 u42），运营手填是猜不出来的，故进页面先把实际存在的分区拉回来。
// 留空而不是预填 'default'：预填一个多半查不到东西的值，只会让人以为"缓存没在工作"。
const scopeId = ref('')

/** 拉分区列表并默认选中条目最多的那个——运营多半就是想看它。 */
async function loadScopes() {
  scopesLoading.value = true
  try {
    scopes.value = await listCacheScopes()
    if (!scopeId.value && scopes.value.length > 0) {
      scopeId.value = scopes.value[0].scopeId
    }
  } finally {
    scopesLoading.value = false
  }
}

async function loadList() {
  // 没有任何分区时不必空跑一次查询
  if (!scopeId.value) {
    list.value = []
    return
  }
  loading.value = true
  try {
    list.value = await listCacheEntries(scopeId.value)
  } finally {
    loading.value = false
  }
}

/** 清空/删除之后分区可能整个消失，得连选择器一起刷新。 */
async function reload() {
  await loadScopes()
  await loadList()
}

function formatTime(ms: number): string {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '-'
}

/** 复用率 = 命中次数 / 条目数：低于 1 说明多数条目从没被用过，缓存在白占容量。 */
const reuseRate = computed(() => {
  if (list.value.length === 0) return 0
  const hits = list.value.reduce((sum, entry) => sum + entry.hitCount, 0)
  return hits / list.value.length
})

const totalHits = computed(() => list.value.reduce((sum, entry) => sum + entry.hitCount, 0))

async function handleEvict(row: SemanticCacheEntry) {
  try {
    await ElMessageBox.confirm(
      `将删除这条缓存，下次问到同类问题会重新调模型。\n\n问题：${row.question}`,
      '删除缓存条目',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
    await evictCacheEntry(row.id)
    ElMessage.success('已删除')
    await reload()
  } catch (error) {
    if (error !== 'cancel') throw error
  }
}

async function handleClear() {
  try {
    await ElMessageBox.confirm(
      '知识库或提示词改过之后，旧答案不再可信，应整体作废。清空后命中率会归零一段时间，属正常。',
      `清空分区 ${scopeId.value} 的全部缓存`,
      { confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning' },
    )
    const removed = await clearCacheScope(scopeId.value)
    ElMessage.success(`已清空 ${removed} 条`)
    // 分区已空，选择器里那一项会消失，得重新挑一个
    scopeId.value = ''
    await reload()
  } catch (error) {
    if (error !== 'cancel') throw error
  }
}

onMounted(reload)
</script>

<template>
  <div class="semantic-cache-board">
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="语义缓存只对与个人上下文无关的通用问答生效"
      description="意图白名单（默认仅 consult）+ 个人标识过滤（问题或答案含 6 位以上连续数字即跳过）。
        两个用户都问「我的订单到哪了」时语义高度相似但答案完全不同，无差别缓存会造成数据泄露。"
    />

    <el-card shadow="never" class="stat-card">
      <div class="stats">
        <div class="stat">
          <div class="stat-value">{{ list.length }}</div>
          <div class="stat-label">缓存条目</div>
        </div>
        <div class="stat">
          <div class="stat-value">{{ totalHits }}</div>
          <div class="stat-label">累计命中（省下的模型调用）</div>
        </div>
        <div class="stat">
          <div class="stat-value" :class="{ 'stat-warn': reuseRate < 1 }">
            {{ reuseRate.toFixed(2) }}
          </div>
          <div class="stat-label">平均复用次数（低于 1 说明多数条目从没被用过）</div>
        </div>
      </div>
    </el-card>

    <el-card shadow="never">
      <div class="toolbar">
        <!-- filterable + allow-create：分区多时能搜，也允许手填一个列表外的分区
             （列表有 100 条上限，长尾分区不在里面） -->
        <el-select
          v-model="scopeId"
          :loading="scopesLoading"
          filterable
          allow-create
          default-first-option
          placeholder="选择分区"
          style="width: 240px"
          @change="loadList"
        >
          <el-option
            v-for="scope in scopes"
            :key="scope.scopeId"
            :label="`${scope.scopeId}（${scope.entries} 条）`"
            :value="scope.scopeId"
          />
        </el-select>
        <el-button type="primary" :loading="loading" @click="loadList">查询</el-button>
        <el-button :loading="scopesLoading" @click="loadScopes">刷新分区</el-button>
        <span v-if="!scopesLoading && scopes.length === 0" class="hint">
          当前还没有任何缓存分区
        </span>
        <div class="spacer" />
        <el-button
          v-permission="'semantic-cache:evict'"
          type="danger"
          plain
          :disabled="!scopeId"
          @click="handleClear"
        >
          清空该分区
        </el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column label="命中" width="90" sortable :sort-by="'hitCount'">
          <template #default="{ row }">
            <el-tag v-if="row.hitCount > 0" type="success">{{ row.hitCount }}</el-tag>
            <el-tag v-else type="info">0</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="intent" label="意图" width="100" />
        <el-table-column prop="question" label="缓存的问题" show-overflow-tooltip />
        <el-table-column prop="answer" label="缓存的答案" show-overflow-tooltip />
        <el-table-column label="写入时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAtMs) }}</template>
        </el-table-column>
        <el-table-column label="最近命中" width="170">
          <template #default="{ row }">{{ formatTime(row.lastHitAtMs) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'semantic-cache:evict'" link type="danger" @click="handleEvict(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && list.length === 0" description="该分区暂无缓存（功能默认关闭，需显式开启）" />
    </el-card>
  </div>
</template>

<style scoped>
.semantic-cache-board {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.stat-card :deep(.el-card__body) {
  padding: 16px 20px;
}

.stats {
  display: flex;
  gap: 48px;
  flex-wrap: wrap;
}

.stat-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
}

.stat-warn {
  color: var(--el-color-warning);
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.stat-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 4px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.spacer {
  flex: 1;
}
</style>
