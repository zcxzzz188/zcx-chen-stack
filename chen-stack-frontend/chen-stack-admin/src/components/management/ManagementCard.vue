<template>
  <div class="management-container">
    <div class="card">
      <!-- 标题行 -->
      <div class="card-title-row">
        <h2 class="card-title" :style="{ '--title-color': titleColor }">{{ title }}</h2>
      </div>

      <!-- 筛选区 -->
      <div class="card-filters">
        <slot name="filters" />
        <TimeRangePicker v-if="showTimeFilter" v-model:start-time="startTime" v-model:end-time="endTime" @change="handleTimeChange" />
      </div>

      <!-- 批量操作区（可选） -->
      <div v-if="$slots['batch-actions']" class="card-batch">
        <slot name="batch-actions" />
      </div>

      <!-- 表格视图 -->
      <div class="desktop-view">
        <slot name="table-view" />
      </div>

      <!-- 分页（可选） -->
      <Pagination v-if="showPagination" v-model:current-page="currentPage" v-model:page-size="pageSize" :total="total" @size-change="handleSizeChange" @current-change="handleCurrentChange" />
    </div>

    <!-- 额外插槽（对话框等） -->
    <slot />
  </div>
</template>

<script setup>
/**
 * 管理卡片组件
 *
 * 功能说明：
 * - 统一管理页面布局：标题、筛选区、表格视图、分页
 * - 可选时间范围筛选器（通过 showTimeFilter 控制，默认不显示）
 * - 可选分页（通过 showPagination 控制，默认显示）
 * - 支持批量操作区域
 *
 * 插槽说明：
 * - filters: 筛选器插槽（审核状态下拉、关键词搜索等）
 * - table-view: 桌面端表格内容
 * - batch-actions: 批量操作按钮区
 *
 * 使用方式：
 * ```vue
 * <ManagementCard title="用户管理" :showTimeFilter="true" :total="100" @search="fetchData">
 *   <template #filters>
 *     <ExamineStatusSelect v-model="searchExamineStatus" />
 *     <KeywordSearch v-model="searchKeyword" />
 *   </template>
 *   <template #table-view>
 *     <DataTable :data="tableData" />
 *   </template>
 * </ManagementCard>
 * ```
 */

import { ref, watch, nextTick } from 'vue'
import Pagination from '@/components/data/Pagination.vue'
import TimeRangePicker from '@/components/search/TimeRangePicker.vue'

// Props
const props = defineProps({
  title: {
    type: String,
    default: '管理页面',
  },
  titleColor: {
    type: String,
    default: 'var(--admin-primary)',
  },
  showTimeFilter: {
    type: Boolean,
    default: false,
  },
  showPagination: {
    type: Boolean,
    default: true,
  },
  modelCurrentPage: {
    type: Number,
    default: 1,
  },
  modelPageSize: {
    type: Number,
    default: 10,
  },
  total: {
    type: Number,
    default: 0,
  },
})

// Emits
const emit = defineEmits(['update:modelCurrentPage', 'update:modelPageSize', 'search', 'time-change'])

// 分页
const currentPage = ref(props.modelCurrentPage)
const pageSize = ref(props.modelPageSize)

// 时间筛选
const startTime = ref('')
const endTime = ref('')

// 监听 props 并同步到内部状态
watch(
  () => [props.modelCurrentPage, props.modelPageSize],
  ([newPage, newSize]) => {
    currentPage.value = newPage
    pageSize.value = newSize
  },
)

// 分页处理
const handleSizeChange = async (size) => {
  pageSize.value = size
  currentPage.value = 1
  emit('update:modelPageSize', size)
  emit('update:modelCurrentPage', 1)
  await nextTick()
  emit('search')
}

const handleCurrentChange = async (current) => {
  currentPage.value = current
  emit('update:modelCurrentPage', current)
  emit('update:modelPageSize', pageSize.value)
  await nextTick()
  emit('search')
}

// 时间筛选变化
const handleTimeChange = () => {
  emit('time-change', { startTime: startTime.value, endTime: endTime.value })
}

// 公开方法
const resetPagination = () => {
  currentPage.value = 1
  emit('update:modelCurrentPage', 1)
}

// 暴露给父组件
defineExpose({
  resetPagination,
  getCurrentPage: () => currentPage.value,
  getPageSize: () => pageSize.value,
  getTimeRange: () => ({ startTime: startTime.value, endTime: endTime.value }),
})

</script>

<style lang="scss" scoped>
.management-container {
  height: 100%;
  position: relative;
}

.card {
  height: 100%;
  padding: 20px;
  display: flex;
  flex-direction: column;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  transition: box-shadow 0.3s ease;
  overflow: hidden;

  &:hover {
    box-shadow: 0 8px 30px rgba(0, 0, 0, 0.12);
  }
}

.card-title-row {
  margin-bottom: 12px;
}

.card-title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
  display: flex;
  align-items: center;

  &::before {
    content: '';
    display: inline-block;
    width: 4px;
    height: 20px;
    background-color: var(--title-color, var(--admin-primary));
    border-radius: 2px;
    margin-right: 10px;
  }
}

.card-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  margin-bottom: 12px;
}

.card-batch {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color);
  margin-bottom: 12px;
}

.desktop-view {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
}

</style>
