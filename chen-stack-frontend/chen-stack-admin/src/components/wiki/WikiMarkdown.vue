<template>
  <div class="wiki-markdown">
    <nav v-if="rendered.headings.length" aria-label="草稿目录" class="draft-toc">
      <strong>正文目录</strong>
      <a v-for="heading in rendered.headings" :key="heading.id" :href="`#${heading.id}`" :style="{ paddingLeft: `${Math.max(0, heading.level - 1) * 12}px` }">{{ heading.title }}</a>
    </nav>
    <article v-html="rendered.html" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { renderWikiMarkdown } from '@/utils/wikiMarkdown'
const props = defineProps({ markdown: { type: String, default: '' } })
const rendered = computed(() => renderWikiMarkdown(props.markdown))
</script>

<style scoped>
.wiki-markdown { line-height: 1.85; overflow-wrap: anywhere; }
.draft-toc { display: flex; flex-direction: column; gap: 3px; margin-bottom: 18px; padding: 12px 16px; background: var(--el-fill-color-light); border-radius: 8px; font-size: 13px; }
.draft-toc a { color: var(--el-color-primary); text-decoration: none; }
.wiki-markdown :deep(pre) { overflow-x: auto; padding: 14px; border-radius: 8px; background: var(--el-fill-color-light); }
.wiki-markdown :deep(code) { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.wiki-markdown :deep(blockquote) { margin-left: 0; padding-left: 16px; border-left: 3px solid var(--el-border-color); color: var(--el-text-color-secondary); }
.wiki-markdown :deep(table) { border-collapse: collapse; display: block; overflow-x: auto; }
.wiki-markdown :deep(th), .wiki-markdown :deep(td) { border: 1px solid var(--el-border-color); padding: 6px 10px; }
.wiki-markdown :deep(a) { color: var(--el-color-primary); }
</style>
