<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useThemeStore } from '@/store/theme'
import type { EvalRun } from '@/api/eval'

// 按需引入：只注册折线图 + 网格/图例/tooltip + Canvas 渲染器，不拉全量 echarts
// （与 ContentGuardTrendChart / AgentCallTrendChart 同款做法）。
echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  /** 后端给的是时间倒序（最新在前），画图要正序 */
  runs: EvalRun[]
  loading?: boolean
  primaryLabel: string
  secondaryLabel: string
}>()

const themeStore = useThemeStore()
const chartEl = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

/** 时间戳压成 MM-DD HH:mm：同一天可能跑好几次，只到日期会挤成一堆同名刻度。 */
function formatTime(ms: number): string {
  const d = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function toPercent(value: number): number {
  return Number((value * 100).toFixed(2))
}

function buildOption() {
  const dark = themeStore.isDark
  const textColor = dark ? '#c9cdd4' : '#606266'
  const axisLineColor = dark ? '#3c3f45' : '#dcdfe6'
  // 后端时间倒序，反转成正序才是一条从左到右的时间线
  const ordered = [...props.runs].reverse()
  const categories = ordered.map((run) => formatTime(run.createdAtMs))
  const rotate = categories.length > 12 ? 30 : 0

  return {
    textStyle: { color: textColor },
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => `${v}%` },
    legend: { textStyle: { color: textColor }, top: 0 },
    grid: { left: 56, right: 24, top: 36, bottom: rotate ? 56 : 30 },
    xAxis: {
      type: 'category',
      data: categories,
      axisLine: { lineStyle: { color: axisLineColor } },
      axisLabel: { color: textColor, rotate },
    },
    yAxis: {
      type: 'value',
      // 指标都已归一化到 0-1，这里统一按百分比展示，两类评测共用同一把尺子
      max: 100,
      min: 0,
      name: '%',
      axisLine: { lineStyle: { color: axisLineColor } },
      axisLabel: { color: textColor },
      splitLine: { lineStyle: { color: axisLineColor, opacity: 0.5 } },
    },
    series: [
      {
        name: props.primaryLabel,
        type: 'line',
        smooth: true,
        symbolSize: 7,
        data: ordered.map((run) => toPercent(run.primaryMetric)),
      },
      {
        name: props.secondaryLabel,
        type: 'line',
        smooth: true,
        symbolSize: 7,
        data: ordered.map((run) => toPercent(run.secondaryMetric)),
      },
    ],
  }
}

function render() {
  if (!chart) return
  chart.setOption(buildOption(), true)
}

function handleResize() {
  chart?.resize()
}

onMounted(() => {
  if (!chartEl.value) return
  chart = echarts.init(chartEl.value)
  render()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
  chart = null
})

// 数据变化与深浅主题切换都要重绘
watch(() => [props.runs, themeStore.isDark], render, { deep: false })
</script>

<template>
  <div v-loading="loading" class="trend-chart-wrap">
    <div v-if="!loading && runs.length === 0" class="empty-tip">
      还没有评测记录，点"立即评测"跑第一次，之后每次运行都会自动与上一版对比
    </div>
    <div ref="chartEl" class="trend-chart" />
  </div>
</template>

<style scoped>
.trend-chart-wrap {
  position: relative;
  width: 100%;
  height: 280px;
}

.trend-chart {
  width: 100%;
  height: 100%;
}

.empty-tip {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-placeholder);
  font-size: 13px;
  z-index: 1;
}
</style>
