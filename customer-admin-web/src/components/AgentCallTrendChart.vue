<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useThemeStore } from '@/store/theme'
import { readThemeChartPalette } from '@/utils/themeChartPalette'
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
  const palette = readThemeChartPalette()
  const categories = props.points.map((p) => p.bucket)
  const rotate = categories.length > 12 ? 30 : 0
  const linePatterns = ['solid', 'solid', 'dashed', 'dotted', 'dashed', 'dotted', 'solid'] as const
  const symbols = ['circle', 'rect', 'triangle', 'diamond', 'roundRect', 'pin', 'arrow'] as const

  return {
    color: palette.series,
    textStyle: { color: palette.text },
    tooltip: {
      trigger: 'axis',
      backgroundColor: palette.tooltipBackground,
      borderColor: palette.tooltipBorder,
      textStyle: { color: palette.text },
    },
    legend: {
      // Token 量纲（可达数万）与耗时 ms 不同，单列一条曲线绑独立右侧 Y 轴，默认不选中避免首屏喧宾夺主，
      // 用户按需点亮；调用量/耗时各占一条 Y 轴。
      data: ['调用量', '平均总耗时', '大模型', '工具', 'MCP', 'Skill', 'Token'],
      selected: { Token: false },
      top: 0,
      textStyle: { color: palette.text },
    },
    grid: { left: 56, right: 88, top: 44, bottom: rotate ? 56 : 30 },
    xAxis: {
      type: 'category',
      data: categories,
      axisLine: { lineStyle: { color: palette.axis } },
      axisLabel: { color: palette.text, formatter: shortenBucket, rotate },
    },
    yAxis: [
      { type: 'value', name: '调用量', position: 'left', axisLine: { lineStyle: { color: palette.axis } }, axisLabel: { color: palette.text }, splitLine: { lineStyle: { color: palette.grid } } },
      { type: 'value', name: '耗时(ms)', position: 'right', axisLine: { lineStyle: { color: palette.axis } }, axisLabel: { color: palette.text }, splitLine: { show: false } },
      { type: 'value', name: 'Token', position: 'right', offset: 52, axisLine: { lineStyle: { color: palette.axis } }, axisLabel: { color: palette.text }, splitLine: { show: false } },
    ],
    series: [
      { name: '调用量', type: 'line', yAxisIndex: 0, smooth: true, data: props.points.map((p) => p.count) },
      { name: '平均总耗时', type: 'line', yAxisIndex: 1, smooth: true, data: props.points.map((p) => p.avgDurationMs) },
      { name: '大模型', type: 'line', yAxisIndex: 1, smooth: true, data: props.points.map((p) => p.avgModelMs) },
      { name: '工具', type: 'line', yAxisIndex: 1, smooth: true, data: props.points.map((p) => p.avgToolMs) },
      { name: 'MCP', type: 'line', yAxisIndex: 1, smooth: true, data: props.points.map((p) => p.avgMcpMs) },
      { name: 'Skill', type: 'line', yAxisIndex: 1, smooth: true, data: props.points.map((p) => p.avgSkillMs) },
      { name: 'Token', type: 'line', yAxisIndex: 2, smooth: true, data: props.points.map((p) => p.totalTokens) },
    ].map((series, index) => ({
      ...series,
      symbol: symbols[index],
      symbolSize: 6,
      lineStyle: { type: linePatterns[index], width: index === 0 ? 2.5 : 2 },
    })),
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

// Canvas 不会自动消费 CSS 变量；数据、主题色或系统明暗变化时都必须重绘。
watch(
  () => [props.points, props.granularity, themeStore.primaryColor, themeStore.mode, themeStore.systemDark],
  render,
  { deep: false },
)
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
