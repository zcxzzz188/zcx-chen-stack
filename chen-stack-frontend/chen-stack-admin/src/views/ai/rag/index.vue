<template>
  <ManagementCard class="ai-management" title="原始知识" v-model:model-current-page="page" :model-page-size="12" :total="documents.total" :show-pagination="!error || documents.items.length > 0" pagination-layout="total, prev, pager, next, jumper" @search="loadDocuments">
    <template #filters>
      <KeywordSearch v-model="query" label="知识名称" placeholder="搜索知识名称或关键词" width="260px" @search="search" />
      <SearchButtons @search="search" @reset="reset" />
      <el-button v-if="status.capabilities?.ragManage" type="primary" plain @click="openCreate">新增知识</el-button>
      <el-button v-if="status.capabilities?.ragManage" @click="openDrafts">知识草稿<span v-if="drafts.length">（{{ drafts.length }}）</span></el-button>
      <el-button :icon="Refresh" :loading="busy" @click="refresh">刷新</el-button>
    </template>
    <template #table-view>
    <el-alert v-if="error" :title="friendlyError" type="warning" :closable="false" show-icon class="notice" />
    <el-card v-if="status.capabilities?.ragManage && pending.length" shadow="never" class="notice">
      <template #header>待完成的知识操作</template>
      <p class="hint">以下操作尚未完成，可继续处理。</p>
      <DataTable :data="pending" :show-id="false" :show-create-time="false" :show-actions="false">
        <el-table-column prop="title" label="知识名称" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" min-width="140"><template #default="{ row }">{{ row.action === 'REVOKE' ? '撤销使用' : row.action === 'CREATE' ? '新增知识' : '更新知识' }}</template></el-table-column>
        <el-table-column label="恢复" min-width="180" align="center"><template #default="{ row }"><TableActions show-edit edit-text="恢复原操作" :disabled="busy" @edit="resume(row)" /></template></el-table-column>
      </DataTable>
    </el-card>

      <DataTable :loading="busy" :data="documents.items" :show-id="false" :show-create-time="false" :show-actions="false" :empty-text="error ? '知识列表暂未加载，请刷新重试' : '暂无符合条件的知识文档'">
        <el-table-column prop="title" label="知识名称" min-width="240" show-overflow-tooltip />
        <el-table-column label="有效性" width="150"><template #default="{ row }"><StatusBadge :text="row.effective ? '已审校可用' : '已停用或待审校'" :custom-class="row.effective ? 'status-audited' : 'status-default'" /></template></el-table-column>
        <el-table-column label="更新时间" min-width="180"><template #default="{ row }">{{ formatKnowledgeTime(row.updatedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="360" header-align="center"><template #default="{ row }"><TableActions show-view view-text="查看详情与来源" :show-edit="status.capabilities?.ragManage" edit-text="更新知识" :show-delete="status.capabilities?.ragManage" delete-text="撤销使用" :disabled="busy" :disabled-actions="row.effective ? [] : ['edit', 'delete']" @view="openDocument(row.sourceId)" @edit="openDocument(row.sourceId, 'edit')" @delete="openDocument(row.sourceId, 'revoke')" /></template></el-table-column>
      </DataTable>
    </template>
    <el-dialog v-model="detailVisible" class="ai-management-dialog" :title="operation === 'edit' ? '更新已审校知识' : operation === 'revoke' ? '撤销知识使用' : '知识详情与来源'" width="min(920px, 95vw)" :close-on-click-modal="!busy">
      <template v-if="selected">
        <template v-if="operation === 'edit'">
          <el-alert title="请先核验内容与真实业务一致，填写新版本后确认更新；已有知识和调用历史会保留。" type="warning" :closable="false" show-icon />
          <el-form label-position="top" class="editor"><el-form-item label="知识名称"><el-input v-model="edit.title" maxlength="160" /></el-form-item><el-form-item label="新版本标识（不能与当前版本相同）"><el-input v-model="edit.version" maxlength="80" /></el-form-item><el-form-item label="知识正文"><el-input v-model="edit.text" type="textarea" :rows="12" /></el-form-item><el-form-item label="检索关键词（每行一个）"><el-input v-model="edit.keywords" type="textarea" :rows="3" /></el-form-item></el-form>
          <el-checkbox v-model="consent">已核验内容准确、来源合法，同意作为客服知识使用</el-checkbox>
        </template>
        <template v-else>
          <h3>{{ selected.title }}</h3>
          <p>更新时间：{{ formatKnowledgeTime(selected.updatedAt) }}</p>
          <p>来源：平台使用说明 · 关联页面：{{ knowledgeSourcePageLabel(selected.sourceUrl) }}</p>
          <p v-if="selected.keywords?.length">检索关键词：{{ selected.keywords.join('、') }}</p>
          <pre class="document-text">{{ selected.text }}</pre>
          <h3>历史版本</h3>
          <p v-if="versionLoading" class="hint">正在读取历史版本…</p>
          <DataTable v-else-if="versions.length" :data="versions" :show-id="false" :show-create-time="false" :show-actions="false">
            <el-table-column prop="version" label="版本" width="130" show-overflow-tooltip />
            <el-table-column label="状态" width="120"><template #default="{ row }"><StatusBadge :text="row.state === 'REVOKED' ? '已撤销' : '历史版本'" :custom-class="row.state === 'REVOKED' ? 'status-rejected' : 'status-default'" /></template></el-table-column>
            <el-table-column prop="archivedAt" label="归档时间" min-width="170" />
            <el-table-column label="操作" width="100" align="center"><template #default="{ row }"><TableActions show-view view-text="查看正文" @view="selectedVersion = row" /></template></el-table-column>
          </DataTable>
          <p v-else class="hint">暂无被替换的历史版本；当前正文以知识目录为准。</p>
          <template v-if="selectedVersion">
            <p class="section-title">历史正文（版本 {{ selectedVersion.version }}）</p>
            <pre class="document-text">{{ selectedVersion.text }}</pre>
          </template>
        </template>
        <template v-if="operation === 'revoke'"><el-alert title="撤销后小辰将停止使用此知识。历史记录保留，不会删除原始业务内容。" type="warning" :closable="false" show-icon /><el-checkbox v-model="consent">确认撤销此版本知识的使用资格</el-checkbox></template>
        <el-alert v-if="operationError" :title="operationError" type="error" :closable="false" show-icon class="notice" />
      </template>
      <template #footer><el-button :disabled="busy" @click="detailVisible = false">关闭</el-button><el-button v-if="operation" type="primary" :loading="busy" :disabled="!consent || !validEdit || !status.capabilities?.ragManage" @click="save">{{ operation === 'edit' ? '确认更新' : '确认撤销' }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="editorVisible" class="ai-management-dialog" :title="draftForm.draftId ? '编辑知识草稿' : '新增知识'" width="min(920px, 95vw)" :close-on-click-modal="!busy">
      <el-alert title="先保存为草稿；预览并确认正文后发布，才会进入小辰的知识目录与检索索引。" type="info" :closable="false" show-icon />
      <el-form label-position="top" class="editor">
        <div class="form-grid">
          <el-form-item label="知识标题"><el-input v-model="draftForm.title" maxlength="80" show-word-limit /></el-form-item>
          <el-form-item label="用途页面"><el-select v-model="draftForm.sourceUrl" placeholder="选择关联的用户端页面"><el-option v-for="item in knowledgeSourcePages" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          <el-form-item label="版本标识"><el-input v-model="draftForm.version" maxlength="40" placeholder="例如 manual-1" /></el-form-item>
          <el-form-item label="检索关键词（每行一个，最多20个）"><el-input v-model="draftForm.keywords" type="textarea" :rows="3" /></el-form-item>
        </div>
        <el-form-item label="知识正文"><el-input v-model="draftForm.text" type="textarea" :rows="12" placeholder="写清真实可用的功能、入口、限制和必要条件" /></el-form-item>
        <el-checkbox v-model="draftPreview">预览正文</el-checkbox>
        <pre v-if="draftPreview" class="document-text">{{ draftForm.text }}</pre>
      </el-form>
      <template #footer><el-button :disabled="busy" @click="editorVisible = false">关闭</el-button><el-button type="primary" :loading="busy" :disabled="!validDraft" @click="saveDraft">保存草稿</el-button></template>
    </el-dialog>

    <el-dialog v-model="draftsVisible" class="ai-management-dialog" title="知识草稿" width="min(1100px, 96vw)">
      <p class="hint">草稿不参与检索；发布后立即写入知识目录并同步索引。发布前可按当前知识正文重新核对。</p>
      <DataTable :loading="draftsLoading" :data="visibleDrafts" :show-id="false" :show-create-time="false" :show-actions="false" empty-text="暂无知识草稿">
        <el-table-column prop="title" label="知识名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="sourceUrlLabel" label="用途页面" width="130" />
        <el-table-column prop="version" label="版本" width="130" show-overflow-tooltip />
        <el-table-column prop="updatedAt" label="更新时间" min-width="170" />
        <el-table-column label="操作" width="330" align="center"><template #default="{ row }"><TableActions show-view view-text="预览并发布" show-edit edit-text="编辑" show-delete delete-text="删除" :disabled="busy" @view="openPublish(row)" @edit="editDraft(row)" @delete="discardDraft(row)" /></template></el-table-column>
      </DataTable>
      <Pagination v-if="drafts.length > 12" v-model:current-page="draftPage" :total="drafts.length" :page-size="12" layout="total, prev, pager, next, jumper" />
      <p v-if="!drafts.length && !draftsLoading" class="hint">点击“新增知识”创建第一篇草稿。</p>
    </el-dialog>

    <el-dialog v-model="publishVisible" class="ai-management-dialog" title="发布前核对" width="min(920px, 95vw)" :close-on-click-modal="!busy">
      <template v-if="publishTarget">
        <h3>{{ publishTarget.title }}</h3>
        <p>用途页面：{{ publishTarget.sourceUrlLabel }} · 版本：{{ publishTarget.version }}</p>
        <p v-if="publishTarget.keywords?.length">检索关键词：{{ publishTarget.keywords.join('、') }}</p>
        <pre class="document-text">{{ publishTarget.text }}</pre>
        <el-alert title="发布后立即进入小辰知识目录并同步检索索引；后续更新会保留历史版本。" type="warning" :closable="false" show-icon />
        <el-checkbox v-model="publishConsent">已核对标题、正文和用途页面，确认内容真实且可以发布</el-checkbox>
        <el-alert v-if="publishError" :title="publishError" type="error" :closable="false" show-icon class="notice" />
      </template>
      <template #footer><el-button :disabled="busy" @click="publishVisible = false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!publishConsent || !status.capabilities?.ragManage" @click="publishDraft">确认发布</el-button></template>
    </el-dialog>
  </ManagementCard>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import ManagementCard from '@/components/management/ManagementCard.vue'
