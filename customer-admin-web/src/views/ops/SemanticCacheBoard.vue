<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useQueryState } from '@/composables/useQueryState'
import { useAuthStore } from '@/store/auth'
import CrudLoadState from '@/components/CrudLoadState.vue'
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

const scopeId = ref('')
const auth = useAuthStore()
const { data: scopes, loading: scopesLoading, error: scopesError, loaded: scopesLoaded, load: queryScopes } =
  useQueryState<SemanticCacheScope[]>(() => listCacheScopes(), () => [])
const { data: list, loading, error: listError, loaded, load: loadList } = useQueryState<SemanticCacheEntry[]>(
  () => scopeId.value ? listCacheEntries(scopeId.value) : Promise.resolve([]), () => [],
)
const loadError = computed(() => scopesError.value || listError.value)
watch([() => auth.loginGeneration, () => auth.token, () => auth.permissions.join('\0')], () => {
  scopeId.value = ''
}, { flush: 'sync' })

/** 只有本次分区读取成功才继续加载条目，避免错误被当作没有分区。 */
async function reload() {
  const rows = await queryScopes()
  if (!rows) return
  if (!scopeId.value) scopeId.value = rows[0]?.scopeId ?? ''
  await loadList()
}

function formatTime(ms: number): string {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '-'
}

const totalHits = computed(() => list.value.reduce((sum, entry) => sum + entry.hitCount, 0))
const zeroHitCount = computed(() => list.value.filter((entry) => entry.hitCount === 0).length)

async function handleEvict(row: SemanticCacheEntry) {
  try {
    await ElMessageBox.confirm(
      `将删除这条缓存，下次问到同类问题会重新调模型。\n\n问题：${row.question}`,
      '删除缓存条目',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
    await evictCacheEntry(row.id)
    ElMessage.success('已删除')
    // 列表只返回前 50 条；仅当当前结果确实只有这一条时，才可判定分区已被删空。
    if (list.value.length === 1) {
      scopeId.value = ''
    }
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
    <CrudLoadState :error="loadError" :has-stale-data="loaded" :loading="loading || scopesLoading" @retry="reload" />
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="语义缓存只对与个人上下文无关的通用问答生效"
      description="意图白名单（默认仅 consult）+ 个人标识过滤（问题或答案含 6 位以上连续数字即跳过）。
        两个用户都问「我的订单到哪了」时语义高度相似但答案完全不同，无差别缓存会造成数据泄露。"
    />

    <div v-if="!loadError || loaded" class="stats" v-loading="loading">
      <div class="stat">
        <div class="stat-value">{{ list.length }}</div>
        <div class="stat-label">当前列表条目</div>
      </div>
      <div class="stat">
        <div class="stat-value">{{ totalHits }}</div>
        <div class="stat-label">当前列表累计命中</div>
      </div>
      <div class="stat">
        <div class="stat-value" :class="{ 'stat-warn': zeroHitCount > 0 }">{{ zeroHitCount }}</div>
        <div class="stat-label">当前列表零命中条目</div>
      </div>
    </div>

    <el-card shadow="never" class="filter-card">
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
        <el-button :loading="scopesLoading" @click="reload">刷新分区</el-button>
        <span v-if="scopesLoaded && !scopesError && !scopesLoading && scopes.length === 0" class="hint">
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
    </el-card>

    <el-card shadow="never" class="list-card">
      <div class="section-heading">
        <strong>缓存证据明细</strong>
        <span>按实际命中次数识别有效复用与长期占用容量的低价值条目</span>
      </div>
      <el-table v-if="!loadError || loaded" v-loading="loading" :data="list" style="width: 100%" empty-text="该分区暂无缓存（功能默认关闭，需显式开启）">
        <el-table-column label="命中" width="90" sortable :sort-by="'hitCount'">
          <template #default="{ row }">
            <el-tag v-if="row.hitCount > 0" type="success">{{ row.hitCount }}</el-tag>
            <el-tag v-else type="info">0</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="intent" label="意图" width="100" />
        <el-table-column prop="question" label="缓存的问题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="answer" label="缓存的答案" min-width="260" show-overflow-tooltip />
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


    </el-card>
  </div>
</template>

<style scoped>
.semantic-cache-board {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(180px, 1fr));
  gap: 12px;
}

.stat {
  min-height: 88px;
  padding: 15px 16px 14px;
  border: 1px solid var(--cw-line);
  border-radius: var(--cw-radius-md);
  background: var(--cw-paper);
  box-shadow: var(--cw-shadow-xs);
}

.stat-value {
  font-size: 26px;
  font-weight: 720;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.stat-warn {
  color: var(--cw-amber);
}

.hint {
  color: var(--cw-text-muted);
  font-size: 12px;
}

.stat-label {
  color: var(--cw-text-muted);
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

.filter-card .toolbar {
  margin-bottom: 0;
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.spacer {
  flex: 1;
}

.section-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.section-heading strong {
  color: var(--cw-text);
  font-size: 14px;
  font-weight: 700;
}

.section-heading span {
  color: var(--cw-text-muted);
  font-size: 12px;
  text-align: right;
}

@media (max-width: 767px) {
  .stats {
    grid-template-columns: 1fr 1fr;
  }

  .spacer {
    display: none;
  }

  .section-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .section-heading span {
    text-align: left;
  }
}

@media (max-width: 480px) {
  .stats {
    grid-template-columns: 1fr;
  }
}
</style>
