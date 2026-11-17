<template>
  <main class="ai-management knowledge-library" v-loading="busy">
    <input ref="picker" type="file" accept=".md,.txt" hidden @change="upload" />

    <ManagementCard v-if="tableSection" :title="titles[section]" :show-pagination="false">
      <template #filters>
        <KeywordSearch :model-value="filters.q" label="关键词" :placeholder="section === 'entries' ? '搜索标题或正文' : '搜索标题'"
          width="200px" @update:model-value="filters.q = $event.slice(0, 160)" @keydown.enter.prevent="applyFilters" />
        <CommonSelect v-if="section === 'documents'" v-model="filters.curated" label="来源" placeholder="全部来源" width="116px"
          :options="sourceOptions" />
        <CommonSelect v-if="section !== 'entries'" v-model="filters.state" :label="section === 'pages' ? '发布状态' : '处理状态'"
          placeholder="全部状态" width="116px" :options="section === 'pages' ? wikiStates : documentStates" />
        <CommonSelect v-if="section === 'pages'" v-model="filters.reviewRequired" label="审核" placeholder="全部" width="105px"
          :options="reviewOptions" />
        <template v-if="section === 'entries'">
          <CommonSelect v-model="filters.documentId" label="所属模块" placeholder="全部模块" width="164px"
            :options="moduleOptions" option-label="title" option-value="id" remote :load-options="findModules" />
          <CommonSelect v-model="filters.enabled" label="启用" placeholder="全部" width="100px" :options="enabledOptions" />
        </template>
        <TimeRangePicker v-model:start-time="filters.from" v-model:end-time="filters.to" />
        <SearchButtons @search="applyFilters" @reset="resetFilters" />
        <el-button v-if="section === 'documents' && capabilities.ragManage" type="primary" plain @click="chooseFile(null)">上传文档</el-button>
        <el-button v-if="section === 'entries' && capabilities.ragManage" type="primary" plain @click="openEntry()">新增知识</el-button>
        <el-button v-if="section === 'pages' && capabilities.wikiCompile" type="primary" plain @click="chooseSources">AI 生成</el-button>
        <el-button :icon="Refresh" :loading="busy" @click="load">刷新</el-button>
      </template>
      <template #batch-actions>
        <BatchActions :selected-count="selected.length" :show-batch-audit="false" :show-batch-reject="false" :show-batch-delete="false">
          <span class="selection-count" aria-live="polite">已选中 {{ selected.length }} 项</span>
          <el-button v-if="section === 'documents'" :icon="Download" plain round
            :disabled="!selected.length || selected.length > 10" @click="downloadSelected">批量下载</el-button>
          <span v-if="section === 'documents'" class="hint">每次最多下载 10 份原件</span>
          <template v-if="section === 'entries' && capabilities.ragManage">
            <el-button type="primary" plain round :disabled="!selected.some(row => !row.enabled && row.status === 'READY')"
              @click="changeSelected(true)">批量启用</el-button>
            <el-button type="warning" plain round :disabled="!selected.some(row => row.enabled)"
              @click="changeSelected(false)">批量停用</el-button>
          </template>
          <template v-if="section === 'pages' && capabilities.wikiPublish">
            <el-button type="primary" plain round :disabled="!selected.some(row => row.status !== 'PUBLISHED' && !row.reviewRequired)"
              @click="changeSelected(true)">批量发布</el-button>
            <el-button type="warning" plain round :disabled="!selected.some(row => row.status === 'PUBLISHED')"
              @click="changeSelected(false)">批量下线</el-button>
          </template>
        </BatchActions>
      </template>
      <template #table-view>
        <el-alert v-if="listError" type="error" :title="listError" show-icon :closable="false" class="notice" />
    <DataTable :key="selectionKey" :data="rows" :loading="loadingList" row-key="id" show-selection
      :show-id="false" :show-create-time="false" :show-actions="false"
      :empty-text="listError ? '列表加载失败，请刷新重试' : emptyText[section]" @selection-change="selected = $event">
      <el-table-column prop="title" :label="section === 'documents' ? '文档 / 模块名称' : '标题'" min-width="190" show-overflow-tooltip />
      <el-table-column v-if="section === 'documents'" label="文档来源" min-width="140">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" type="info">{{ row.curated ? '整理母版' : '上传原件' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-else-if="section === 'entries'" label="所属模块" min-width="155">
        <template #default="{ row }">{{ row.module || '未归入文档' }}</template>
      </el-table-column>
      <el-table-column v-else prop="summary" label="摘要" min-width="220" show-overflow-tooltip />
      <el-table-column v-if="section === 'pages'" prop="version" label="版本" width="72" />
      <el-table-column :label="section === 'pages' ? '页面状态' : section === 'entries' ? '索引状态' : '处理状态'" width="145">
        <template #default="{ row }">
          <StatusBadge :text="labels[row.status] || '待处理'" :custom-class="statusClasses[row.status] || 'status-default'" />
          <StatusBadge v-if="row.reviewRequired" text="待复核" custom-class="status-unaudited" />
        </template>
      </el-table-column>
      <el-table-column v-if="section === 'entries'" label="启用" width="95">
        <template #default="{ row }"><el-switch :model-value="row.enabled" :disabled="!capabilities.ragManage" @change="enabled(row, $event)" /></template>
      </el-table-column>
      <el-table-column v-if="section === 'documents'" label="知识数" width="100">
        <template #default="{ row }"><el-button v-if="row.topicCount" link type="primary" @click="openModule(row.id)">{{ row.topicCount }} 条</el-button><span v-else>0</span></template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="165">
        <template #default="{ row }">{{ (row.updatedAt || row.createdAt)?.replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="320" header-align="center">
        <template #default="{ row }">
          <TableActions show-view :show-edit="section === 'entries' ? capabilities.ragManage : section === 'pages' && capabilities.wikiEdit"
            :show-delete="section === 'pages' ? capabilities.wikiPublish : capabilities.ragManage"
            @view="preview(row)" @edit="section === 'entries' ? openEntry(row) : openPage(row)" @delete="remove(row)">
            <template v-if="section === 'documents'">
              <el-button size="small" @click="original(row, true)">下载</el-button>
              <el-button v-if="capabilities.ragManage" size="small" @click="chooseFile(row.id)">替换</el-button>
              <el-button v-if="capabilities.ragManage && ['FAILED', 'UNINDEXED'].includes(row.status)" size="small" @click="retryDocument(row)">重试</el-button>
            </template>
            <el-button v-if="section === 'entries' && capabilities.ragManage && ['FAILED', 'UNINDEXED'].includes(row.status)"
              size="small" @click="retry(row)">重试</el-button>
            <el-button v-if="section === 'pages' && capabilities.wikiPublish" size="small"
              :disabled="row.reviewRequired && row.status !== 'PUBLISHED'" @click="publish(row)">{{ row.status === 'PUBLISHED' ? '下线' : '发布' }}</el-button>
          </TableActions>
        </template>
      </el-table-column>
    </DataTable>
    <Pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10,20,50]"
      layout="total, sizes, prev, pager, next, jumper" @current-change="load" @size-change="resizePage" />
      </template>
    </ManagementCard>

    <div v-if="section === 'search'" class="ai-workspace">
      <PageHeader :title="titles.search" description="验证知识检索结果与智能客服回答。" />
      <el-card shadow="never" class="workspace-card search-controls">
        <el-form label-position="top" @submit.prevent="verify">
          <div class="search-options">
            <el-form-item label="检索模式">
              <el-radio-group v-model="query.mode" :disabled="searching">
                <el-radio-button v-for="mode in modes" :key="mode" :value="mode">{{ modeLabels[mode] }}</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-checkbox v-model="query.answer" :disabled="searching">生成回答</el-checkbox>
          </div>
          <el-form-item label="检索问题">
            <el-input v-model="query.question" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" :maxlength="1000"
              :disabled="searching" placeholder="输入要验证的问题或关键词" show-word-limit />
          </el-form-item>
          <p v-if="query.mode === 'wiki'" class="hint search-hint">Wiki 按标题和正文匹配关键词。完整问句未命中时，可试用简短关键词。</p>
          <div class="workspace-actions">
            <el-button type="primary" :icon="Search" native-type="submit" :loading="searching"
              :disabled="!query.question.trim()">开始检索</el-button>
            <el-button @click="resetVerification">重置</el-button>
          </div>
        </el-form>
      </el-card>

      <el-card shadow="never" class="workspace-card verification" :aria-busy="searching">
        <template #header>
          <div class="panel-heading">
            <h2>检索结果</h2>
            <div v-if="result" class="result-meta">
              <el-tag size="small" effect="plain">请求 {{ modeLabels[submitted.mode] }}</el-tag>
              <el-tag v-if="result.engine !== submitted.mode" size="small" type="info" effect="plain">
                实际 {{ modeLabels[result.engine] || result.engine }}
              </el-tag>
              <span>{{ result.hits.length }} 条命中<span v-if="result.elapsedMs != null"> · {{ result.elapsedMs }} ms</span></span>
            </div>
          </div>
        </template>
        <p v-if="searching" class="hint workspace-status" role="status">正在检索知识…</p>
        <el-alert v-else-if="searchError" type="error" :title="searchError" :closable="false" show-icon />
        <p v-else-if="!result" class="hint workspace-status">输入问题后开始检索。</p>
        <p v-else-if="!result.hits.length" class="hint workspace-status">没有匹配的知识。</p>
        <article v-for="(hit, index) in result?.hits" :key="index" class="search-hit">
          <h3>{{ hit.title }}</h3>
          <p class="hint hit-source">{{ modeLabels[result.engine] }}{{ hit.url ? ' · ' + hit.url : '' }}</p>
          <p class="excerpt">{{ hit.content.length > 240 ? hit.content.slice(0, 240) + '…' : hit.content }}</p>
          <details v-if="hit.content.length > 240">
            <summary>查看完整检索片段</summary>
            <p class="excerpt">{{ hit.content }}</p>
          </details>
        </article>
      </el-card>

      <el-card v-if="submitted?.answer || (!submitted && query.answer)" shadow="never" class="workspace-card verification"
        :aria-busy="searching">
        <template #header><h2>生成回答</h2></template>
        <p v-if="searching" class="hint workspace-status" role="status">正在检索并生成回答…</p>
        <p v-else-if="searchError" class="hint workspace-status">本次请求未返回回答，请查看检索错误。</p>
        <template v-else-if="result?.answer">
          <pre class="answer-text">{{ result.answer }}</pre>
          <ul v-if="result.hits.length" class="answer-sources" aria-label="回答参考知识">
            <li v-for="(hit, index) in result.hits" :key="index">
              {{ hit.title }}<span v-if="hit.url" class="hint"> · {{ hit.url }}</span>
            </li>
          </ul>
        </template>
        <p v-else class="hint workspace-status">{{ result ? '本次请求未返回回答。' : '开始检索后生成回答。' }}</p>
      </el-card>
    </div>

    <div v-if="section === 'config'" class="ai-workspace">
    <PageHeader :title="titles.config" description="配置小辰的工作模式、回复风格与使用额度。" />
    <el-alert v-if="configError" :title="configError" type="error" :closable="false" show-icon class="notice" />
    <p v-if="busy && !settings" role="status" class="hint">正在读取 AI 配置…</p>

    <section v-if="section === 'config' && settings" class="settings-summary" aria-label="当前生效配置">
      <StatCard label="客服工作模式" :value="modeLabels[effectiveSettings.mode]" :icon="MagicStick" type="user"
        trend-text="当前生效" />
      <StatCard label="当前模型服务" value="DeepSeek" :icon="Cpu" type="article"
        :trend-text="effectiveSettings.model" />
      <StatCard label="每人每日额度" :value="effectiveSettings.dailyLimit + ' 次'" :icon="Timer" type="visits"
        trend-text="按北京时间自然日计数" />
    </section>
    <section v-if="section === 'config' && settings" class="settings-grid">
      <el-card shadow="never" class="workspace-card">
        <template #header><h2>工作模式</h2></template>
        <el-form label-position="top" :disabled="!capabilities.configManage" @submit.prevent="saveSettings(['mode'])">
        <el-form-item>
          <el-radio-group v-model="settings.mode" aria-label="工作模式" class="mode-list">
            <el-radio v-for="mode in modes" :key="mode" :value="mode" class="mode-option">
              <span class="mode-title">
                <strong>{{ modeLabels[mode] }}</strong>
                <el-tag v-if="mode === effectiveSettings.mode" size="small" type="success" effect="plain">当前生效</el-tag>
              </span>
              <span class="hint">{{ modeDescriptions[mode] }}</span>
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <div class="settings-footer">
          <el-button v-if="capabilities.configManage" type="primary" native-type="submit" :disabled="busy">保存工作模式</el-button>
          <span v-else class="hint">当前账号只有查看权限。</span>
        </div>
        </el-form>
      </el-card>
      <el-card shadow="never" class="workspace-card">
        <template #header><h2>客服回复风格</h2></template>
        <el-form label-position="top" :disabled="busy || !capabilities.configManage"
          @submit.prevent="saveSettings(['replyStyle'], '/settings/reply-style')">
          <el-form-item>
            <el-radio-group v-model="settings.replyStyle" aria-label="客服回复风格" class="mode-list">
              <el-radio v-for="style in replyStyles" :key="style.value" :value="style.value" class="mode-option">
                <span class="mode-title">
                  <strong>{{ style.label }}</strong>
                  <el-tag v-if="style.value === effectiveSettings.replyStyle" size="small" type="success" effect="plain">当前生效</el-tag>
                </span>
                <span class="hint">{{ style.description }}</span>
              </el-radio>
            </el-radio-group>
          </el-form-item>
          <div v-if="capabilities.configManage" class="settings-footer">
            <el-button type="primary" native-type="submit" :loading="savingReplyStyle"
              :disabled="busy || settings.replyStyle === effectiveSettings.replyStyle">保存回复风格</el-button>
          </div>
        </el-form>
      </el-card>
      <el-card shadow="never" class="workspace-card">
        <template #header><h2>使用额度</h2></template>
        <el-form label-position="top" :disabled="!capabilities.configManage" @submit.prevent="saveSettings(['dailyLimit'])">
        <el-form-item label="每人每日次数"><el-input-number v-model="settings.dailyLimit" :min="1" :max="10000" /></el-form-item>
          <div v-if="capabilities.configManage" class="settings-footer">
            <el-button type="primary" native-type="submit" :disabled="busy">保存额度</el-button>
          </div>
        </el-form>
      </el-card>
      <el-card shadow="never" class="workspace-card">
        <template #header><h2>知识检索</h2></template>
        <el-form label-position="top" :disabled="!capabilities.configManage" @submit.prevent="saveSettings(['topK'])">
          <el-form-item label="每次最多返回的知识条数（Top K）"><el-input-number v-model="settings.topK" :min="1" :max="20" /></el-form-item>
          <div v-if="capabilities.configManage" class="settings-footer">
            <el-button type="primary" native-type="submit" :disabled="busy">保存检索设置</el-button>
          </div>
        </el-form>
      </el-card>
    </section>
    <el-card v-else-if="!busy" shadow="never" class="workspace-card">
      <el-button @click="load">重新加载配置</el-button>
    </el-card>
    </div>

    <el-dialog v-model="batchReportVisible" title="批量操作结果" width="min(640px, 92vw)">
      <p>成功 {{ batchReport.success }} 项，失败 {{ batchReport.failed.length }} 项，跳过 {{ batchReport.skipped }} 项。</p>
      <ul><li v-for="failure in batchReport.failed" :key="failure.id">#{{ failure.id }} {{ failure.title }}：{{ failure.reason }}</li></ul>
    </el-dialog>

    <el-dialog v-model="dialog" class="ai-management-dialog" :title="detail.title || '知识详情'" width="min(840px, 92vw)">
      <el-form v-if="detail.edit" label-position="top" @submit.prevent="saveDetail">
        <el-form-item label="标题"><el-input v-model="detail.title" :maxlength="160" /></el-form-item>
        <el-form-item v-if="section === 'pages'" label="摘要"><el-input v-model="detail.summary" type="textarea" :maxlength="500" /></el-form-item>
        <el-form-item label="正文"><el-input v-model="detail.content" type="textarea" :rows="16" :maxlength="20000" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy">保存</el-button>
      </el-form>
      <template v-else>
        <p v-if="detail.summary">{{ detail.summary }}</p>
        <p v-if="section === 'entries' && detail.module" class="hint">所属模块：{{ detail.module }}<span v-if="detail.filename"> · {{ detail.filename }}</span></p>
        <div v-if="section === 'pages' || detail.markdown" class="markdown-preview" v-html="markdown.render(detail.content || '')"></div>
        <pre v-else>{{ detail.content }}</pre>
        <ul v-if="detail.sources?.length" class="wiki-sources" aria-label="Wiki 来源">
          <li v-for="source in detail.sources" :key="source.id">{{ source.title }}<span v-if="source.module"> · {{ source.module }}</span></li>
        </ul>
        <template v-if="section === 'pages'">
          <el-button :disabled="busy" @click="loadRevisions">查看历史版本</el-button>
          <p v-if="detail.revisions?.length === 0" class="hint">暂无历史版本</p>
          <ul v-if="detail.revisions?.length" aria-label="Wiki 历史版本">
            <li v-for="version in detail.revisions" :key="version.version">
              <el-button text :disabled="busy" @click="showRevision(version.version)">版本 {{ version.version }} · {{ version.title }}</el-button>
            </li>
          </ul>
          <div v-if="detail.revision" class="wiki-sources">
            <h3>版本 {{ detail.revision.version }} · {{ detail.revision.title }}</h3>
            <p>{{ detail.revision.summary }}</p>
            <div class="markdown-preview" v-html="markdown.render(detail.revision.content || '')"></div>
            <el-button v-if="capabilities.wikiEdit && capabilities.wikiPublish" :loading="busy"
              @click="restoreRevision">恢复为草稿</el-button>
          </div>
        </template>
      </template>
    </el-dialog>

    <el-dialog v-model="sourcesDialog" class="ai-management-dialog" title="选择知识来源" width="min(640px, 92vw)">
      <el-select v-model="sourceIds" multiple filterable :multiple-limit="5" placeholder="选择一至五条知识">
        <el-option v-for="source in sources" :key="source.id" :label="source.title" :value="source.id" />
      </el-select>
      <template #footer><el-button type="primary" :loading="busy" :disabled="!sourceIds.length" @click="generate">生成草稿</el-button></template>
    </el-dialog>
  </main>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Cpu, Download, MagicStick, Refresh, Search, Timer } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'