import DataTable from '@/components/data/DataTable.vue'
import TableActions from '@/components/data/TableActions.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import Pagination from '@/components/data/Pagination.vue'
import KeywordSearch from '@/components/search/KeywordSearch.vue'
import SearchButtons from '@/components/search/SearchButtons.vue'
import { knowledgeApi } from '@/api/knowledge'
import { formatKnowledgeTime, knowledgeSourcePages, knowledgeSourcePageLabel } from '@/utils/aiPresentation'

const busy = ref(false)
const error = ref('')
const status = ref({})
const query = ref('')
const page = ref(1)
const documents = ref({ items: [], total: 0 })
const pending = ref([])
const selected = ref(null)
const detailVisible = ref(false)
const operation = ref('')
const operationError = ref('')
const consent = ref(false)
const requestId = ref('')
const edit = reactive({ title: '', text: '', version: '', keywords: '' })
const versions = ref([])
const versionLoading = ref(false)
const selectedVersion = ref(null)
const drafts = ref([])
const draftsLoading = ref(false)
const draftsVisible = ref(false)
const draftPage = ref(1)
const visibleDrafts = computed(() => drafts.value.slice((draftPage.value - 1) * 12, draftPage.value * 12))
const editorVisible = ref(false)
const draftPreview = ref(false)
const draftForm = reactive({ draftId: '', title: '', text: '', version: 'manual-1', sourceUrl: '', keywords: '' })
const publishVisible = ref(false)
const publishTarget = ref(null)
const publishConsent = ref(false)
const publishError = ref('')
const publishRequestId = ref('')
const publishGeneration = ref(0)
const validEdit = computed(() => operation.value !== 'edit' || Boolean(edit.title.trim() && edit.text.trim() && edit.version.trim() && edit.version.trim() !== selected.value?.version))
const validDraft = computed(() => Boolean(draftForm.title.trim() && draftForm.text.trim() && /^[a-zA-Z0-9._-]{1,40}$/.test(draftForm.version.trim()) && draftForm.sourceUrl))
const friendlyError = computed(() => /[\u3400-\u9fff]/.test(error.value) ? error.value : '知识暂未加载，请稍后刷新重试。')
function message(failure) { return failure?.response?.data?.msg || failure?.msg || failure?.message || '操作失败，请刷新重试' }
async function perform(action) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try { await action() } catch (failure) { error.value = message(failure) } finally { busy.value = false }
}
function loadDocuments() { return perform(async () => { documents.value = (await knowledgeApi.documents({ q: query.value.trim(), page: page.value, size: 12 })).data }) }
async function loadDrafts() {
  if (!status.value.capabilities?.ragManage || !knowledgeApi.drafts) return
  draftsLoading.value = true
  try { drafts.value = (await knowledgeApi.drafts({})).data || [] }
  catch (failure) { drafts.value = [] }
  finally { draftsLoading.value = false }
}
async function loadSnapshot() {
  status.value = (await knowledgeApi.status()).data
  pending.value = status.value.capabilities?.ragManage ? (await knowledgeApi.pending()).data : []
  documents.value = (await knowledgeApi.documents({ q: query.value.trim(), page: page.value, size: 12 })).data
  await loadDrafts()
}
function refresh() { return perform(loadSnapshot) }
async function resume(item) {
  if (busy.value || !status.value.capabilities?.ragManage) return
  try { await ElMessageBox.confirm(`继续恢复“${item.title}”此前已确认的${item.action === 'REVOKE' ? '撤权' : item.action === 'CREATE' ? '新增' : '更新'}操作？不会创建新的请求。`, '继续知识操作', { type: 'warning', confirmButtonText: '恢复原操作', cancelButtonText: '取消' }) } catch { return }
  return perform(async () => {
    const result = (await knowledgeApi.resume(item.operationId)).data
    if (result.status !== 'COMPLETED') throw new Error('操作尚未完成，请稍后继续处理。')
    await loadSnapshot()
    ElMessage.success('原操作已恢复完成')
  })
}
function search() { page.value = 1; return loadDocuments() }
function reset() { query.value = ''; return search() }
function openDocument(sourceId, action = '') {
  return perform(async () => {
    selected.value = (await knowledgeApi.document(sourceId)).data
    operation.value = action
    operationError.value = ''
    consent.value = false
    selectedVersion.value = null
    versions.value = []
    requestId.value = `knowledge-${crypto.randomUUID()}`
    Object.assign(edit, { title: selected.value.title, text: selected.value.text, version: selected.value.version, keywords: selected.value.keywords?.join('\n') || '' })
    detailVisible.value = true
    if (!action && knowledgeApi.versions) {
      versionLoading.value = true
      try { versions.value = (await knowledgeApi.versions(sourceId)).data || [] }
      catch (failure) { versions.value = [] }
      finally { versionLoading.value = false }
    }
  })
}
async function save() {
  if (busy.value || !consent.value || !validEdit.value || !status.value.capabilities?.ragManage) return
  busy.value = true
  operationError.value = ''
  const data = { requestId: requestId.value, expectedGeneration: selected.value.generation, expectedContentHash: selected.value.contentHash }
  try {
    if (operation.value === 'edit') Object.assign(data, { confirmReviewed: consent.value, title: edit.title.trim(), text: edit.text, version: edit.version.trim(), keywords: edit.keywords.split('\n').map((value) => value.trim()).filter(Boolean) })
    else data.confirmRevocation = consent.value
    const result = (await knowledgeApi[operation.value === 'edit' ? 'update' : 'revoke'](selected.value.sourceId, data)).data
    if (!['COMPLETED', 'SUCCEEDED', 'SUCCESS'].includes(result.status)) {
      operationError.value = '操作尚未完成，请保留当前页面并稍后重试。'
      return
    }
    detailVisible.value = false
    ElMessage.success(operation.value === 'edit' ? '知识已更新' : '知识已撤销使用')
    await loadSnapshot()
  } catch (failure) {
    operationError.value = /CONFLICT|STALE|GENERATION/i.test(message(failure)) ? '知识已被其他操作更新，请关闭后刷新并重新核查。' : '操作未完成，请保留当前页面并稍后重试。'
    error.value = message(failure)
  } finally { busy.value = false }
}
function resetDraftForm() {
  Object.assign(draftForm, { draftId: '', title: '', text: '', version: 'manual-1', sourceUrl: '', keywords: '' })
  draftPreview.value = false
}
function openCreate() {
  resetDraftForm()
  editorVisible.value = true
}
function editDraft(draft) {
  Object.assign(draftForm, { draftId: draft.draftId, title: draft.title, text: draft.text, version: draft.version, sourceUrl: draft.sourceUrl, keywords: (draft.keywords || []).join('\n') })
  draftPreview.value = false
  draftsVisible.value = false
  editorVisible.value = true
}
async function saveDraft() {
  if (busy.value || !validDraft.value) return
  busy.value = true
  error.value = ''
  const data = {
    title: draftForm.title.trim(), text: draftForm.text, version: draftForm.version.trim(),
    sourceUrl: draftForm.sourceUrl,
    keywords: draftForm.keywords.split('\n').map((value) => value.trim()).filter(Boolean).slice(0, 20),
  }
  try {
    if (draftForm.draftId) await knowledgeApi.updateDraft(draftForm.draftId, data)
    else await knowledgeApi.createDraft(data)
    editorVisible.value = false
    ElMessage.success('知识草稿已保存')
    await loadSnapshot()
    draftsVisible.value = true
    draftPage.value = 1
  } catch (failure) { error.value = message(failure); ElMessage.error(friendlyError.value) }
  finally { busy.value = false }
}
function openDrafts() {
  draftPage.value = 1
  draftsVisible.value = true
  return perform(loadDrafts)
}
function openPublish(draft) {
  publishTarget.value = draft
  publishConsent.value = false
  publishError.value = ''
  publishRequestId.value = `knowledge-${crypto.randomUUID()}`
  publishGeneration.value = status.value.generation ?? 0
  draftsVisible.value = false
  publishVisible.value = true
}
async function publishDraft() {
  if (busy.value || !publishConsent.value || !publishTarget.value) return
  busy.value = true
  publishError.value = ''
  try {
    const result = (await knowledgeApi.publishDraft(publishTarget.value.draftId, {
      requestId: publishRequestId.value, expectedGeneration: publishGeneration.value, confirmReviewed: publishConsent.value,
    })).data
    if (!['COMPLETED', 'SUCCEEDED', 'SUCCESS'].includes(result.status)) {
      publishError.value = '发布尚未完成，请保留当前窗口并稍后重试。'
      return
    }
    publishVisible.value = false
    ElMessage.success('知识已发布并同步索引')
    await loadSnapshot()
  } catch (failure) {
    publishError.value = /CONFLICT|STALE|GENERATION/i.test(message(failure)) ? '知识目录已变化，请关闭后刷新再发布。' : '发布未完成，请保留当前窗口并稍后重试。'
    error.value = message(failure)
  } finally { busy.value = false }
}
async function discardDraft(draft) {
  if (busy.value) return
  try { await ElMessageBox.confirm(`删除草稿“${draft.title}”？不会影响已发布知识。`, '删除草稿', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }) } catch { return }
  return perform(async () => {
    await knowledgeApi.discardDraft(draft.draftId)
    await loadDrafts()
    ElMessage.success('草稿已删除')
  })
}
onMounted(refresh)
</script>

<style lang="scss" src="@/assets/scss/ai-management.scss"></style>
