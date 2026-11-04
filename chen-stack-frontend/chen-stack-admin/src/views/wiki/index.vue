<template>
  <ManagementCard class="ai-management" :title="sectionInfo.title" v-model:model-current-page="listPageNumber" :model-page-size="12" :total="listTotal" :show-pagination="!error || listTotal > 0" pagination-layout="total, prev, pager, next, jumper" @search="changeListPage">
    <template #filters>
      <template v-if="section === 'sources' || section === 'jobs'">
        <KeywordSearch v-model="sourceQuery" :label="section === 'jobs' ? '整理材料' : '来源标题'" :placeholder="section === 'jobs' ? '搜索已授权材料' : '搜索来源标题'" width="260px" @search="searchSources" />
        <SearchButtons @search="searchSources" @reset="resetSources" />
      </template>
      <template v-if="section === 'sources'">
        <el-button v-if="capabilities.wikiCompile" type="primary" plain :disabled="!selectedSources.length || busy" @click="goToTasks">AI整理选中知识</el-button>
      </template>
      <template v-if="section === 'pages'">
        <KeywordSearch v-model="pageQuery" label="知识名称" placeholder="搜索知识名称或正文" width="260px" @search="searchPages" />
        <SearchButtons @search="searchPages" @reset="resetPages" />
        <el-button v-if="capabilities.wikiEdit" type="primary" plain @click="openCreatePage">新增知识</el-button>
      </template>
      <el-button :icon="Refresh" :loading="busy" @click="refresh">刷新</el-button>
    </template>
    <template #table-view>
    <el-alert v-if="error" :title="friendlyError" type="warning" show-icon :closable="false" class="notice" role="alert" />
    <div class="knowledge-content">
      <section v-if="section === 'sources'">
        <p class="hint">只列出当前满足公开、已发布、已审核条件的材料。选中材料不会自动授权。</p>
        <DataTable :loading="sourcesLoading" :data="sourcePage.items" :show-id="false" :show-create-time="false" :show-actions="false" :empty-text="error ? '来源列表暂未加载，请刷新重试' : '暂无合法来源'">
          <el-table-column prop="title" label="来源标题" min-width="240" show-overflow-tooltip />
          <el-table-column label="类型" width="120"><template #default="{ row }">{{ sourceType(row.type) }}</template></el-table-column>
          <el-table-column label="使用授权" width="130"><template #default="{ row }"><StatusBadge :text="row.active ? '已授权' : '未授权'" :custom-class="row.active ? 'status-audited' : 'status-default'" /></template></el-table-column>
          <el-table-column label="操作" width="430" header-align="center"><template #default="{ row }">
            <TableActions show-audit audit-text="检查并授权" show-edit :edit-text="selectedSources.some((item) => item.snapshotId === row.snapshotId) ? '取消选择' : '选择整理'" show-delete delete-text="撤销授权" show-detail detail-text="受影响主题" :disabled="busy" :disabled-actions="[!capabilities.wikiSourceManage && 'audit', (!capabilities.wikiCompile || !row.active || !row.snapshotId) && 'edit', (!row.active || !capabilities.wikiSourceManage) && 'delete'].filter(Boolean)" @audit="inspect(row)" @edit="selectSource(row)" @delete="revoke(row)" @detail="findAffected(row)" />
          </template></el-table-column>
        </DataTable>
        <div v-if="affected.length" class="affected">
          <h3>待更新主题</h3>
          <p class="hint">重新检查并授权当前来源后，可到AI整理页重新生成知识。</p>
          <DataTable :data="affected" :show-id="false" :show-create-time="false" :show-actions="false"><el-table-column prop="topic" label="主题" show-overflow-tooltip /><el-table-column label="操作" width="160" header-align="center"><template #default="{ row }"><TableActions show-edit edit-text="重新整理" :disabled="busy || !capabilities.wikiCompile" @edit="useAffected(row)" /></template></el-table-column></DataTable>
        </div>
      </section>
      <section v-if="section === 'jobs'">
        <el-card shadow="never" class="compile-card">
          <template #header>选择知识并交给AI整理</template>
          <p class="hint">选择已核验授权的材料，AI将生成草稿。草稿经人工审核发布后，才能供小辰使用。</p>
          <div class="toolbar"><el-button @click="router.push('/ai/sources')">管理来源</el-button></div>
          <el-select v-model="selectedSnapshotIds" multiple placeholder="选择已授权的知识材料（最多16份）" class="source-picker"><el-option v-for="source in authorizedSources" :key="source.snapshotId" :label="source.title" :value="source.snapshotId" /></el-select>
          <Pagination v-model:current-page="sourcePageNumber" :total="sourcePage.total" :page-size="12" layout="total, prev, pager, next, jumper" aria-label="来源材料分页" @current-change="perform(loadSources)" />
          <div class="selected-sources"><el-tag v-for="source in selectedSources" :key="source.snapshotId" size="small" closable @close="selectSource(source)">{{ source.title }}</el-tag><span v-if="!selectedSources.length" class="hint">先选择已授权材料；没有合适材料时到“知识来源”检查并授权</span></div>
          <el-form label-position="top" @submit.prevent="compile">
            <div class="form-grid">
              <el-form-item label="限定主题（可选，一行一个，最多 16 个）"><el-input v-model="topicInput" type="textarea" :rows="2" placeholder="留空整理全部主题" /></el-form-item>
            </div>

            <el-button type="primary" native-type="submit" :loading="busy" :disabled="!capabilities.wikiCompile || !selectedSources.length || !requestId">开始AI整理</el-button>
          </el-form>
        </el-card>
        <DataTable :loading="jobsLoading" :data="jobPage.items" :show-id="false" :show-create-time="false" :show-actions="false" :empty-text="error ? '整理记录暂未加载，请刷新重试' : '暂无整理记录'">
          <el-table-column label="知识主题" min-width="180"><template #default="{ row }">{{ row.topics?.join('、') || '全部主题' }}</template></el-table-column>
          <el-table-column label="整理状态" min-width="160"><template #default="{ row }"><div><StatusBadge :text="jobStatus(row.state)" :custom-class="jobBadgeClass(row.state)" /><p v-if="row.firstStop" class="hint">{{ stopReason(row.firstStop) }}</p></div></template></el-table-column>
          <el-table-column label="创建时间" min-width="180"><template #default="{ row }">{{ formatKnowledgeTime(row.createdAt) }}</template></el-table-column>
          <el-table-column label="操作" width="230" header-align="center"><template #default="{ row }"><TableActions show-view view-text="预览草稿" show-delete delete-text="取消" :disabled="busy" :disabled-actions="[row.state !== 'DRAFT_READY' && 'view', (!capabilities.wikiCompile || !['QUEUED', 'RUNNING'].includes(row.state)) && 'delete'].filter(Boolean)" @view="previewDraft(row.requestId)" @delete="cancel(row)" /></template></el-table-column>
        </DataTable>
      </section>
      <section v-if="section === 'pages'">
        <p class="hint">可以阅读每篇知识的完整正文；人工编写或更新仍需选择已授权来源，并在审核通过后发布。</p>
        <DataTable :loading="pagesLoading" :data="visiblePages" :show-id="false" :show-create-time="false" :show-actions="false" :empty-text="error ? '知识页面暂未加载，请刷新重试' : '暂无 Wiki 页面，请先整理来源并创建待审版本'">
          <el-table-column prop="title" label="知识名称" min-width="240" show-overflow-tooltip />
          <el-table-column label="更新时间" min-width="180"><template #default="{ row }">{{ formatKnowledgeTime(row.updatedAt) }}</template></el-table-column>
          <el-table-column label="状态" width="180"><template #default="{ row }"><div><StatusBadge :text="row.currentVersion == null ? '草稿' : row.withdrawn ? '已撤回' : row.publicEligible ? '已发布' : '待更新'" :custom-class="row.currentVersion == null ? 'status-default' : row.withdrawn || !row.publicEligible ? 'status-rejected' : 'status-audited'" /><p v-if="row.statusReason && row.statusReason !== 'PUBLISHED'" class="hint">{{ reasonText(row.statusReason) }}</p></div></template></el-table-column>
          <el-table-column label="操作" width="420" header-align="center"><template #default="{ row }"><TableActions show-view view-text="阅读正文" show-audit audit-text="审核与历史版本" show-delete delete-text="停止使用知识" :disabled="busy" :disabled-actions="row.withdrawn || row.currentVersion == null || !capabilities.wikiPublish ? ['delete'] : []" @view="openPageDetail(row)" @audit="openVersions(row)" @delete="withdraw(row)" /></template></el-table-column>
        </DataTable>
      </section>
    </div>
    </template>

    <el-dialog v-model="sourceVisible" class="ai-management-dialog" title="核查并授权来源" width="min(900px, 95vw)">
      <template v-if="candidate">
        <h3>{{ candidate.title }}</h3><pre class="source-text">{{ candidate.text }}</pre>

        <el-checkbox v-model="sourceConsent">我已检查当前材料，明确同意将此版本作为公开 Wiki 编译来源</el-checkbox>
      </template>
      <template #footer><el-button @click="sourceVisible = false">关闭</el-button><el-button type="primary" :disabled="!sourceConsent || !capabilities.wikiSourceManage" :loading="busy" @click="authorize">确认授权</el-button></template>
    </el-dialog>

    <el-dialog v-model="draftVisible" class="ai-management-dialog" title="草稿预览与人工核查" width="min(1100px, 96vw)">
      <template v-if="draft">
        <el-alert title="草稿需人工核查：引用仅证明来源位置，生成结论、冲突和欠证仍须人工检查。" type="warning" :closable="false" show-icon />
        <p>主题：{{ draft.topics?.join('、') || '全部主题' }} · 引用 {{ citationCount(draft.markdown) }} 处 · {{ formatKnowledgeTime(draft.createdAt) }}</p>

        <div v-if="draft.conflicts?.length" class="notice"><h3>待核查内容</h3><p v-for="(conflict, index) in draft.conflicts" :key="index">{{ readableConflict(conflict) }}</p></div>
        <h3>当前来源</h3><DataTable :data="draft.sources" :show-id="false" :show-create-time="false" :show-actions="false"><el-table-column prop="title" label="来源" show-overflow-tooltip /></DataTable>

        <WikiMarkdown :markdown="readableMarkdown(draft.markdown, draft.sources)" class="draft-preview" />
        <el-divider />
        <el-form label-position="top" @submit.prevent="createRevision">
          <div class="form-grid"><el-form-item label="Wiki 标题（必填）"><el-input v-model="edit.title" maxlength="160" /></el-form-item><el-form-item label="更新已有页面（留空新建）"><el-select v-model="edit.pageId" clearable placeholder="创建新页面"><el-option v-for="page in pages" :key="page.id" :label="page.title" :value="page.id" /></el-select></el-form-item></div>
          <el-form-item label="页面摘要（必填）"><el-input v-model="edit.summary" type="textarea" maxlength="500" :rows="2" /></el-form-item>
          <el-checkbox v-model="edit.changeMarkdown">人工修改正文（修改后创建全新版本，必须重新审核）</el-checkbox>
          <el-form-item v-if="edit.changeMarkdown" label="Markdown 正文"><el-input v-model="edit.markdown" type="textarea" :rows="14" /></el-form-item>
          <el-button native-type="submit" type="primary" :loading="busy" :disabled="!capabilities.wikiEdit || !edit.title.trim() || !edit.summary.trim()">创建待审版本</el-button>
        </el-form>
      </template>
    </el-dialog>

    <el-dialog v-model="versionsVisible" class="ai-management-dialog" title="人工审核与版本历史" width="min(1100px, 96vw)">
      <template v-if="selectedPage">
        <h3>{{ selectedPage.title }} · 当前发布版本 {{ selectedPage.currentVersion ?? '无' }}</h3>
        <DataTable :data="visibleVersions" :show-id="false" :show-create-time="false" :show-actions="false" empty-text="暂无历史版本" highlight-current-row @row-click="selectRevision">
          <el-table-column prop="version" label="版本" width="80" /><el-table-column label="审核状态" width="130"><template #default="{ row }"><StatusBadge :text="revisionStatus(row.state)" :custom-class="row.state === 'PUBLISHED' || row.state === 'APPROVED' ? 'status-audited' : row.state === 'IN_REVIEW' ? 'status-unaudited' : 'status-default'" /></template></el-table-column><el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip /><el-table-column label="创建时间" min-width="180"><template #default="{ row }">{{ formatKnowledgeTime(row.createdAt) }}</template></el-table-column><el-table-column label="发布状态" width="100"><template #default="{ row }">{{ row.current ? '当前发布' : '—' }}</template></el-table-column>
        </DataTable>
        <Pagination v-if="versions.length > 12" v-model:current-page="versionPageNumber" :total="versions.length" :page-size="12" layout="total, prev, pager, next, jumper" />
        <template v-if="revision">
          <p>当前选择：版本 {{ revision.version }} · {{ revisionStatus(revision.state) }}</p>
          <el-button :disabled="busy" @click="previewEvidence">核查来源与内容</el-button>
          <WikiMarkdown :markdown="readableMarkdown(revision.markdown, draft?.requestId === revision.requestId ? draft.sources : [])" class="draft-preview" />
          <el-checkbox v-model="evidenceConsent">已检查当前来源、引用证据及生成结论</el-checkbox><br />
          <el-checkbox v-model="conflictConsent">已检查并处理冲突、欠证和待人工核查内容</el-checkbox>
          <div class="revision-actions">
            <el-button :loading="busy" :disabled="!capabilities.wikiEdit || revision.state !== 'DRAFT'" @click="revisionAction('submit')">提交审核</el-button>
            <el-button type="success" :loading="busy" :disabled="!capabilities.wikiReview || revision.state !== 'IN_REVIEW' || !evidenceConsent || !conflictConsent || !evidenceSeen" @click="revisionAction('approve')">审核通过</el-button>
            <el-button type="primary" :loading="busy" :disabled="!capabilities.wikiPublish || revision.state !== 'APPROVED'" @click="revisionAction('publish')">正式发布</el-button>
            <el-button :loading="busy" :disabled="!capabilities.wikiPublish || revision.state !== 'PUBLISHED' || (revision.current && !selectedPage.withdrawn)" @click="restore">恢复此历史版本</el-button>
          </div>
        </template>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" class="ai-management-dialog" title="知识正文" width="min(1100px, 96vw)">
      <template v-if="detail">
        <div class="mode-summary">
          <StatusBadge :text="detailStatusText" :custom-class="detail.publicEligible ? 'status-audited' : 'status-rejected'" />
          <span class="hint">{{ detail.statusDetail }}</span>
        </div>
        <h3>{{ detail.title }}</h3>
        <p>版本 {{ detail.version }} · 发布时间 {{ formatKnowledgeTime(detail.publishedAt) }}</p>
        <p>摘要：{{ detail.summary }}</p>
        <p v-if="detail.topics?.length">主题：{{ detail.topics.join('、') }}</p>
        <h3>引用来源</h3>
        <ul v-if="detail.citations?.length" class="sources">
          <li v-for="(citation, index) in detail.citations" :key="index">{{ citation.title }}（{{ citation.version }}）<a v-if="citation.url" :href="citation.url" target="_blank" rel="noreferrer">查看来源</a></li>
        </ul>
        <p v-else class="hint">当前版本没有附加引用来源；如为人工编写，请核对正文与真实业务一致。</p>
        <WikiMarkdown :markdown="detail.markdown" class="draft-preview" />
        <div class="revision-actions">
          <el-button v-if="capabilities.wikiEdit" type="primary" :disabled="busy" @click="editFromDetail">编辑正文并新建版本</el-button>
          <el-button :disabled="busy" @click="openVersionsFromDetail">审核与历史版本</el-button>
          <el-button v-if="capabilities.wikiPublish && detail.publicEligible" type="danger" plain :disabled="busy" @click="withdrawFromDetail">停止使用知识</el-button>
        </div>
      </template>
      <template #footer><el-button @click="detailVisible = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="editorVisible" class="ai-management-dialog" :title="editor.pageId ? '编辑正文并新建版本' : '新增知识'" width="min(1100px, 96vw)" :close-on-click-modal="!busy">
      <el-alert v-if="editor.pageId" title="保存后创建待审版本，不会立即替换已发布正文；审核通过并发布后才生效。" type="info" :closable="false" show-icon />
      <el-alert v-else title="请选择已核验并授权的来源，再人工编写正文；创建后进入待审版本，不会立即公开。" type="info" :closable="false" show-icon />
      <el-form label-position="top">
        <el-form-item v-if="!editor.pageId" label="引用来源（至少一份已授权来源）">
          <el-select v-model="editor.snapshotIds" multiple placeholder="选择已授权来源" class="source-picker">
            <el-option v-for="source in manualSources" :key="source.snapshotId" :label="source.title" :value="source.snapshotId" />
          </el-select>
          <p v-if="manualSourcesLoading" class="hint">正在读取已授权来源…</p>
          <p v-else-if="!manualSources.length" class="hint">暂无已授权来源，请先到“知识来源”检查并授权。</p>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="知识标题"><el-input v-model="editor.title" maxlength="160" show-word-limit /></el-form-item>
          <el-form-item label="摘要"><el-input v-model="editor.summary" maxlength="500" show-word-limit /></el-form-item>
        </div>
        <el-form-item label="正文（Markdown）"><el-input v-model="editor.markdown" type="textarea" :rows="14" placeholder="写清真实入口、步骤、必要条件和当前限制" /></el-form-item>
        <el-checkbox v-model="editor.preview">预览正文</el-checkbox>
        <WikiMarkdown v-if="editor.preview" :markdown="editor.markdown" class="draft-preview" />
      </el-form>
      <template #footer>
        <el-button :disabled="busy" @click="editorVisible = false">关闭</el-button>
        <el-button type="primary" :loading="busy" :disabled="!validEditor || busy" @click="savePage">创建待审版本</el-button>
      </template>
    </el-dialog>
  </ManagementCard>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import ManagementCard from '@/components/management/ManagementCard.vue'
