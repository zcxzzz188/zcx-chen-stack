<template>
  <section class="wiki-management">
    <PageHeader title="Wiki 知识管理" description="检查并授权来源，编译草稿，审核后发布给小辰使用。引用有效不代表生成结论已完成核查。">
      <template #actions><el-button :loading="busy" @click="refresh">刷新</el-button></template>
    </PageHeader>
    <el-alert v-if="error" :title="friendlyError" type="error" show-icon :closable="false" class="notice" role="alert"><details><summary>技术详情</summary>{{ error }}</details></el-alert>
    <p class="hint">操作顺序：检查并授权来源 → 编译草稿 → 检查事实与引用 → 提交审核 → 审核并发布。需要停止使用时可撤回页面或撤销来源授权。</p>
    <el-tabs v-model="tab" class="wiki-tabs">
      <el-tab-pane label="知识来源" name="sources">
        <div class="toolbar">
          <el-input v-model="sourceQuery" placeholder="搜索合法来源标题" clearable @keyup.enter="searchSources" />
          <el-button :disabled="busy" @click="searchSources">搜索来源</el-button>
        </div>
        <p class="hint">只列出当前满足公开、已发布、已审核条件的材料。选中材料不会自动授权。</p>
        <el-table v-loading="sourcesLoading" :data="sourcePage.items" empty-text="暂无合法来源">
          <el-table-column prop="title" label="来源标题" min-width="200" />
          <el-table-column prop="type" label="类型" width="150" />
          <el-table-column label="编译授权" width="130"><template #default="{ row }"><el-tag :type="row.active ? 'success' : 'info'">{{ row.active ? '已授权' : '未授权' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" min-width="300"><template #default="{ row }">
            <el-button link type="primary" :disabled="busy" @click="inspect(row)">检查并授权</el-button>
            <el-button link type="primary" :disabled="busy || !row.active || !row.snapshotId" @click="selectSource(row)">{{ selectedSources.some((item) => item.snapshotId === row.snapshotId) ? '移出编译' : '加入编译' }}</el-button>
            <el-button link type="danger" :disabled="!row.active || busy" @click="revoke(row)">撤销授权</el-button>
            <el-button link :disabled="busy" @click="findAffected(row)">受影响主题</el-button>
          </template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="sourcePageNumber" :total="sourcePage.total" :page-size="12" layout="prev, pager, next" @current-change="perform(loadSources)" />
        <div v-if="affected.length" class="affected">
          <h3>需要检查与重编译的主题</h3>
          <p class="hint">重新检查并授权当前来源后，用新任务编号发起重编译。</p>
          <el-table :data="affected"><el-table-column prop="topic" label="主题" /><el-table-column label="技术详情"><template #default="{ row }"><details><summary>查看原任务</summary>{{ row.previousRequestId }}</details></template></el-table-column><el-table-column label="操作"><template #default="{ row }"><el-button link @click="useAffected(row)">填入重编译主题</el-button></template></el-table-column></el-table>
        </div>
      </el-tab-pane>
      <el-tab-pane label="编译任务与草稿" name="jobs">
        <el-card shadow="never" class="compile-card">
          <template #header>创建编译任务</template>
          <p class="hint">请选择已授权来源，可按主题整理知识。服务端未启用编译器时会明确提示。</p>
          <div class="selected-sources"><el-tag v-for="source in selectedSources" :key="source.snapshotId" closable @close="selectSource(source)">{{ source.title }}</el-tag><span v-if="!selectedSources.length" class="hint">先到“知识来源”检查、授权并加入材料</span></div>
          <el-form label-position="top" @submit.prevent="compile">
            <div class="form-grid">
              <el-form-item label="限定主题（可选，一行一个，最多 16 个）"><el-input v-model="topicInput" type="textarea" :rows="2" placeholder="留空编译所有主题" /></el-form-item>
            </div>
            <details><summary>技术详情：任务编号</summary><el-form-item label="任务编号"><el-input v-model="requestId" maxlength="80" placeholder="唯一任务编号" /><el-button link @click="newRequestId">生成新编号</el-button></el-form-item></details>
            <el-button type="primary" native-type="submit" :loading="busy" :disabled="!selectedSources.length || !requestId">发起编译</el-button>
          </el-form>
        </el-card>
        <el-table v-loading="jobsLoading" :data="jobPage.items" empty-text="暂无编译任务">
          <el-table-column label="知识主题" min-width="180"><template #default="{ row }">{{ row.topics?.join('、') || '全部主题' }}<details><summary>技术详情</summary><div>任务编号：{{ row.requestId }}</div><div>原始状态：{{ row.state }}</div><div>调用 {{ row.usedCalls ?? 0 }} / {{ row.maxCalls ?? '—' }}</div><div>预算单位 {{ row.usedUnits ?? 0 }} / {{ row.maxUnits ?? '—' }}</div><div v-if="row.firstStop">停止原因：{{ row.firstStop }}</div></details></template></el-table-column>
          <el-table-column label="编译状态" min-width="110"><template #default="{ row }">{{ jobStatus(row.state) }}<p v-if="row.firstStop" class="hint">{{ stopReason(row.firstStop) }}</p></template></el-table-column>
          <el-table-column label="调用次数" min-width="110"><template #default="{ row }">{{ row.usedCalls ?? 0 }} / {{ row.maxCalls ?? '—' }}</template></el-table-column>
          <el-table-column prop="createdAt" label="创建时间" min-width="180" />
          <el-table-column label="操作" min-width="230"><template #default="{ row }"><el-button link type="primary" :disabled="busy || row.state !== 'DRAFT_READY'" @click="previewDraft(row.requestId)">预览草稿</el-button><el-button link type="danger" :disabled="busy || !['QUEUED', 'RUNNING'].includes(row.state)" @click="cancel(row)">取消</el-button><p class="hint">{{ jobHint(row.state) }}</p></template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="jobPageNumber" :total="jobPage.total" :page-size="12" layout="prev, pager, next" @current-change="perform(loadJobs)" />
      </el-tab-pane>
      <el-tab-pane label="审核、发布与版本" name="pages">
        <el-alert title="正文改动须创建新版本并重新审核；发布和历史恢复都会重验当前来源。" type="info" :closable="false" show-icon class="notice" />
        <el-table v-loading="pagesLoading" :data="pages" empty-text="暂无 Wiki 页面，请先从编译草稿创建待审版本">
          <el-table-column prop="title" label="Wiki 页面" min-width="240" />
          <el-table-column label="当前发布版本" min-width="150"><template #default="{ row }">{{ row.currentVersion ?? '未发布' }}</template></el-table-column>
          <el-table-column label="状态" width="160"><template #default="{ row }"><el-tag :type="row.currentVersion == null ? 'info' : row.withdrawn || !row.publicEligible ? 'warning' : 'success'">{{ row.currentVersion == null ? '草稿' : row.withdrawn ? '已撤回' : row.publicEligible ? '已发布' : '来源或版本失效，需重编译核查' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" min-width="220"><template #default="{ row }"><el-button link type="primary" :disabled="busy" @click="openVersions(row)">审核与历史版本</el-button><el-button link type="danger" :disabled="row.withdrawn || row.currentVersion == null || busy" @click="withdraw(row)">撤回公开页面</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="sourceVisible" title="检查来源并显式授权" width="min(900px, 95vw)">
      <template v-if="candidate">
        <h3>{{ candidate.title }}</h3><pre class="source-text">{{ candidate.text }}</pre>
        <details><summary>技术详情</summary><p>{{ candidate.type }} / {{ candidate.id }} · {{ candidate.originVersion }} · {{ candidate.parserVersion }}</p><p class="hash">Fingerprint：{{ candidate.fingerprint }}</p></details>
        <el-checkbox v-model="sourceConsent">我已检查当前材料，明确同意将此版本作为公开 Wiki 编译来源</el-checkbox>
      </template>
      <template #footer><el-button @click="sourceVisible = false">关闭</el-button><el-button type="primary" :disabled="!sourceConsent" :loading="busy" @click="authorize">授权此精确版本</el-button></template>
    </el-dialog>

    <el-dialog v-model="draftVisible" title="草稿预览与人工核查" width="min(1100px, 96vw)">
      <template v-if="draft">
        <el-alert title="草稿需人工核查：引用仅证明来源位置，生成结论、冲突和欠证仍须人工检查。" type="warning" :closable="false" show-icon />
        <p>主题：{{ draft.topics?.join('、') || '全部主题' }} · 引用 {{ citationCount(draft.markdown) }} 处 · {{ draft.createdAt }}</p>
        <details><summary>技术详情</summary><p>状态：DRAFT · SEMANTIC_NOT_VERIFIED</p><p>{{ draft.compilerVersion }} · 任务：{{ draft.requestId }}</p><p class="hash">Body：{{ draft.bodyHash }}<br />Manifest：{{ draft.manifestHash }}</p><pre class="source-text">{{ draft.markdown }}</pre></details>
        <div v-if="draft.conflicts?.length" class="notice"><h3>检测到的冲突与核查标记</h3><p v-for="(conflict, index) in draft.conflicts" :key="index">{{ readableConflict(conflict) }}</p></div>
        <h3>当前来源</h3><el-table :data="draft.sources"><el-table-column prop="title" label="来源" /></el-table>
        <details><summary>来源技术详情</summary><el-table :data="draft.sources"><el-table-column prop="title" label="来源" /><el-table-column prop="originVersion" label="来源版本" show-overflow-tooltip /><el-table-column prop="parserVersion" label="解析版本" /><el-table-column prop="snapshotId" label="快照" /><el-table-column prop="generation" label="授权代次" /><el-table-column prop="fingerprint" label="Fingerprint" show-overflow-tooltip /></el-table></details>
        <WikiMarkdown :markdown="readableMarkdown(draft.markdown, draft.sources)" class="draft-preview" />
        <el-divider />
        <el-form label-position="top" @submit.prevent="createRevision">
          <div class="form-grid"><el-form-item label="Wiki 标题（必填）"><el-input v-model="edit.title" maxlength="160" /></el-form-item><el-form-item label="更新已有页面（留空新建）"><el-select v-model="edit.pageId" clearable placeholder="创建新页面"><el-option v-for="page in pages" :key="page.id" :label="`${page.title} (#${page.id})`" :value="page.id" /></el-select></el-form-item></div>
          <el-form-item label="页面摘要（必填）"><el-input v-model="edit.summary" type="textarea" maxlength="500" :rows="2" /></el-form-item>
          <el-checkbox v-model="edit.changeMarkdown">人工修改正文（修改后创建全新版本，必须重新审核）</el-checkbox>
          <el-form-item v-if="edit.changeMarkdown" label="Markdown 正文"><el-input v-model="edit.markdown" type="textarea" :rows="14" /></el-form-item>
          <el-button native-type="submit" type="primary" :loading="busy" :disabled="!edit.title.trim() || !edit.summary.trim()">创建待审版本</el-button>
        </el-form>
      </template>
    </el-dialog>

    <el-dialog v-model="versionsVisible" title="人工审核与版本历史" width="min(1100px, 96vw)">
      <template v-if="selectedPage">
        <h3>{{ selectedPage.title }} · 当前发布版本 {{ selectedPage.currentVersion ?? '无' }}</h3>
        <el-table :data="versions" empty-text="暂无历史版本" highlight-current-row @row-click="selectRevision">
          <el-table-column prop="version" label="版本" width="80" /><el-table-column label="审核状态" width="160"><template #default="{ row }">{{ revisionStatus(row.state) }}<details><summary>技术详情</summary>{{ row.state }}</details></template></el-table-column><el-table-column prop="title" label="标题" /><el-table-column prop="createdAt" label="创建时间" min-width="180" /><el-table-column label="当前指针" width="100"><template #default="{ row }">{{ row.current ? '当前发布' : '—' }}</template></el-table-column>
        </el-table>
        <template v-if="revision">
          <p>当前选择：版本 {{ revision.version }} · {{ revisionStatus(revision.state) }}</p><details><summary>技术详情</summary><p class="hash">Body：{{ revision.bodyHash }}<br />来源清单：{{ revision.manifestHash }}</p><pre class="source-text">{{ revision.markdown }}</pre></details>
          <el-button :disabled="busy" @click="previewEvidence">重新检查此版本的来源与编译冲突</el-button>
          <WikiMarkdown :markdown="readableMarkdown(revision.markdown, draft?.requestId === revision.requestId ? draft.sources : [])" class="draft-preview" />
          <el-checkbox v-model="evidenceConsent">已检查当前来源、引用证据及生成结论</el-checkbox><br />
          <el-checkbox v-model="conflictConsent">已检查并处理冲突、欠证和待人工核查内容</el-checkbox>
          <div class="revision-actions">
            <el-button :loading="busy" :disabled="revision.state !== 'DRAFT'" @click="revisionAction('submit')">提交审核</el-button>
            <el-button type="success" :loading="busy" :disabled="revision.state !== 'IN_REVIEW' || !evidenceConsent || !conflictConsent || !evidenceSeen" @click="revisionAction('approve')">审核通过</el-button>
            <el-button type="primary" :loading="busy" :disabled="revision.state !== 'APPROVED'" @click="revisionAction('publish')">正式发布</el-button>
            <el-button :loading="busy" :disabled="revision.state !== 'PUBLISHED' || (revision.current && !selectedPage.withdrawn)" @click="restore">恢复此历史版本</el-button>
          </div>
        </template>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import PageHeader from '@/components/management/PageHeader.vue'
