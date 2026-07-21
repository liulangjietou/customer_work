<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { copyText } from './composables/useCopy'
import { usePersistedRef } from './composables/useToolStorage'
import {
  detectTimestampUnit,
  formatEpochMs,
  parseLenientDateTime,
  parsedDateTimeToEpochMs,
  resolveLocalTimeZone,
  timestampTextToEpochMs,
} from './composables/timezoneMath'

type TimezoneOption = 'local' | 'Asia/Shanghai' | 'UTC'

const localZoneName = resolveLocalTimeZone()

// ---- 顶部实时走秒的"当前时间戳" ----
const nowSeconds = ref(Math.floor(Date.now() / 1000))
const nowMillis = ref(Date.now())
let tickTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  tickTimer = setInterval(() => {
    nowMillis.value = Date.now()
    nowSeconds.value = Math.floor(nowMillis.value / 1000)
  }, 1000)
})
onUnmounted(() => {
  if (tickTimer) clearInterval(tickTimer)
})

function copyNow(kind: 'seconds' | 'millis') {
  const text = kind === 'seconds' ? String(nowSeconds.value) : String(nowMillis.value)
  copyText(text, kind === 'seconds' ? '当前时间戳（秒）' : '当前时间戳（毫秒）')
}

// ---- 时间戳 <-> 日期时间双向联动 ----
const timestampText = usePersistedRef('timestamp:tsText', '')
const datetimeText = usePersistedRef('timestamp:dtText', '')
const timezone = usePersistedRef<TimezoneOption>('timestamp:tz', 'local')

const epochMs = ref<number | null>(null)
const tsError = ref('')
const dtError = ref('')

const effectiveTimeZone = computed(() => (timezone.value === 'local' ? localZoneName : timezone.value))
const detectedUnit = computed(() => detectTimestampUnit(timestampText.value))

/**
 * 双向联动的核心难点：任一边变化都要"即时更新另一边"，但另一边被程序性赋值又会触发它自己的 watch，
 * 容易形成互相触发的死循环。这里用一个同步标记 + `flush: 'sync'` 的组合解决：程序性写入时先把标记
 * 置真，watch 用同步 flush（赋值语句执行期间就会跑到回调里），回调看到标记为真就直接跳过——
 * 不依赖"值相同就不触发"这种隐式行为，也不依赖定时器时序，逻辑始终确定。
 */
let programmatic = false

function setTimestampTextSilently(v: string) {
  programmatic = true
  timestampText.value = v
  programmatic = false
}
function setDatetimeTextSilently(v: string) {
  programmatic = true
  datetimeText.value = v
  programmatic = false
}

function handleTimestampInput(val: string) {
  if (!val.trim()) {
    tsError.value = ''
    return
  }
  const ms = timestampTextToEpochMs(val)
  if (ms === null) {
    tsError.value = '不是合法的时间戳（应为纯数字，可带负号表示 1970 年之前）'
    return
  }
  tsError.value = ''
  epochMs.value = ms
  dtError.value = ''
  setDatetimeTextSilently(formatEpochMs(ms, effectiveTimeZone.value))
}

function handleDatetimeInput(val: string) {
  if (!val.trim()) {
    dtError.value = ''
    return
  }
  const parsed = parseLenientDateTime(val)
  if (!parsed) {
    dtError.value = '无法识别的日期时间格式，支持 ISO8601（可带 Z/±HH:mm 时区偏移）/ yyyy-MM-dd HH:mm:ss / yyyy-MM-dd'
    return
  }
  dtError.value = ''
  const ms = parsedDateTimeToEpochMs(parsed, effectiveTimeZone.value)
  epochMs.value = ms
  tsError.value = ''
  // 派生回时间戳字段时沿用当前识别到的位数单位（没有则默认秒），避免两个字段间来回跳变位数
  const unit = detectedUnit.value ?? 'seconds'
  setTimestampTextSilently(unit === 'seconds' ? String(Math.round(ms / 1000)) : String(ms))
}

let tsTimer: ReturnType<typeof setTimeout> | null = null
watch(
  timestampText,
  (val) => {
    if (programmatic) return
    if (tsTimer) clearTimeout(tsTimer)
    tsTimer = setTimeout(() => handleTimestampInput(val), 300)
  },
  { flush: 'sync' },
)

