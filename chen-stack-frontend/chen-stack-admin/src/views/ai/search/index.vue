<template>
  <ManagementCard class="ai-management" title="检索验证" v-model:model-current-page="usagePage" :model-page-size="12" :total="usage.length" pagination-layout="total, prev, pager, next, jumper">
    <template #filters>
      <el-button :icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
    </template>
    <template #table-view>
    <el-alert v-if="error" :title="friendlyError" type="warning" show-icon :closable="false" class="notice" />
    <div class="search-form">
      <p>当前客服使用：<el-tag size="small">{{ modeName(status.mode) }}</el-tag></p>
      <el-form label-position="top" @submit.prevent="search">
        <el-form-item :label="mode === 'wiki' ? '要检索的关键词' : '要验证的问题'"><el-input v-model="query" :placeholder="mode === 'wiki' ? '例如：关注' : '例如：忘记密码怎么办？'" :maxlength="mode === 'wiki' ? 80 : 500" show-word-limit /></el-form-item>
        <p v-if="mode === 'wiki'" class="hint">Wiki按标题和正文中的关键词匹配，请输入“关注”或“忘记密码”等简短词语；完整问句可能无法命中。</p>
        <div class="toolbar"><el-select v-model="mode" aria-label="测试知识类型"><el-option v-for="item in supportedModes" :key="item" :label="modeName(item)" :value="item" /></el-select><el-button type="primary" native-type="submit" :loading="searching" :disabled="!status.capabilities?.test || !query.trim()">检索知识</el-button><el-button :icon="Refresh" :disabled="searching" @click="reset">重置</el-button></div>
      </el-form>
      <p v-if="searched" class="hint">{{ results.length ? `找到${results.length}条有效知识，以下是实际检索片段。` : '未找到可用知识。请检查资料是否覆盖此问题、是否已授权发布，以及索引是否可用。' }}</p>
      <article v-for="(item, index) in results" :key="`${item.id}-${index}`" class="result">
        <h3>{{ item.title }}</h3><WikiMarkdown v-if="resultMode === 'wiki'" :markdown="readableWikiMarkdown(item.text)" class="excerpt" /><p v-else class="excerpt">{{ item.text }}</p>
      </article>
    </div>
    <h3 class="section-title">最近客服参考的知识</h3>
      <DataTable :loading="loading" :data="visibleUsage" :show-id="false" :show-create-time="false" :show-actions="false" :empty-text="error ? '使用记录暂未加载，请刷新重试' : '暂无可查看的客服知识使用记录'">
        <el-table-column label="时间" min-width="190"><template #default="{ row }">{{ formatKnowledgeTime(row.time) }}</template></el-table-column>
        <el-table-column label="使用方式" min-width="160"><template #default="{ row }">{{ modeName(row.knowledgeMode) }}</template></el-table-column>
        <el-table-column label="实际来源" min-width="260"><template #default="{ row }"><ul v-if="row.sources?.length" class="sources"><li v-for="(source, index) in row.sources" :key="`${source.id}-${index}`">{{ source.title }}<span v-if="source.version" class="hint">（{{ source.version }}）</span></li></ul><span v-else class="hint">{{ row.outcome === 'NO_EVIDENCE' ? '未命中有效知识' : '此次记录没有知识引用' }}</span></template></el-table-column>

      </DataTable>
    </template>
  </ManagementCard>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import ManagementCard from '@/components/management/ManagementCard.vue'
import DataTable from '@/components/data/DataTable.vue'
import WikiMarkdown from '@/components/wiki/WikiMarkdown.vue'
import { readableWikiMarkdown } from '@/utils/wikiPresentation'
import { knowledgeApi } from '@/api/knowledge'
import { formatKnowledgeTime, knowledgeModeLabel as modeName } from '@/utils/aiPresentation'
const status = ref({})
const query = ref('')
const mode = ref('rag')
const results = ref([])
const resultMode = ref(null)
const usage = ref([])
const usagePage = ref(1)
const visibleUsage = computed(() => usage.value.slice((usagePage.value - 1) * 12, usagePage.value * 12))
const loading = ref(false)
const searching = ref(false)
const searched = ref(false)
const error = ref('')
const supportedModes = computed(() => (status.value.supportedModes || ['rag', 'wiki']).filter((item) => ['rag', 'wiki'].includes(item)))
const friendlyError = computed(() => !/[\u3400-\u9fff]/.test(error.value) ? '知识服务暂不可用，请稍后重试。' : error.value)
function failureMessage(failure) { return failure?.response?.data?.msg || failure?.msg || failure?.message || '请求失败，请稍后重试' }
async function refresh() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    status.value = (await knowledgeApi.status()).data
    if (supportedModes.value.includes(status.value.mode)) mode.value = status.value.mode
    usage.value = (await knowledgeApi.usage()).data
    usagePage.value = 1
  } catch (failure) { error.value = failureMessage(failure) } finally { loading.value = false }
}
async function search() {
  if (searching.value || !query.value.trim() || !status.value.capabilities?.test) return
  searching.value = true
  error.value = ''
  results.value = []
  searched.value = false
  try {
    const result = (await knowledgeApi.test({ query: query.value.trim(), mode: mode.value })).data
    results.value = result.items
    resultMode.value = result.mode
    searched.value = true
  } catch (failure) { error.value = failureMessage(failure) } finally { searching.value = false }
}
function reset() { query.value = ''; results.value = []; resultMode.value = null; searched.value = false; error.value = '' }
onMounted(refresh)
</script>

<style lang="scss" src="@/assets/scss/ai-management.scss"></style>