import WikiMarkdown from '@/components/wiki/WikiMarkdown.vue'
import { wikiApi } from '@/api/wiki'

const tab = ref('sources')
const busy = ref(false)
const error = ref('')
const friendlyError = computed(() => /^[A-Z0-9_.:-]+$/.test(error.value) ? '操作未完成，请查看技术详情或重试。' : error.value)
const sourcesLoading = ref(false)
const jobsLoading = ref(false)
const pagesLoading = ref(false)
const sourceQuery = ref('')
const sourcePageNumber = ref(1)
const jobPageNumber = ref(1)
const sourcePage = ref({ items: [], total: 0 })
const jobPage = ref({ items: [], total: 0 })
const pages = ref([])
const selectedSources = ref([])
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
const revision = ref(null)
const evidenceSeen = ref(false)
const evidenceConsent = ref(false)
const conflictConsent = ref(false)
let poll
let disposed = false

function jobStatus(state) {
  return { QUEUED: '等待编译', RUNNING: '编译中', DRAFT_READY: '草稿已生成', FAILED: '编译失败', CANCELLED: '已取消', INVALIDATED: '来源已失效' }[state] || '状态未知'
}
function jobHint(state) {
  if (state === 'QUEUED' || state === 'RUNNING') return '草稿尚未生成，暂不能预览；可取消任务。'
  if (state === 'DRAFT_READY') return '可预览草稿；任务已结束，不能取消。'
  if (state === 'FAILED' || state === 'CANCELLED' || state === 'INVALIDATED') return '任务已结束，没有可预览的有效草稿，不能取消。'
  return '当前状态不支持预览或取消。'
}
function stopReason(reason) {
  return { CANCELLED: '任务已取消。', WIKI_COMPILE_TIMEOUT: '编译超时。', WIKI_COMPILE_INTERRUPTED: '编译被中断。', WIKI_TOPIC_SET_INVALID: '所选来源未生成指定主题，请调整主题或重新选择来源。', RECOVERED_UNKNOWN: '任务恢复时无法确认此前调用结果。' }[reason] || '任务未完成，可在技术详情中查看停止原因。'
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
function readableMarkdown(markdown, sources = []) {
  if (typeof markdown !== 'string' || !markdown.startsWith('# Wiki 编译草稿\n')) return markdown || ''
  let inBody = false
  return markdown.split('\n').filter((line) => {
    if (line.startsWith('## ')) inBody = true
    return inBody || !['# Wiki 编译草稿', '状态：DRAFT', '编译版本：', '任务：', '来源清单版本：'].some((prefix) => line.startsWith(prefix))
  })
    .map((line) => {
      const match = line.match(/^(\s*)- snapshot=(\d+) .*? quote=“(.*)”$/)
      if (match) {
        const source = sources?.find((item) => String(item.snapshotId) === match[2])
        const title = source?.title ? `（${source.title.replace(/[\\`*_{}\[\]()#+.!>|-]/g, '\\$&')}）` : ''
        return `${match[1]}- 引用${title}：“${match[3]}”`
      }
      return line.replaceAll('[SEMANTIC_NOT_VERIFIED]', '[待人工核查]')
    })
    .join('\n')
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
  try { pages.value = (await wikiApi.pages()).data }
  finally { pagesLoading.value = false }
}
function refresh() { return perform(() => Promise.all([loadSources(), loadJobs(), loadPages()])) }
function searchSources() { sourcePageNumber.value = 1; return perform(loadSources) }
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
  }, '已授权此精确版本')
}
async function confirm(message) { try { await ElMessageBox.confirm(message, '确认操作', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }); return true } catch { return false } }
async function revoke(source) {
  if (!await confirm('撤销后，依赖此授权的草稿和公开页面将失效。继续撤销？')) return
  return perform(async () => { await wikiApi.revoke({ type: source.type, id: source.id }); selectedSources.value = selectedSources.value.filter((item) => item.type !== source.type || item.id !== source.id); await loadSources() }, '来源授权已撤销')
}
function findAffected(source) { return perform(async () => { affected.value = (await wikiApi.affected({ type: source.type, id: source.id })).data; if (!affected.value.length) ElMessage.info('此来源暂无受影响主题') }) }
function useAffected(target) { topicInput.value = target.topic; newRequestId(); tab.value = 'jobs' }
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
    tab.value = 'pages'
    const page = pages.value.find((item) => item.id === result.pageId)
    if (page) await loadVersions(page)
  }, '已创建新版本，请检查并提交审核')
}
async function loadVersions(page) {
  selectedPage.value = pages.value.find((item) => item.id === page.id) || page
  versions.value = (await wikiApi.versions(page.id)).data
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
  return perform(async () => { await wikiApi.withdraw(page.id, { expectedCurrentVersion: page.currentVersion }); await loadPages() }, '页面已撤回')
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
onMounted(async () => { newRequestId(); await refresh(); if (!disposed) poll = setTimeout(pollJobs, 2000) })
onUnmounted(() => { disposed = true; clearTimeout(poll) })
</script>

<style scoped>
.wiki-management { padding: 12px; color: var(--el-text-color-primary); }
.wiki-tabs { padding: 18px; border: 1px solid var(--el-border-color-light); border-radius: 12px; background: var(--el-bg-color); }
.notice, .compile-card { margin-bottom: 18px; }
.toolbar, .selected-sources, .revision-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 12px 0; }
.toolbar .el-input { max-width: 350px; }
.hint { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.7; }
.el-pagination { margin: 16px 0; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.form-grid .el-select { width: 100%; }
.hash { font: 12px/1.8 monospace; overflow-wrap: anywhere; color: var(--el-text-color-secondary); }
.source-text { max-height: 42vh; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; background: var(--el-fill-color-light); padding: 18px; border-radius: 8px; }
.draft-preview { margin: 20px 0; padding: 18px; border: 1px solid var(--el-border-color); border-radius: 8px; max-height: 55vh; overflow: auto; }
.affected { margin-top: 24px; }
@media (max-width: 700px) { .wiki-management { padding: 0; } .wiki-tabs { padding: 10px; } .form-grid { grid-template-columns: 1fr; gap: 0; } }
</style>