import DataTable from '@/components/data/DataTable.vue'
import TableActions from '@/components/data/TableActions.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import Pagination from '@/components/data/Pagination.vue'
import KeywordSearch from '@/components/search/KeywordSearch.vue'
import SearchButtons from '@/components/search/SearchButtons.vue'
import WikiMarkdown from '@/components/wiki/WikiMarkdown.vue'
import { readableWikiMarkdown as readableMarkdown } from '@/utils/wikiPresentation'
import { wikiApi } from '@/api/wiki'
import { knowledgeApi } from '@/api/knowledge'
import { formatKnowledgeTime } from '@/utils/aiPresentation'
import { useRouter, useRoute } from 'vue-router'

const props = defineProps({ section: { type: String, default: 'sources' } })
const router = useRouter()
const route = useRoute()
const sectionInfo = computed(() => ({ sources: { title: '知识来源' }, jobs: { title: '智能整理' }, pages: { title: '整理知识' } }[props.section]))
const capabilities = ref({})
const pageQuery = ref('')
const pageNumber = ref(1)
const visiblePages = computed(() => pages.value.slice((pageNumber.value - 1) * 12, pageNumber.value * 12))
const busy = ref(false)
const error = ref('')
const friendlyError = computed(() => !/[\u3400-\u9fff]/.test(error.value) ? '操作未完成，请稍后重试。' : error.value)
const sourcesLoading = ref(false)
const jobsLoading = ref(false)
const pagesLoading = ref(false)
const sourceQuery = ref('')
const sourcePageNumber = ref(1)
const jobPageNumber = ref(1)
const sourcePage = ref({ items: [], total: 0 })
const jobPage = ref({ items: [], total: 0 })
const pages = ref([])
const listPageNumber = computed({ get: () => props.section === 'sources' ? sourcePageNumber.value : props.section === 'jobs' ? jobPageNumber.value : pageNumber.value, set: (value) => { if (props.section === 'sources') sourcePageNumber.value = value; else if (props.section === 'jobs') jobPageNumber.value = value; else pageNumber.value = value } })
const listTotal = computed(() => props.section === 'sources' ? sourcePage.value.total : props.section === 'jobs' ? jobPage.value.total : pages.value.length)
const selectedSources = ref([])
const authorizedSources = computed(() => [...new Map([...selectedSources.value, ...sourcePage.value.items.filter((source) => source.active && source.snapshotId)].map((source) => [source.snapshotId, source])).values()])
const selectedSnapshotIds = computed({ get: () => selectedSources.value.map((source) => source.snapshotId), set: (ids) => { if (ids.length <= 16) selectedSources.value = authorizedSources.value.filter((source) => ids.includes(source.snapshotId)); else ElMessage.warning('每次最多选择16份材料') } })
const requestId = ref('')
const topicInput = ref('')
const affected = ref([])
const candidate = ref(null)
const sourceVisible = ref(false)
const sourceConsent = ref(false)
const draft = ref(null)
const draftVisible = ref(false)
const edit = reactive({ title: '', summary: '', pageId: undefined, markdown: '', changeMarkdown: false })
const selectedPage = ref(null)
const versionsVisible = ref(false)
const versions = ref([])
const versionPageNumber = ref(1)
const visibleVersions = computed(() => versions.value.slice((versionPageNumber.value - 1) * 12, versionPageNumber.value * 12))
const revision = ref(null)
const evidenceSeen = ref(false)
const evidenceConsent = ref(false)
const conflictConsent = ref(false)
const detail = ref(null)
const detailVisible = ref(false)
const detailStatusText = computed(() => ({ PUBLISHED: '已发布', WITHDRAWN: '已撤回', UNPUBLISHED: '未发布', SOURCE_CHANGED: '待更新', DRAFT_INVALID: '待更新' }[detail.value?.statusReason] || '待更新'))
const manualSources = ref([])
const manualSourcesLoading = ref(false)
const editorVisible = ref(false)
const editor = reactive({ pageId: null, title: '', summary: '', markdown: '', snapshotIds: [], preview: false, requestId: '' })
const validEditor = computed(() => Boolean(editor.title.trim() && editor.summary.trim() && editor.markdown.trim() && (editor.pageId || editor.snapshotIds.length)))
let poll
let disposed = false

