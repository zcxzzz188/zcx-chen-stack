<template>
  <main class="wiki-page">
    <header class="wiki-hero">
      <p class="kicker">Chen Stack Wiki</p>
      <h1>知识 Wiki</h1>
      <p>浏览经人工审核发布的技术知识，沿着引用继续阅读。</p>
    </header>
    <form class="wiki-search" @submit.prevent="search">
      <label for="wiki-search">搜索 Wiki</label>
      <div class="search-controls">
        <input id="wiki-search" v-model="keyword" maxlength="80" placeholder="搜索标题、摘要和正文" type="search" />
        <select v-model="topic" aria-label="主题分类" @change="search">
          <option value="">全部主题</option>
          <option v-for="item in topics" :key="item" :value="item">{{ item }}</option>
        </select>
        <button type="submit" :disabled="loading">搜索</button>
      </div>
    </form>
    <div v-if="loading" class="wiki-state" role="status">正在加载知识目录…</div>
    <div v-else-if="error" class="wiki-state" role="alert">
      <p>{{ error }}</p><button @click="load">重新加载</button>
    </div>
    <template v-else>
      <p class="result-count" aria-live="polite">共 {{ total }} 个公开页面</p>
      <div v-if="!items.length" class="wiki-state">{{ keyword || topic ? '没有找到符合条件的 Wiki 页面。' : '暂无已发布的 Wiki 页面。' }}</div>
      <section v-else class="wiki-grid" aria-label="知识页面">
        <article v-for="item in items" :key="item.id" class="wiki-card">
          <div class="wiki-tags"><span v-for="tag in item.topics" :key="tag">{{ tag }}</span></div>
          <h2><RouterLink :to="`/wiki/${item.id}`">{{ item.title }}</RouterLink></h2>
          <p>{{ item.summary || '打开页面阅读完整知识与引用。' }}</p>
          <div class="card-meta"><span>版本 {{ item.version }}</span><time>{{ formatDate(item.publishedAt) }}</time></div>
        </article>
      </section>
      <nav v-if="total > size" class="wiki-pagination" aria-label="目录分页">
        <button :disabled="page <= 1" @click="goPage(page - 1)">上一页</button>
        <span>第 {{ page }} / {{ Math.max(1, Math.ceil(total / size)) }} 页</span>
        <button :disabled="page * size >= total" @click="goPage(page + 1)">下一页</button>
      </nav>
    </template>
  </main>
</template>

<script setup>
import { ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { listWikiPages } from '@/api/wiki'

const route = useRoute()
const router = useRouter()
const keyword = ref('')
const topic = ref('')
const page = ref(1)
const size = 12
const items = ref([])
const topics = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
let requestNumber = 0

async function load() {
  const current = ++requestNumber
  keyword.value = typeof route.query.q === 'string' ? route.query.q.slice(0, 80) : ''
  topic.value = typeof route.query.topic === 'string' ? route.query.topic.slice(0, 100) : ''
  page.value = /^\d+$/.test(String(route.query.page || '1')) ? Math.min(10000, Math.max(1, Number(route.query.page || 1))) : 1
  loading.value = true
  error.value = ''
  items.value = []
  total.value = 0
  try {
    const response = await listWikiPages({ q: keyword.value, topic: topic.value, page: page.value, size })
    if (current !== requestNumber) return
    items.value = response.data.items
    topics.value = response.data.topics
    total.value = response.data.total
  } catch {
    if (current === requestNumber) error.value = '知识目录暂时无法加载，请稍后重试。'
  } finally {
    if (current === requestNumber) loading.value = false
  }
}

function search() {
  const query = { ...(keyword.value.trim() ? { q: keyword.value.trim() } : {}), ...(topic.value ? { topic: topic.value } : {}) }
  if (JSON.stringify(query) === JSON.stringify(route.query)) load()
  else router.push({ path: '/wiki', query })
}

function goPage(value) {
  router.push({ path: '/wiki', query: { ...route.query, page: String(value) } })
}

function formatDate(value) {
  return value ? String(value).slice(0, 10) : ''
}

watch(() => route.query, load, { immediate: true })
</script>

<style scoped>
.wiki-page { max-width: 1160px; margin: 0 auto; padding: 82px 24px 48px; color: var(--text-primary); }
.wiki-hero { padding: 32px 0; }
.kicker { color: var(--el-color-primary); font-size: 13px; letter-spacing: .08em; }
.wiki-hero h1 { font-size: 34px; margin: 8px 0 14px; }
.wiki-hero > p:last-child, .wiki-card p { color: var(--text-muted); line-height: 1.7; }
.wiki-search { padding: 22px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; }
.wiki-search label { display: block; font-weight: 600; margin-bottom: 12px; }
.search-controls { display: flex; gap: 12px; }
input, select, button { font: inherit; border-radius: 6px; border: 1px solid var(--border); padding: 10px 14px; color: var(--text-primary); background: var(--bg-card); }
input { flex: 1; min-width: 0; }
select { max-width: 220px; }
button { cursor: pointer; }
button[type=submit] { background: var(--el-color-primary); border-color: var(--el-color-primary); color: white; }
button:disabled { cursor: default; opacity: .5; }
.result-count { color: var(--text-muted); margin: 24px 0 14px; font-size: 13px; }
.wiki-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.wiki-card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; padding: 24px; overflow-wrap: anywhere; }
.wiki-card h2 { font-size: 20px; margin: 14px 0 10px; }
.wiki-card h2 a { color: var(--text-primary); text-decoration: none; }
.wiki-card h2 a:hover { color: var(--el-color-primary); }
.wiki-card p { font-size: 14px; min-height: 48px; }
.wiki-tags { display: flex; gap: 8px; flex-wrap: wrap; }
.wiki-tags span { color: var(--el-color-primary); background: var(--el-color-primary-light-9); border-radius: 4px; padding: 3px 8px; font-size: 12px; }
.card-meta, .wiki-pagination { display: flex; justify-content: space-between; gap: 16px; align-items: center; }
.card-meta { font-size: 12px; color: var(--text-muted); margin-top: 24px; }
.wiki-pagination { justify-content: center; margin-top: 28px; }
.wiki-state { padding: 60px 16px; text-align: center; color: var(--text-muted); }
@media(max-width: 650px) { .wiki-page { padding: 68px 16px 32px; } .wiki-grid { grid-template-columns: 1fr; } .search-controls { flex-wrap: wrap; } input { width: 100%; flex-basis: 100%; } select { flex: 1; } .wiki-hero h1 { font-size: 28px; } }
</style>
