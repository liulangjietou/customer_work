<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useThemeStore } from '@/store/theme'
import { readThemeChartPalette } from '@/utils/themeChartPalette'
import type { ContentGuardCountVO } from '@/types/api'

// 按需引入：只注册柱状图 + 网格/tooltip + Canvas 渲染器，不拉全量 echarts 包体积
// （与 AgentCallTrendChart 同款做法）。
echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  points: ContentGuardCountVO[]
  /** 后端按查询区间跨度决定的粒度：day / hour，仅用于 X 轴标签裁剪 */
  granularity: string
  loading?: boolean
}>()

const themeStore = useThemeStore()
const chartEl = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

/** 标签裁掉年份前缀：day 粒度形如 2026-07-28，hour 粒度形如 2026-07-28 14:00。 */
function shortenLabel(label: string): string {
  return label.length > 5 ? label.slice(5) : label
}

function buildOption() {
  const palette = readThemeChartPalette()
  const categories = props.points.map((p) => p.label)
  const rotate = categories.length > 12 ? 30 : 0

  return {
    color: palette.series,
    textStyle: { color: palette.text },
    tooltip: {
      trigger: 'axis',
      backgroundColor: palette.tooltipBackground,
      borderColor: palette.tooltipBorder,
      textStyle: { color: palette.text },
    },
    grid: { left: 56, right: 24, top: 24, bottom: rotate ? 56 : 30 },
    xAxis: {
      type: 'category',
      data: categories,
      axisLine: { lineStyle: { color: palette.axis } },
      axisLabel: { color: palette.text, formatter: shortenLabel, rotate },
    },
    yAxis: {
      type: 'value',
      name: '命中数',
      axisLine: { lineStyle: { color: palette.axis } },
      axisLabel: { color: palette.text },
      splitLine: { lineStyle: { color: palette.grid } },
    },
    series: [{
      name: '命中数',
      type: 'bar',
      barMaxWidth: 32,
      itemStyle: { color: palette.series[0], borderRadius: [5, 5, 0, 0] },
      data: props.points.map((p) => p.total),
    }],
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

// Canvas 不会自动消费 CSS 变量；同明暗主题之间切换也必须重绘。
watch(
  () => [props.points, props.granularity, themeStore.primaryColor, themeStore.mode, themeStore.systemDark],
  render,
  { deep: false },
)
</script>

<template>
  <div v-loading="loading" class="trend-chart-wrap">
    <div v-if="!loading && points.length === 0" class="empty-tip">暂无命中数据</div>
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