function sourceType(type) { return { ARTICLE: '文章', PLATFORM_HELP: '平台帮助', PRODUCT_CAPABILITY: '产品说明', KNOWLEDGE: '知识文档' }[type] || '知识材料' }
function goToTasks() { return router.push({ path: '/ai/tasks', query: { sources: selectedSources.value.map((source) => source.snapshotId).join(','), ...(topicInput.value ? { topic: topicInput.value } : {}) }, state: { knowledgeSources: selectedSources.value.map(({ snapshotId, title, active }) => ({ snapshotId, title, active })) } }) }
function jobStatus(state) {
  return { QUEUED: '等待整理', RUNNING: '整理中', DRAFT_READY: '草稿已生成', FAILED: '整理未完成', CANCELLED: '已取消', INVALIDATED: '来源已失效' }[state] || '状态未知'
}
function jobBadgeClass(state) { return { QUEUED: 'status-unaudited', RUNNING: 'status-unaudited', DRAFT_READY: 'status-audited', FAILED: 'status-rejected', INVALIDATED: 'status-rejected' }[state] || 'status-default' }
function stopReason(reason) {
  return { CANCELLED: '任务已取消。', WIKI_COMPILE_TIMEOUT: '整理用时较长，请稍后重新发起。', WIKI_COMPILE_INTERRUPTED: '整理已中断，可重新发起。', WIKI_TOPIC_SET_INVALID: '所选来源未生成指定主题，请调整主题或重新选择来源。', RECOVERED_UNKNOWN: '任务恢复时无法确认此前调用结果。' }[reason] || '整理未完成，请检查材料后重新发起。'
}
function revisionStatus(state) {
  return { DRAFT: '待提交审核', IN_REVIEW: '审核中', APPROVED: '审核通过', PUBLISHED: '已发布' }[state] || '状态未知'
}
function readableConflict(value) {
  return String(value).replaceAll('CONFLICT_CANDIDATE', '来源陈述存在差异').replaceAll('UNSUPPORTED', '缺少证据').replaceAll('SEMANTIC_NOT_VERIFIED', '待人工核查')
}
function citationCount(markdown) {
  return (typeof markdown === 'string' ? markdown.match(/^\s*- snapshot=.*? quote=“/gm) : null)?.length || 0
}
async function perform(action, message) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await action()
    if (message) ElMessage.success(message)
  } catch (failure) {
    error.value = failure?.response?.data?.msg || failure?.msg || failure?.message || '操作失败，请刷新后重试'
    ElMessage.error(friendlyError.value)
  } finally { busy.value = false }
}
async function loadSources() {
  sourcesLoading.value = true
  try { sourcePage.value = (await wikiApi.sources({ q: sourceQuery.value, page: sourcePageNumber.value, size: 12 })).data }
  finally { sourcesLoading.value = false }
}
async function loadJobs() {
  jobsLoading.value = true
  try { jobPage.value = (await wikiApi.jobs({ page: jobPageNumber.value, size: 12 })).data }
  finally { jobsLoading.value = false }
}
async function loadPages() {
  pagesLoading.value = true
  try { pages.value = (await wikiApi.pages({ q: pageQuery.value.trim() })).data }
  finally { pagesLoading.value = false }
}
function refresh() { return perform(async () => { capabilities.value = (await knowledgeApi.status()).data.capabilities; if (props.section === 'sources') await loadSources(); else if (props.section === 'jobs') await Promise.all([loadSources(), loadJobs(), loadPages()]); else await loadPages() }) }
function searchSources() { sourcePageNumber.value = 1; return perform(loadSources) }
function resetSources() { sourceQuery.value = ''; return searchSources() }
function searchPages() { pageNumber.value = 1; return perform(loadPages) }
function resetPages() { pageQuery.value = ''; return searchPages() }
function reasonText(code) {
  return {
    SOURCE_CHANGED: '引用来源已变化，需要重新核对来源后更新',
    DRAFT_INVALID: '草稿状态已变化，需要重新整理',
    WITHDRAWN: '已撤回，公开和小辰都不会使用',
    UNPUBLISHED: '尚未发布',
  }[code] || ''
}
async function openPageDetail(page) {
  return perform(async () => {
    detail.value = null
    detail.value = (await wikiApi.pageDetail(page.id)).data
    detailVisible.value = true
  })
}
function openVersionsFromDetail() {
  const target = { id: detail.value.id }
  detailVisible.value = false
  return openVersions(target)
}
function editFromDetail() {
  Object.assign(editor, {
    pageId: detail.value.id, title: detail.value.title, summary: detail.value.summary,
    markdown: detail.value.markdown, snapshotIds: [], preview: false, requestId: '',
  })
  detailVisible.value = false
  editorVisible.value = true
}
async function loadManualSources() {
  if (manualSources.value.length || manualSourcesLoading.value) return
  manualSourcesLoading.value = true
  try {
    const page = (await wikiApi.sources({ q: '', page: 1, size: 50 })).data
    manualSources.value = (page.items || []).filter((source) => source.active && source.snapshotId)
  } catch (failure) {
    manualSources.value = []
  } finally { manualSourcesLoading.value = false }
}
function openCreatePage() {
  Object.assign(editor, { pageId: null, title: '', summary: '', markdown: '', snapshotIds: [], preview: false, requestId: `manual-${crypto.randomUUID()}` })
  editorVisible.value = true
  return loadManualSources()
}
async function savePage() {
  if (busy.value || !validEditor.value) return
  busy.value = true
  error.value = ''
  try {
    const body = { title: editor.title.trim(), summary: editor.summary.trim(), markdown: editor.markdown }
    let result
    if (editor.pageId) result = (await wikiApi.editPage(editor.pageId, body)).data
    else result = (await wikiApi.createManualPage({ requestId: editor.requestId, ...body, snapshotIds: editor.snapshotIds })).data
    editorVisible.value = false
    ElMessage.success('已创建待审版本，请检查并提交审核')
    await loadPages()
    if (result?.pageId) await loadVersions({ id: result.pageId })
  } catch (failure) {
    error.value = failure?.response?.data?.msg || failure?.msg || failure?.message || '操作失败，请刷新后重试'
    ElMessage.error(friendlyError.value)
  } finally { busy.value = false }
}
function changeListPage() { if (props.section === 'sources') return perform(loadSources); if (props.section === 'jobs') return perform(loadJobs) }
function newRequestId() { requestId.value = `wiki-${crypto.randomUUID()}` }
function selectSource(source) {
  const found = selectedSources.value.findIndex((item) => item.snapshotId === source.snapshotId)
  if (found >= 0) selectedSources.value.splice(found, 1)
  else if (source.snapshotId && selectedSources.value.length < 16) selectedSources.value.push(source)
  else ElMessage.warning('每个任务最多选择 16 份已授权来源')
}
function inspect(source) { return perform(async () => { candidate.value = null; sourceConsent.value = false; candidate.value = (await wikiApi.inspect({ type: source.type, id: source.id })).data; sourceVisible.value = true }) }
function authorize() {
  if (!candidate.value || !sourceConsent.value) return
  return perform(async () => {
    const source = (await wikiApi.authorize({ type: candidate.value.type, id: candidate.value.id, fingerprint: candidate.value.fingerprint })).data
    selectedSources.value = selectedSources.value.filter((item) => item.type !== source.type || item.id !== source.id)
    if (selectedSources.value.length < 16) selectedSources.value.push(source)
    else ElMessage.warning('授权已生效；当前任务已选满 16 份材料，请先移出一份再加入')
    sourceVisible.value = false
    await loadSources()
  }, '已确认授权')
}
async function confirm(message) { try { await ElMessageBox.confirm(message, '确认操作', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }); return true } catch { return false } }
async function revoke(source) {
  if (!await confirm('撤销后，依赖此授权的草稿和公开页面将失效。继续撤销？')) return
  return perform(async () => { await wikiApi.revoke({ type: source.type, id: source.id }); selectedSources.value = selectedSources.value.filter((item) => item.type !== source.type || item.id !== source.id); await loadSources() }, '来源授权已撤销')
}
function findAffected(source) { return perform(async () => { affected.value = (await wikiApi.affected({ type: source.type, id: source.id })).data; if (!affected.value.length) ElMessage.info('此来源暂无受影响主题') }) }
function useAffected(target) { topicInput.value = target.topic; return goToTasks() }
function compile() {
  return perform(async () => {
    const topics = topicInput.value.split('\n').map((value) => value.trim()).filter(Boolean)
    if (topics.length > 16) throw new Error('限定主题最多 16 个')
    if (!/^[A-Za-z0-9_.:-]{1,80}$/.test(requestId.value)) throw new Error('任务编号只允许字母、数字、下划线、点、冒号和连字符，最多 80 位')
    await wikiApi.compile({ requestId: requestId.value, snapshotIds: selectedSources.value.map((source) => source.snapshotId), topics })
    jobPageNumber.value = 1
    await loadJobs()
    newRequestId()
  }, '编译任务已创建')
}
function cancel(job) { return perform(async () => { await wikiApi.cancel(job.requestId); await loadJobs() }, '已处理取消请求；终态任务保持原状态') }
function previewDraft(id) {
  return perform(async () => {
    draft.value = null
    draft.value = (await wikiApi.draft(id)).data
    Object.assign(edit, { title: draft.value.topics?.[0] || '', summary: '', pageId: undefined, markdown: draft.value.markdown, changeMarkdown: false })
    draftVisible.value = true
  })
}
function createRevision() {
  return perform(async () => {
    const result = (await wikiApi.revision({ requestId: draft.value.requestId, title: edit.title.trim(), summary: edit.summary, pageId: edit.pageId || undefined, markdown: edit.changeMarkdown ? edit.markdown : undefined })).data
    draftVisible.value = false
    await loadPages()
    const page = pages.value.find((item) => item.id === result.pageId)
    if (page) await loadVersions(page)
  }, '已创建新版本，请检查并提交审核')
}
async function loadVersions(page) {
  selectedPage.value = pages.value.find((item) => item.id === page.id) || page
  versions.value = (await wikiApi.versions(page.id)).data
  versionPageNumber.value = 1
  selectRevision(versions.value[0] || null)
  versionsVisible.value = true
}
function openVersions(page) { return perform(() => loadVersions(page)) }
function selectRevision(value) { revision.value = value; evidenceSeen.value = false; evidenceConsent.value = false; conflictConsent.value = false }
function previewEvidence() {
  return perform(async () => {
    evidenceSeen.value = false
    draft.value = null
    draft.value = (await wikiApi.draft(revision.value.requestId)).data
    Object.assign(edit, { title: revision.value.title, summary: revision.value.summary, pageId: revision.value.pageId, markdown: revision.value.markdown, changeMarkdown: true })
    draftVisible.value = true
    evidenceSeen.value = true
  })
}
function revisionAction(action) {
  return perform(async () => {
    const data = { bodyHash: revision.value.bodyHash }
    if (action === 'approve') Object.assign(data, { confirmEvidence: evidenceConsent.value, confirmConflicts: conflictConsent.value })
    if (action === 'publish') data.expectedCurrentVersion = selectedPage.value.currentVersion
    const currentId = revision.value.id
    await wikiApi[action](currentId, data)
    await loadPages()
    await loadVersions(selectedPage.value)
    selectRevision(versions.value.find((item) => item.id === currentId))
  }, '版本状态已更新')
}
async function withdraw(page) {
  if (!await confirm('撤回后，此 Wiki 页面将立即停止公开。继续撤回？')) return
  return perform(async () => {
    await wikiApi.withdraw(page.id, { expectedCurrentVersion: page.currentVersion })
    detailVisible.value = false
    await loadPages()
  }, '页面已撤回')
}
function withdrawFromDetail() {
  return withdraw({ id: detail.value.id, currentVersion: detail.value.version })
}
async function restore() {
  if (!await confirm('恢复将重验当前来源，并将此已审核历史版本设为当前发布版本。继续恢复？')) return
  return perform(async () => { await wikiApi.restore(selectedPage.value.id, { revisionId: revision.value.id, expectedCurrentVersion: selectedPage.value.currentVersion }); await loadPages(); await loadVersions(selectedPage.value) }, '历史版本已受控恢复')
}
async function pollJobs() {
  if (disposed) return
  if (!busy.value && jobPage.value.items.some((job) => ['QUEUED', 'RUNNING'].includes(job.state))) {
    try { await loadJobs() } catch (failure) { error.value = failure?.message || '任务状态刷新失败' }
  }
  if (!disposed) poll = setTimeout(pollJobs, 2000)
}
onMounted(async () => {
  newRequestId()
  await refresh()
  if (props.section === 'jobs') {
    const ids = String(route?.query?.sources || '').split(',')
    selectedSources.value = [...new Map([...(history.state?.knowledgeSources || []), ...sourcePage.value.items].filter((source) => source.active && ids.includes(String(source.snapshotId))).map((source) => [source.snapshotId, source])).values()].slice(0, 16)
    topicInput.value = String(route?.query?.topic || '')
    if (!disposed) poll = setTimeout(pollJobs, 2000)
  }
})
onUnmounted(() => { disposed = true; clearTimeout(poll) })
</script>

<style lang="scss" src="@/assets/scss/ai-management.scss"></style>