import ManagementCard from '@/components/management/ManagementCard.vue'
import PageHeader from '@/components/management/PageHeader.vue'
import DataTable from '@/components/data/DataTable.vue'
import Pagination from '@/components/data/Pagination.vue'
import TableActions from '@/components/data/TableActions.vue'
import BatchActions from '@/components/actions/BatchActions.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import StatCard from '@/components/cards/StatCard.vue'
import CommonSelect from '@/components/search/CommonSelect.vue'
import KeywordSearch from '@/components/search/KeywordSearch.vue'
import SearchButtons from '@/components/search/SearchButtons.vue'
import TimeRangePicker from '@/components/search/TimeRangePicker.vue'
import request from '@/utils/Request'
import { GetJwt } from '@/utils/Auth'
import { runKnowledgeBatch } from './batch'

const route = useRoute()
const router = useRouter()
const markdown = new MarkdownIt({ html: false, breaks: true }).disable(['image'])
const sections = { documents: 'documents', rag: 'entries', wiki: 'pages', search: 'search', config: 'config' }
const section = computed(() => sections[route.path.split('/').at(-1)])
const titles = { documents: '知识文档', entries: 'RAG 知识库', pages: 'Wiki 页面', search: '检索验证', config: 'AI 配置' }
const descriptions = {
  documents: '查看上传原件与整理母版，跟踪各文档模块的处理进度。',
  entries: '按模块查看、搜索和管理客服可用的检索知识。',
  pages: '查看和管理知识整理后的 Wiki 页面。',
}
const emptyText = { documents: '暂无知识文档', entries: '没有符合条件的知识', pages: '暂无 Wiki 页面' }
const labels = { PENDING: '处理中', READY: '已完成', FAILED: '处理失败', UNINDEXED: '需要处理', PUBLISHED: '已发布', OFFLINE: '已下线', DRAFT: '草稿' }
const statusClasses = { READY: 'status-audited', PUBLISHED: 'status-audited', PENDING: 'status-unaudited',
  UNINDEXED: 'status-unaudited', FAILED: 'status-rejected' }