let dtTimer: ReturnType<typeof setTimeout> | null = null
watch(
  datetimeText,
  (val) => {
    if (programmatic) return
    if (dtTimer) clearTimeout(dtTimer)
    dtTimer = setTimeout(() => handleDatetimeInput(val), 300)
  },
  { flush: 'sync' },
)

// 时区切换：时间戳（UTC 瞬时值）不变，只需要重新格式化日期时间字段与下方对照表
watch(timezone, () => {
  if (epochMs.value != null) {
    setDatetimeTextSilently(formatEpochMs(epochMs.value, effectiveTimeZone.value))
  }
})

onMounted(() => {
  // 首次打开且两个字段都是空的（无持久化历史），用当前时间预填，避免联动区/对照表一片空白
  if (!timestampText.value && !datetimeText.value) {
    setTimestampTextSilently(String(Math.floor(Date.now() / 1000)))
    handleTimestampInput(timestampText.value)
  } else if (timestampText.value) {
    handleTimestampInput(timestampText.value)
  } else if (datetimeText.value) {
    handleDatetimeInput(datetimeText.value)
  }
})

// ---- 下方多时区对照 ----
const comparisonRows = computed(() => {
  if (epochMs.value === null) return []
  const zones: Array<{ label: string; zone: string }> = [
    { label: `本地（${localZoneName}）`, zone: localZoneName },
    { label: 'UTC', zone: 'UTC' },
    { label: '东八区', zone: 'Asia/Shanghai' },
  ]
  const seen = new Set<string>()
  return zones
    .filter((z) => {
      if (seen.has(z.zone)) return false
      seen.add(z.zone)
      return true
    })
    .map((z) => ({ label: z.label, value: formatEpochMs(epochMs.value as number, z.zone) }))
})
</script>

<template>
  <div class="timestamp-tool">
    <el-card shadow="never" class="now-card">
      <div class="now-row">
        <span class="now-label">当前时间戳（秒）</span>
        <span class="now-value" title="点击复制" @click="copyNow('seconds')">{{ nowSeconds }}</span>
      </div>
      <div class="now-row">
        <span class="now-label">当前时间戳（毫秒）</span>
        <span class="now-value" title="点击复制" @click="copyNow('millis')">{{ nowMillis }}</span>
      </div>
    </el-card>

    <el-form label-width="110px" class="link-form">
      <el-form-item label="时区">
        <el-select v-model="timezone" style="width: 220px">
          <el-option label="本地" value="local" />
          <el-option label="东八区（UTC+8）" value="Asia/Shanghai" />
          <el-option label="UTC" value="UTC" />
        </el-select>
      </el-form-item>

      <el-form-item label="时间戳">
        <div class="field-with-hint">
          <el-input v-model="timestampText" placeholder="输入 10 位秒或 13 位毫秒时间戳" clearable />
          <el-tag v-if="detectedUnit" size="small" type="info" class="unit-tag">
            识别为：{{ detectedUnit === 'seconds' ? '秒' : '毫秒' }}
          </el-tag>
        </div>
        <div v-if="tsError" class="field-error">{{ tsError }}</div>
      </el-form-item>

      <el-form-item label="日期时间">
        <el-input v-model="datetimeText" placeholder="yyyy-MM-dd HH:mm:ss / yyyy-MM-dd / ISO8601" clearable />
        <div v-if="dtError" class="field-error">{{ dtError }}</div>
      </el-form-item>
    </el-form>

    <div class="comparison">
      <div class="comparison-title">多时区对照</div>
      <el-table :data="comparisonRows" size="small" border>
        <el-table-column prop="label" label="时区" width="220" />
        <el-table-column prop="value" label="日期时间" />
      </el-table>
      <el-empty v-if="comparisonRows.length === 0" description="输入合法的时间戳或日期时间后显示对照" :image-size="60" />
    </div>
  </div>
</template>

<style scoped>
.timestamp-tool {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.now-card {
  background: var(--el-fill-color-light);
}

.now-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0;
}

.now-label {
  width: 150px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.now-value {
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 16px;
  font-weight: 600;
  color: var(--theme-primary, var(--el-color-primary));
  cursor: pointer;
  user-select: all;
}

.now-value:hover {
  text-decoration: underline;
}

.link-form {
  max-width: 640px;
}

.field-with-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.unit-tag {
  flex-shrink: 0;
}

.field-error {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-danger);
}

.comparison-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
</style>
