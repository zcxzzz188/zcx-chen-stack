<template>
  <div class="service-cards" aria-live="polite">
    <el-button v-if="current.type === 'NAVIGATION_ACTION'" type="primary" @click="navigate">
      {{ current.text }}
    </el-button>
    <div v-else-if="current.type === 'KNOWLEDGE_SOURCES'" class="knowledge-sources">
      <p>本次回答参考的公开来源</p>
      <ul><li v-for="(source, index) in current.sources" :key="index">
        <a v-if="source.url" :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.title }}</a>
        <span v-else>{{ source.title }}</span>
      </li></ul>
    </div>
    <template v-else-if="['ARTICLE_SELECTION', 'EMPTY_RESULT'].includes(current.type)">
      <p>{{ current.text }}</p>
      <div v-for="article in current.articles" :key="article.articleId" class="article-choice">
        <span class="article-title">{{ article.title }}</span>
        <small>{{ article.editStatus === 1 ? '草稿' : '已提交发布' }} · {{ ['待审核', '审核通过', '审核未通过'][article.examineStatus] }}</small>
        <el-button type="primary" :disabled="busy" @click="edit(article)">编辑</el-button>
      </div>
      <el-button v-if="current.page > 1" :disabled="busy" @click="loadPage(current.page - 1)">上一页</el-button>
      <el-button v-if="current.hasMore && current.page < 1000" :disabled="busy" @click="loadPage(current.page + 1)">下一页</el-button>
    </template>
    <template v-else-if="current.type === 'ERROR_STATE'">
      <p role="alert">{{ current.text }}</p>
      <el-button v-if="current.action === 'RETRY_ARTICLES'" :disabled="busy" @click="loadPage(1)">重试查询</el-button>
    </template>
  </div>
</template>

<script setup>
import { ref, watch, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { getEditableArticle, listEditableArticles } from '@/api/ai'
import { navigationTarget, validateCustomerServiceEvent } from './customerServiceProtocol'

const props = defineProps({ event: { type: Object, required: true } })
const emit = defineEmits(['navigate'])
const current = ref(props.event)
const busy = ref(false)
const router = useRouter()
const userStore = useUserStore()
let mounted = true
onUnmounted(() => { mounted = false })
watch(() => props.event, value => { current.value = value })
const stillCurrent = userId => mounted && userId === userStore.user?.id

const navigate = async () => {
  await router.push(navigationTarget(current.value.action, undefined, userStore.user?.id))
  if (mounted) emit('navigate')
}
const loadPage = async page => {
  const userId = userStore.user?.id
  busy.value = true
  try {
    const response = await listEditableArticles(page, current.value.pageSize || 5)
    if (stillCurrent(userId)) current.value = validateCustomerServiceEvent(response.data)
  } catch {
    if (stillCurrent(userId)) current.value = { type: 'ERROR_STATE', text: '文章查询失败，请重试或重新登录。', action: 'RETRY_ARTICLES' }
  } finally { busy.value = false }
}
const edit = async article => {
  if (!current.value.articles?.includes(article) || !article.canEdit || busy.value) return
  const userId = userStore.user?.id
  busy.value = true
  try {
    const response = await getEditableArticle(article.articleId)
    if (stillCurrent(userId) && response.data?.articleId === article.articleId && response.data?.canEdit === true) {
      await router.push(navigationTarget('OPEN_EDIT_ARTICLE', response.data))
      if (mounted) emit('navigate')
    }
  } catch {
    if (stillCurrent(userId)) current.value = { type: 'ERROR_STATE', text: '文章已不可编辑或登录已失效，请重新查询。', action: 'RETRY_ARTICLES' }
  } finally { busy.value = false }
}
</script>

<style scoped>
.service-cards { margin-top: 8px; }
.article-choice { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 10px; margin: 8px 0; border: 1px solid var(--el-border-color); border-radius: 8px; }
.article-title { width: 100%; overflow-wrap: anywhere; }
small { flex: 1; color: var(--el-text-color-secondary); }
</style>
