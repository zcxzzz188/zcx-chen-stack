<template>
  <div class="user-activity-chart">
    <el-skeleton v-if="loading" :rows="4" animated />
    <div v-show="!loading" class="chart-shell">
      <div ref="chartRef" class="chart-container"></div>
      <div class="chart-center-content">
        <span class="chart-center-label">活跃率</span>
        <span class="chart-center-value">{{ activeRateText }}</span>
      </div>
      <div v-if="safeTotal === 0" class="chart-empty-state">暂无用户数据</div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Chart } from '@antv/g2'
import { useDarkStore } from '@/stores/darkStore'
import { storeToRefs } from 'pinia'

const props = defineProps({
  activeUserCount: {
    type: Number,
    default: 0,
  },
  totalUserCount: {
    type: Number,
    default: 0,
  },
  loading: {
    type: Boolean,
    default: false,
  },
})

const darkStore = useDarkStore()
const { isDark } = storeToRefs(darkStore)

const chartRef = ref(null)
let chartInstance = null

const safeTotal = computed(() => Math.max(props.totalUserCount || 0, 0))
const safeActive = computed(() => Math.min(Math.max(props.activeUserCount || 0, 0), safeTotal.value))
const inactiveCount = computed(() => Math.max(safeTotal.value - safeActive.value, 0))
const activeRateText = computed(() => {
  if (safeTotal.value === 0) {
    return '0.0%'
  }
  return `${((safeActive.value / safeTotal.value) * 100).toFixed(1)}%`
})

const renderChart = () => {
  if (!chartRef.value) return

  if (chartInstance) {
    chartInstance.destroy()
    chartInstance = null
  }

  if (safeTotal.value === 0) {
    return
  }

  const data = [{ item: '活跃用户', count: safeActive.value }]
  if (inactiveCount.value > 0) {
    data.push({ item: '非活跃用户', count: inactiveCount.value })
  }

  chartInstance = new Chart({
    container: chartRef.value,
    autoFit: true,
  })

  chartInstance.options({
    type: 'interval',
    data,
    coordinate: { type: 'theta', innerRadius: 0.6, outerRadius: 0.95 },
    transform: [{ type: 'stackY' }],
    encode: {
      y: 'count',
      color: 'item',
    },
    theme: isDark.value ? 'classicDark' : 'light',
    legend: {
      color: {
        position: 'bottom',
        itemLabelFill: isDark.value ? '#e2e8f0' : '#475569',
      },
    },
    labels: [],
    interaction: {
      elementHoverScale: true,
    },
    tooltip: {
      title: 'item',
      items: [
        (datum) => ({
          name: '用户数',
          value: datum.count,
        }),
        (datum) => ({
          name: '占比',
          value: `${safeTotal.value > 0 ? ((datum.count / safeTotal.value) * 100).toFixed(2) : '0.00'}%`,
        }),
      ],
    },
    scale: {
      color: {
        range: ['#3b82f6', '#94a3b8'],
      },
    },
    animate: { enter: { type: 'waveIn', duration: 800 } },
  })

  chartInstance.render()
}

const destroyChart = () => {
  if (chartInstance) {
    chartInstance.destroy()
    chartInstance = null
  }
}

onMounted(() => {
  renderChart()
})

onBeforeUnmount(() => {
  destroyChart()
})

watch(
  () => [props.activeUserCount, props.totalUserCount, props.loading],
  async () => {
    if (!props.loading) {
      await nextTick()
      renderChart()
    } else {
      destroyChart()
    }
  },
)

watch(isDark, () => {
  if (!props.loading) {
    renderChart()
  }
})
</script>

<style lang="scss" scoped>
.user-activity-chart {
  width: 100%;
  height: 100%;
  min-height: 240px;

  .chart-shell {
    position: relative;
    width: 100%;
    height: 100%;
    min-height: 240px;
  }

  .chart-container {
    width: 100%;
    height: 100%;
    min-height: 240px;
  }

  .chart-center-content {
    position: absolute;
    top: 50%;
    left: 50%;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    transform: translate(-50%, -50%);
    pointer-events: none;
  }

  .chart-center-label {
    font-size: 13px;
    color: var(--el-text-color-secondary);
    letter-spacing: 0.08em;
  }

  .chart-center-value {
    font-size: 24px;
    font-weight: 700;
    color: var(--el-text-color-primary);
    line-height: 1;
  }

  .chart-empty-state {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    padding-top: 48px;
    font-size: 14px;
    color: var(--el-text-color-secondary);
    pointer-events: none;
  }
}
</style>
