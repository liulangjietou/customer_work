<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useThemeStore } from '@/store/theme'
import type { AgentCallStatsTrendPoint, AgentCallTrendGranularity } from '@/types/api'

// 按需引入：只注册用到的折线图 + 网格/图例/tooltip + Canvas 渲染器，不拉全量 echarts 包体积。
echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  points: AgentCallStatsTrendPoint[]
  granularity: AgentCallTrendGranularity
  loading?: boolean
}>()

const themeStore = useThemeStore()
const chartEl = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

/**
 * 后端未强约束 bucket 的具体字符串格式（day 粒度大概率 yyyy-MM-dd，hour 粒度大概率
 * yyyy-MM-dd HH:00 一类），这里只做保守裁剪去掉年份前缀，超长的（含时间部分）截到分钟位，
 * 不对具体格式做强假设，hover tooltip 里始终展示 bucket 原文兜底。
 */
function shortenBucket(bucket: string): string {
  const trimmed = bucket.length > 10 ? bucket.slice(5, 16) : bucket.slice(5)
  return trimmed || bucket
}

function buildOption() {
  const dark = themeStore.isDark
  const textColor = dark ? '#c9cdd4' : '#606266'
  const axisLineColor = dark ? '#3c3f45' : '#dcdfe6'
  const categories = props.points.map((p) => p.bucket)
  const rotate = categories.length > 12 ? 30 : 0

  return {
    textStyle: { color: textColor },
    tooltip: { trigger: 'axis' },
    legend: {
      data: ['调用量', '平均总耗时', '大模型', '工具', 'MCP', 'Skill'],
      top: 0,
      textStyle: { color: textColor },
    },
    grid: { left: 56, right: 56, top: 44, bottom: rotate ? 56 : 30 },
    xAxis: {
      type: 'category',
      data: categories,
      axisLine: { lineStyle: { color: axisLineColor } },
      axisLabel: { color: textColor, formatter: shortenBucket, rotate },
    },
    yAxis: [
      { type: 'value', name: '调用量', position: 'left', axisLine: { lineStyle: { color: axisLineColor } }, axisLabel: { color: textColor }, splitLine: { lineStyle: { color: axisLineColor, opacity: 0.5 } } },
      { type: 'value', name: '耗时(ms)', position: 'right', axisLine: { lineStyle: { color: axisLineColor } }, axisLabel: { color: textColor }, splitLine: { show: false } },
    ],
    series: [
      { name: '调用量', type: 'line', yAxisIndex: 0, smooth: true, data: props.points.map((p) => p.count) },
      { name: '平均总耗时', type: 'line', yAxisIndex: 1, smooth: true, data: props.points.map((p) => p.avgDurationMs) },
      { name: '大模型', type: 'line', yAxisIndex: 1, smooth: true, data: props.points.map((p) => p.avgModelMs) },
      { name: '工具', type: 'line', yAxisIndex: 1, smooth: true, data: props.points.map((p) => p.avgToolMs) },
      { name: 'MCP', type: 'line', yAxisIndex: 1, smooth: true, data: props.points.map((p) => p.avgMcpMs) },
      { name: 'Skill', type: 'line', yAxisIndex: 1, smooth: true, data: props.points.map((p) => p.avgSkillMs) },
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

// points/granularity 变化（切筛选条件、切粒度）与深浅主题切换都要重绘
watch(() => [props.points, props.granularity, themeStore.isDark], render, { deep: false })
</script>

<template>
  <div v-loading="loading" class="trend-chart-wrap">
    <div v-if="!loading && points.length === 0" class="empty-tip">暂无趋势数据</div>
    <div ref="chartEl" class="trend-chart" />
  </div>
</template>

<style scoped>
.trend-chart-wrap {
  position: relative;
  width: 100%;
  height: 320px;
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