const modes = ['rag', 'wiki', 'hybrid']
const modeLabels = { rag: 'RAG', wiki: 'Wiki', hybrid: 'Hybrid' }
const modeDescriptions = {
  rag: '从知识条目中检索与问题相关的片段。',
  wiki: '在已发布 Wiki 中匹配关键词。',
  hybrid: '优先匹配 Wiki，未命中时检索 RAG。',
}
const replyStyles = [
  { value: 'PROFESSIONAL_CONCISE', label: '简洁专业', description: '回答准确直接，操作步骤清晰。' },
  { value: 'FRIENDLY_NATURAL', label: '自然友好', description: '表达自然亲切，适合日常咨询。' },
  { value: 'PATIENT_DETAILED', label: '详细耐心', description: '提供更完整的操作指导。' },
]
const options = values => values.map(value => ({ value, label: labels[value] }))
const documentStates = options(['PENDING', 'READY', 'FAILED', 'UNINDEXED'])
const wikiStates = options(['PUBLISHED', 'OFFLINE', 'DRAFT'])
const sourceOptions = [{ value: 'false', label: '上传原件' }, { value: 'true', label: '整理母版' }]
const reviewOptions = [{ value: 'true', label: '待复核' }, { value: 'false', label: '无需复核' }]
const enabledOptions = [{ value: 'true', label: '已启用' }, { value: 'false', label: '已停用' }]
const tableSection = computed(() => ['documents', 'entries', 'pages'].includes(section.value))
const root = '/admin/knowledge-library'
const api = (path, method = 'get', data, params) => request({
  url: root + path, method, data, params, timeout: 65000,
  headers: data instanceof FormData ? { 'Content-Type': 'multipart/form-data' } : undefined,
})
const busy = ref(false)
const rows = ref([])
const capabilities = ref({})
const blankFilters = documentId => ({ q: '', curated: '', state: '', enabled: '', reviewRequired: '', from: '', to: '', documentId })
const filters = ref(blankFilters(null))
const applied = ref(blankFilters(null))
const moduleMatches = ref([])
const moduleOptions = computed(() => {
  const current = rows.value.find(row => row.documentId === filters.value.documentId)
  if (current && !moduleMatches.value.some(row => row.id === current.documentId)) {
    return [{ id: current.documentId, title: current.module }, ...moduleMatches.value]
  }
  return moduleMatches.value
})
let moduleGeneration = 0
const findModules = async (keyword = '') => {
  const generation = ++moduleGeneration
  try {
    const { data } = await api('/documents/list', 'get', undefined, { page: 1, size: 50, q: keyword.slice(0, 160) })
    if (generation === moduleGeneration && section.value === 'entries') moduleMatches.value = data.items
  } catch {
    if (generation === moduleGeneration) moduleMatches.value = []
  }
}
const page = ref(1)
const size = ref(10)
const total = ref(0)
const selected = ref([])
const selectionKey = ref(0)
const loadingList = ref(false)
const listError = ref('')
const searchError = ref('')
const configError = ref('')
const batchReportVisible = ref(false)
const batchReport = ref({ success: 0, failed: [], skipped: 0 })
let listGeneration = 0
const picker = ref(null)
const replaceId = ref(null)
const dialog = ref(false)
const detail = ref({})
const sourcesDialog = ref(false)
const sourceIds = ref([])
const sources = ref([])
const settings = ref(null)
const savingReplyStyle = ref(false)
const effectiveSettings = ref(null)
const query = ref({ question: '', mode: 'wiki', answer: false })
const result = ref(null)
const submitted = ref(null)
const searching = ref(false)
let verificationGeneration = 0

