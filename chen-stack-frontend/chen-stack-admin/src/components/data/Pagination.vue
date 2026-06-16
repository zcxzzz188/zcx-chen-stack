<template>
  <div class="pagination-container">
    <el-pagination
      v-model:current-page="currentPageValue"
      v-model:page-size="pageSizeValue"
      :page-sizes="pageSizes"
      :layout="layout"
      :total="total"
      :pager-count="pagerCount"
      @size-change="handleSizeChange"
      @current-change="handleCurrentChange"
    />
  </div>
</template>

<script setup>
/**
 * Pagination 分页组件
 *
 * 功能说明：
 * - 基于 el-pagination 的分页控件
 * - 支持 v-model:currentPage 和 v-model:pageSize 双向绑定
 * - 支持自定义每页条数选项（默认 [10, 20, 50, 100]）
 *
 * 使用方式：
 * ```vue
 * <Pagination v-model:current-page="currentPage" v-model:page-size="pageSize" :total="100" @size-change="handleSizeChange" @current-change="handleCurrentChange" />
 * ```
 */

import { computed } from 'vue'

// Props 定义
const props = defineProps({
  // 当前页码
  currentPage: {
    type: Number,
    required: true,
    default: 1,
  },
  // 每页显示条数
  pageSize: {
    type: Number,
    required: true,
    default: 10,
  },
  // 总条数
  total: {
    type: Number,
    required: true,
    default: 0,
  },
  // 每页显示个数选择器的选项设置
  pageSizes: {
    type: Array,
    default: () => [10, 20, 50, 100],
  },
  // 组件布局
  layout: {
    type: String,
    default: 'prev, pager, next, jumper',
  },
})

// 事件定义
const emit = defineEmits(['update:currentPage', 'update:pageSize', 'size-change', 'current-change'])

// 双向绑定当前页码
const currentPageValue = computed({
  get: () => props.currentPage,
  set: (value) => emit('update:currentPage', value),
})

// 双向绑定每页显示条数
const pageSizeValue = computed({
  get: () => props.pageSize,
  set: (value) => emit('update:pageSize', value),
})

// 处理每页条数变化
const handleSizeChange = (size) => {
  emit('size-change', size)
}

// 处理当前页码变化
const handleCurrentChange = (current) => {
  emit('current-change', current)
}

const pagerCount = 7
</script>

<style lang="scss" scoped>
// 分页容器
.pagination-container {
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
  margin-top: 12px;
  padding: 10px;
  background-color: var(--el-bg-color);
  border-radius: 0 0 12px 12px;
  z-index: 10;
  width: 100%;
  box-sizing: border-box;
}

</style>
