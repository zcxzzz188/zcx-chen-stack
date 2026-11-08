<template>
  <div class="article-cover-image">
    <el-image
      v-if="coverSrc"
      :key="coverSrc"
      :src="coverSrc"
      :alt="alt"
      :fit="fit"
      class="cover-media"
    >
      <template #placeholder>
        <div class="cover-loading" role="status" aria-label="封面加载中"></div>
      </template>
      <template #error>
        <div class="cover-fallback" role="img" aria-label="暂无封面">
          <el-icon><Picture /></el-icon>
        </div>
      </template>
    </el-image>
    <div v-else class="cover-fallback" role="img" aria-label="暂无封面">
      <el-icon><Picture /></el-icon>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Picture } from '@element-plus/icons-vue'

const props = defineProps({
  src: { type: String, default: '' },
  alt: { type: String, default: '文章封面' },
  fit: { type: String, default: 'fill' },
})

const coverSrc = computed(() => (typeof props.src === 'string' ? props.src.trim() : ''))
</script>

<style scoped>
.article-cover-image {
  display: inline-flex;
  overflow: hidden;
  vertical-align: middle;
  background: var(--el-fill-color-light, #f5f7fa);
}

.cover-media,
.cover-loading,
.cover-fallback {
  width: 100%;
  height: 100%;
}

.cover-loading,
.cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--el-text-color-placeholder, #909399);
  font-size: 24px;
}
</style>