async function perform(action) {
  busy.value = true
  try { await action() } catch (error) { ElMessage.error(error?.msg || error?.message || '操作失败，请重试') }
  finally { busy.value = false }
}
const clearSelection = () => { selected.value = []; selectionKey.value += 1 }
const dateParam = value => value ? value.replace(' ', 'T') : undefined
const load = async () => {
  const active = section.value
  if (tableSection.value) {
    const generation = ++listGeneration
    clearSelection()
    loadingList.value = true
    listError.value = ''
    const { q, from, to, curated, state, documentId, enabled, reviewRequired } = applied.value
    const params = { page: page.value, size: size.value, q, from: dateParam(from), to: dateParam(to) }
    if (active === 'documents') Object.assign(params, { curated, state })
    if (active === 'entries') Object.assign(params, { documentId, enabled })
    if (active === 'pages') Object.assign(params, { state, reviewRequired })
    try {
      const { data } = await api('/' + active + '/list', 'get', undefined, params)
      if (generation !== listGeneration) return
      total.value = data.total
      const last = Math.max(1, Math.ceil(total.value / size.value))
      if (page.value > last) {
        page.value = last
        await load()
      } else {
        rows.value = data.items
      }
    } catch (error) {
      if (generation === listGeneration) {
        rows.value = []
        total.value = 0
        listError.value = error?.message || error?.msg || '列表暂无法加载'
      }
    } finally {
      if (generation === listGeneration) loadingList.value = false
    }
  } else if (active === 'config') {
    const generation = ++listGeneration
    await perform(async () => {
      configError.value = ''
      try {
        const { data } = await api('/settings')
        if (generation !== listGeneration) return
        effectiveSettings.value = data
        settings.value = { ...data }
      } catch (error) {
        if (generation === listGeneration) configError.value = error?.message || error?.msg || '配置暂无法加载'
      }
    })
  }
}
let refreshTimer
onMounted(() => {
  void api('/capabilities').then(({ data }) => { capabilities.value = data }).catch(() => {})
  refreshTimer = setInterval(() => {
    if (section.value === 'documents' && !busy.value && !loadingList.value && !selected.value.length && !document.hidden &&
        rows.value.some(row => row.status === 'PENDING')) void load()
  }, 3000)
})
onUnmounted(() => clearInterval(refreshTimer))
watch(() => [section.value, route.query.documentId], ([active, documentId]) => {
  listGeneration += 1
  loadingList.value = false
  page.value = 1
  rows.value = []
  total.value = 0
  clearSelection()
  resetVerification()
  settings.value = null
  configError.value = ''
  batchReportVisible.value = false
  dialog.value = false
  filters.value = blankFilters(active === 'entries' && /^[1-9]\d*$/.test(String(documentId)) ? Number(documentId) : null)
  applied.value = { ...filters.value }
  void load()
  if (active === 'entries') void findModules()
}, { immediate: true })
const applyFilters = () => {
  if (filters.value.from && filters.value.to && filters.value.from > filters.value.to) {
    ElMessage.warning('开始时间不能晚于结束时间')
    return
  }
  applied.value = { ...filters.value, q: filters.value.q.trim() }
  page.value = 1
  void load()
}
const resetFilters = () => {
  filters.value = blankFilters(null)
  applyFilters()
}
const openModule = (id) => router.push({ path: '/ai/rag', query: { documentId: id } })
const resizePage = () => { page.value = 1; void load() }
const confirmAction = async (message, title) => {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' })
    return true
  } catch {
    return false
  }
}
const saveBlob = (blob, filename) => {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
const downloadSelected = () => {
  if (busy.value || !selected.value.length || selected.value.length > 10) return
  const ids = selected.value.map(row => row.id)
  void perform(async () => {
    const backend = import.meta.env.VITE_BACKEND_SERVER || 'http://localhost:5000'
    const reply = await fetch(backend + root + '/documents/archive', {
      method: 'POST',
      headers: { Authorization: GetJwt(), 'Content-Type': 'application/json', Accept: 'application/zip, application/json' },
      body: JSON.stringify({ ids }),
    })
    if (!reply.ok || !reply.headers.get('content-type')?.startsWith('application/zip')) {
      const error = await reply.json().catch(() => ({}))
      throw new Error(error.msg || '所选原件暂无法下载')
    }
    saveBlob(await reply.blob(), 'knowledge-originals.zip')
    ElMessage.success('已生成 ' + ids.length + ' 份原件的 ZIP 下载')
    await load()
  })
}
const changeSelected = async value => {
  const active = section.value
  const isRag = active === 'entries'
  if (busy.value || !(isRag ? capabilities.value.ragManage : active === 'pages' && capabilities.value.wikiPublish)) return
  const snapshot = selected.value.slice(0, size.value)
  const eligible = row => !!row.updatedAt && (isRag
    ? value ? !row.enabled && row.status === 'READY' : row.enabled
    : value ? row.status !== 'PUBLISHED' && !row.reviewRequired : row.status === 'PUBLISHED')
  const count = snapshot.filter(eligible).length
  if (!count) return
  const action = isRag ? (value ? '启用' : '停用') : (value ? '发布' : '下线')
  const impact = isRag && !value ? '引用这些知识的 Wiki 也会下线。' : ''
  if (!await confirmAction(`对 ${count} 项执行${action}？不符合当前操作的记录将跳过。${impact}`, '确认批量' + action)) return
  if (busy.value || active !== section.value) return
  await perform(async () => {
    batchReport.value = await runKnowledgeBatch(snapshot, eligible, row => api(
      `/${active}/${row.id}/${isRag ? 'enabled' : 'published'}`, 'put', { enabled: value, expectedUpdatedAt: row.updatedAt },
    ))
    batchReportVisible.value = true
    await load()
  })
}
const chooseFile = (id) => { replaceId.value = id; picker.value?.click() }
const upload = (event) => {
  const file = event.target.files[0]
  event.target.value = ''
  if (!file) return
  const form = new FormData()
  form.append('file', file)
  if (replaceId.value) form.append('replaceId', replaceId.value)
  void perform(async () => { await api('/documents', 'post', form); await load() })
}
const original = (row, download) => perform(async () => {
  const backend = import.meta.env.VITE_BACKEND_SERVER || 'http://localhost:5000'
  const reply = await fetch(backend + root + '/documents/' + row.id + '/original?download=' + download,
    { headers: { Authorization: GetJwt() } })
  if (!reply.ok || !reply.headers.get('content-type')?.startsWith('text/plain')) throw new Error('原文件暂不可用')
  if (download) {
    saveBlob(await reply.blob(), row.filename)
  } else {
    detail.value = { title: row.title, content: await reply.text(), markdown: row.type === 'MD' }
    dialog.value = true
  }
})
const openDetail = (path, edit) => perform(async () => {
  const [details, references] = await Promise.all([api(path), path.startsWith('/pages/') ? api(path + '/sources') : null])
  detail.value = { ...details.data, sources: references?.data, edit }
  dialog.value = true
})
const preview = (row) => section.value === 'documents' ? original(row, false) : openDetail('/' + section.value + '/' + row.id, false)
const openEntry = (row) => {
  if (row) return openDetail('/entries/' + row.id, true)
  detail.value = { edit: true, title: '', content: '' }
  dialog.value = true
}
const openPage = (row) => openDetail('/pages/' + row.id, true)
const loadRevisions = () => perform(async () => {
  const page = detail.value
  const { data } = await api('/pages/' + page.id + '/revisions')
  if (detail.value === page) page.revisions = data
})
const showRevision = (version) => perform(async () => {
  const page = detail.value
  const { data } = await api('/pages/' + page.id + '/revisions/' + version)
  if (detail.value === page) page.revision = data
})
const restoreRevision = async () => {
  const page = detail.value
  try {
    await ElMessageBox.confirm('当前正文会保留为历史版本。恢复后的草稿需要重新发布。', '恢复历史版本', { type: 'warning' })
  } catch { return }
  if (detail.value !== page) return
  await perform(async () => {
    await api('/pages/' + page.id + '/revisions/' + page.revision.version + '/restore', 'post',
      { expectedUpdatedAt: page.updatedAt })
    dialog.value = false
    await load()
  })
}
const saveDetail = () => perform(async () => {
  const { id, title, content, summary } = detail.value
  const active = section.value
  const data = active === 'pages' ? { title, content, summary } : { title, content }
  await api('/' + active + (id ? '/' + id : ''), id ? 'put' : 'post', data)
  dialog.value = false
  await load()
})
const enabled = (row, value) => perform(async () => { await api('/entries/' + row.id + '/enabled', 'put', { enabled: value }); await load() })
const retry = (row) => perform(async () => { await api('/entries/' + row.id + '/retry', 'post'); await load() })
const retryDocument = (row) => perform(async () => { await api('/documents/' + row.id + '/retry', 'post'); await load() })
const publish = (row) => perform(async () => { await api('/pages/' + row.id + '/published', 'put', { enabled: row.status !== 'PUBLISHED' }); await load() })
const remove = async (row) => {
  const active = section.value
  const impact = active === 'documents' ? '删除涉及该文档的原件和关联知识；服务端会检查当前依赖。' : '删除后该记录将不再可用。'
  if (!await confirmAction('删除「' + row.title + '」？' + impact, '确认删除') || active !== section.value) return
  await perform(async () => { await api('/' + active + '/' + row.id, 'delete'); await load() })
}
const chooseSources = () => perform(async () => {
  const { data } = await api('/entries')
  sources.value = data.filter(row => row.enabled && ['READY', 'UNINDEXED'].includes(row.status))
  sourceIds.value = []
  sourcesDialog.value = true
})
const generate = () => perform(async () => {
  await api('/pages', 'post', { ids: sourceIds.value })
  sourcesDialog.value = false
  await load()
})
function resetVerification() {
  verificationGeneration += 1
  searching.value = false
  query.value = { question: '', mode: 'wiki', answer: false }
  submitted.value = null
  result.value = null
  searchError.value = ''
}
async function verify() {
  const question = query.value.question.trim()
  if (!question || searching.value) return
  const generation = ++verificationGeneration
  submitted.value = { ...query.value, question }
  searching.value = true
  result.value = null
  searchError.value = ''
  try {
    const { data } = await api('/verification', 'post', submitted.value)
    if (generation === verificationGeneration) result.value = data
  } catch (error) {
    if (generation === verificationGeneration) searchError.value = error?.message || error?.msg || '检索失败，请重试'
  } finally {
    if (generation === verificationGeneration) searching.value = false
  }
}
function saveSettings(fields, path = '/settings') {
  if (busy.value || !capabilities.value.configManage || !settings.value) return
  const draft = settings.value
  const changes = Object.fromEntries(fields.map(key => [key, draft[key]]))
  const replyStyleOnly = path === '/settings/reply-style'
  if (replyStyleOnly && changes.replyStyle === effectiveSettings.value.replyStyle) return
  return perform(async () => {
    configError.value = ''
    savingReplyStyle.value = replyStyleOnly
    try {
      const payload = replyStyleOnly ? changes : { ...(await api('/settings')).data, ...changes }
      const { data: saved } = await api(path, 'put', payload)
      const { data: live } = await api('/settings')
      if (draft !== settings.value) return
      effectiveSettings.value = live
      Object.assign(draft, Object.fromEntries(fields.map(key => [key, live[key]])))
      if (fields.some(key => live[key] !== saved[key])) {
        ElMessage.warning('已保存，但重新读取的值不同，请检查当前生效配置')
        return
      }
      ElMessage.success('配置已保存，已核对当前生效值')
    } catch (error) {
      if (draft === settings.value) configError.value = error?.message || error?.msg || '配置保存未完成，请重试'
    } finally {
      savingReplyStyle.value = false
    }
  })
}
</script>

<style lang="scss" src="@/assets/scss/ai-management.scss"></style>

<style scoped>
.knowledge-library .el-card {
  --el-card-border-radius: 18px;
  --el-card-bg-color: var(--el-bg-color-overlay);
}
.settings-summary {
  --bg-card: var(--el-bg-color-overlay);
  --bg-card-muted: var(--el-fill-color-light);
  --text-primary: var(--el-text-color-primary);
  --text-regular: var(--el-text-color-regular);
  --text-muted: var(--el-text-color-secondary);
  --border: var(--el-border-color-light);
  --shadow-card: 0 10px 24px rgb(15 23 42 / 6%);
  --shadow-hover: var(--el-box-shadow-light);
  --trend-neutral-bg: var(--el-fill-color-light);
}
.knowledge-library :deep(.el-card__header) { padding: 16px 20px; font-weight: 600; }
.knowledge-library :deep(.el-card__body) { padding: 18px 20px; }
.page-hint { margin: 0 0 12px; }
.selection-count { font-size: 14px; color: var(--el-text-color-regular); }
.ai-workspace { min-width: 0; padding: 8px 0 16px; }
.workspace-card {
  min-width: 0;
  border-color: var(--el-border-color-light);
  box-shadow: 0 10px 24px rgb(15 23 42 / 6%);
}
.workspace-card h2 { margin: 0; font-size: 16px; font-weight: 600; }
.search-controls :deep(.el-form-item) { margin-bottom: 10px; }
.search-options, .panel-heading, .result-meta, .workspace-actions, .mode-title {
  display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px;
}
.search-options, .panel-heading { justify-content: space-between; }
.search-hint { margin: 0 0 10px; }
.workspace-actions > .el-button { margin-left: 0; }
.verification { margin-top: 16px; }
.result-meta { color: var(--el-text-color-secondary); font-size: 13px; font-weight: 400; }
.workspace-status { margin: 0; }
.search-hit + .search-hit { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--el-border-color-lighter); }
.search-hit h3 { margin: 0; font-size: 15px; overflow-wrap: anywhere; }
.search-hit .hit-source { margin: 2px 0 0; }
.search-hit .excerpt { margin: 6px 0; }
.search-hit summary { cursor: pointer; color: var(--el-color-primary); font-size: 13px; }
.answer-text { margin: 0; line-height: 1.75; }
.answer-sources { margin: 14px 0 0; padding: 12px 0 0 20px; border-top: 1px solid var(--el-border-color-lighter); overflow-wrap: anywhere; }
.settings-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-bottom: 20px; }
.settings-summary :deep(.number) { font-size: clamp(22px, 2vw, 32px); overflow-wrap: anywhere; }
.settings-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.settings-grid :deep(.el-card__body) { display: flex; flex: 1; flex-direction: column; }
.settings-grid .workspace-card, .settings-grid .el-form { display: flex; flex-direction: column; }
.settings-grid .el-form { flex: 1; min-width: 0; }
.settings-footer { margin-top: auto; padding-top: 12px; border-top: 1px solid var(--el-border-color-lighter); }
.mode-list { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); width: 100%; gap: 8px; }
.mode-option {
  height: auto; margin: 0; padding: 8px 12px;
  border: 1px solid var(--el-border-color-light); border-radius: 10px;
  align-items: flex-start; white-space: normal;
}
.mode-option :deep(.el-radio__input) { margin-top: 4px; }
.mode-option.is-checked { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.mode-option :deep(.el-radio__label) { display: grid; gap: 2px; min-width: 0; white-space: normal; }
.settings-grid :deep(.el-input-number) { max-width: 100%; }
.wiki-sources { border-top: 1px solid var(--el-border-color-light); padding-top: 12px; }
@media (max-width: 1200px) {
  .mode-list { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 900px) {
  .settings-grid, .settings-summary { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 480px) {
  .search-controls :deep(.el-radio-button__inner) { padding-inline: 9px; }
}
pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; }
.markdown-preview { overflow-wrap: anywhere; }
</style>
