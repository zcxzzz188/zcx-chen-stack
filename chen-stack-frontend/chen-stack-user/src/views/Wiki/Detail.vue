<template>
  <main class="wiki-reading">
    <RouterLink class="back-link" to="/wiki">← 知识目录</RouterLink>
    <div v-if="loading" class="reading-state" role="status">正在加载知识页面…</div>
    <section v-else-if="error" class="reading-state" role="alert">
      <h1>{{ unavailable ? '页面当前不可访问' : '知识页面加载失败' }}</h1>
      <p>{{ error }}</p><button @click="load">重新加载</button>
    </section>
    <template v-else-if="page">
      <header class="reading-header">
        <div class="topics"><RouterLink v-for="topic in page.topics" :key="topic" :to="{ path: '/wiki', query: { topic } }">{{ topic }}</RouterLink></div>
        <h1>{{ page.title }}</h1>
        <p class="meta">版本 {{ page.version }} · 发布于 {{ formatDate(page.publishedAt) }}</p>
        <p class="review-note">引用可用于核查来源；自动语义核验尚未完成（SEMANTIC_NOT_VERIFIED）。</p>
      </header>
      <div class="reading-layout">
        <aside v-if="rendered.headings.length" class="wiki-toc" aria-label="页面目录">
          <strong>本页目录</strong>
          <a v-for="heading in rendered.headings" :key="heading.id" :href="`#${heading.id}`" :class="{ indent: heading.level > 2 }">{{ heading.title }}</a>
        </aside>
        <article class="wiki-content" v-html="rendered.html"></article>
      </div>
      <section class="reading-footer" aria-label="来源引用">
        <h2>来源引用</h2>
        <p v-if="!page.citations?.length">此页没有可跳转的公开来源。</p>
        <ul v-else><li v-for="(citation, index) in page.citations" :key="index">
          <a v-if="safeWikiLink(citation.url)" :href="safeWikiLink(citation.url)" rel="noopener noreferrer">{{ citation.title }}</a>
          <span v-else>{{ citation.title }}</span>
          <span class="source-version">来源版本：{{ citation.version }}</span>
        </li></ul>
      </section>
      <section v-if="page.related?.length" class="reading-footer" aria-label="关联知识">
        <h2>继续阅读</h2>
        <ul><li v-for="related in page.related" :key="related.id"><RouterLink :to="`/wiki/${related.id}`">{{ related.title }}</RouterLink></li></ul>
      </section>
    </template>
  </main>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { getWikiPage } from '@/api/wiki'
import { renderWikiMarkdown, safeWikiLink } from '@/utils/wikiMarkdown'

const route = useRoute()
const page = ref(null)
const loading = ref(false)
const error = ref('')
const unavailable = ref(false)
const rendered = computed(() => renderWikiMarkdown(page.value?.markdown || ''))
let requestNumber = 0

async function load() {
  const current = ++requestNumber
  page.value = null
  error.value = ''
  unavailable.value = false
  const id = String(route.params.id || '')
  if (!/^[1-9]\d{0,18}$/.test(id)) {
    unavailable.value = true
    error.value = '页面地址无效，请从知识目录重新选择。'
    loading.value = false
    return
  }
  loading.value = true
  try {
    const response = await getWikiPage(id)
    if (current === requestNumber) page.value = response.data
  } catch (failure) {
    if (current !== requestNumber) return
    unavailable.value = [403, 404].includes(failure.response?.status)
    error.value = unavailable.value ? '页面尚未发布、已撤回或来源已失效，请返回目录查看当前内容。' : '暂时无法取得页面，请稍后重试。'
  } finally {
    if (current === requestNumber) loading.value = false
  }
}

function formatDate(value) { return value ? String(value).slice(0, 10) : '' }
watch(() => route.params.id, load, { immediate: true })
</script>

<style scoped>
.wiki-reading { max-width: 1160px; margin: 0 auto; padding: 82px 24px 60px; color: var(--text-primary); }
.back-link, a { color: var(--el-color-primary); text-decoration: none; }
a:hover { text-decoration: underline; }
.reading-header { margin: 30px 0; }
.reading-header h1 { font-size: 34px; line-height: 1.35; overflow-wrap: anywhere; }
.meta, .source-version { font-size: 13px; color: var(--text-muted); }
.topics { display: flex; flex-wrap: wrap; gap: 12px; font-size: 13px; }
.review-note { background: var(--el-color-warning-light-9); border-left: 3px solid var(--el-color-warning); padding: 14px 18px; font-size: 13px; line-height: 1.6; }
.reading-layout { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 34px; align-items: start; }
.wiki-toc { position: sticky; top: 80px; display: flex; flex-direction: column; gap: 12px; border-left: 1px solid var(--border); padding: 16px; max-height: 70vh; overflow-y: auto; font-size: 13px; overflow-wrap: anywhere; }
.wiki-toc a { color: var(--text-muted); line-height: 1.5; }
.wiki-toc .indent { padding-left: 12px; }
.wiki-content { min-width: 0; padding: 28px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 10px; line-height: 1.85; overflow-wrap: anywhere; }
.wiki-content :deep(h1), .wiki-content :deep(h2), .wiki-content :deep(h3), .wiki-content :deep(h4) { scroll-margin-top: 80px; line-height: 1.4; margin: 28px 0 16px; }
.wiki-content :deep(h1:first-child), .wiki-content :deep(h2:first-child) { margin-top: 0; }
.wiki-content :deep(pre) { background: var(--el-fill-color-light); padding: 18px; overflow-x: auto; border-radius: 6px; line-height: 1.6; }
.wiki-content :deep(code) { font-family: ui-monospace, Consolas, monospace; font-size: .9em; }
.wiki-content :deep(blockquote) { border-left: 3px solid var(--el-color-primary); padding: 4px 18px; margin-left: 0; color: var(--text-muted); }
.wiki-content :deep(a) { color: var(--el-color-primary); }
.wiki-content :deep(table) { display: block; max-width: 100%; overflow-x: auto; border-collapse: collapse; }
.wiki-content :deep(th), .wiki-content :deep(td) { border: 1px solid var(--border); padding: 8px 12px; }
.reading-footer { margin: 32px 0 0 254px; padding-top: 20px; border-top: 1px solid var(--border); }
.reading-footer h2 { font-size: 20px; }
.reading-footer li { margin: 12px 0; line-height: 1.7; overflow-wrap: anywhere; }
.source-version { display: block; }
.reading-state { padding: 70px 16px; text-align: center; }
.reading-state p { color: var(--text-muted); }
button { font: inherit; border: 1px solid var(--border); border-radius: 6px; padding: 10px 16px; color: var(--text-primary); background: var(--bg-card); cursor: pointer; }
@media(max-width: 760px) { .wiki-reading { padding: 68px 16px 32px; } .reading-layout { grid-template-columns: 1fr; gap: 20px; } .wiki-toc { position: static; max-height: 220px; } .wiki-content { padding: 20px; } .reading-footer { margin-left: 0; } .reading-header h1 { font-size: 28px; } }
</style>
