<template>
  <ManagementCard class="ai-management" title="知识库" v-model:model-current-page="page" :model-page-size="12" :total="documents.total" :show-pagination="!error || documents.items.length > 0" pagination-layout="total, prev, pager, next, jumper" @search="loadDocuments">
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

    <el-dialog v-model="editorVisible" class="ai-management-dialog knowledge-editor-dialog" :title="draftForm.draftId ? '编辑知识草稿' : '新增知识'" width="min(980px, 96vw)" :close-on-click-modal="!busy && !uploadBusy && !saveBusy" @closed="onEditorClosed">
      <el-alert v-if="!draftForm.draftId" title="先保存为草稿；预览并确认正文后发布，才会进入小辰的知识目录与检索索引。" type="info" :closable="false" show-icon />

      <el-tabs v-if="!draftForm.draftId" v-model="createTab" class="knowledge-create-tabs">
        <el-tab-pane label="文档上传" name="document">
          <div class="upload-area">
            <el-upload
              drag
              :show-file-list="false"
              :auto-upload="false"
              accept=".md,.markdown,.txt"
              :disabled="uploadBusy"
              :on-change="onDocumentFileChange"
            >
              <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
              <div class="el-upload__text">将 .md / .txt 文件拖到此处，或<em>点击选择</em></div>
              <template #tip><div class="el-upload__tip">单文件不超过 2 MiB；上传只代表原件已保存，AI 候选仍需管理员确认。</div></template>
            </el-upload>
          </div>

          <div v-if="uploadState.file" class="upload-file">
            <p><strong>{{ uploadState.file.name }}</strong>（{{ formatFileSize(uploadState.file.size) }}）</p>
            <el-input v-model="uploadState.title" maxlength="80" show-word-limit placeholder="文档标题（选填，默认取 Markdown 一级标题或文件名）" />
            <div class="toolbar">
              <el-button type="primary" :loading="uploadBusy" :disabled="!uploadState.file" @click="uploadAndAnalyze">上传并分析</el-button>
              <span v-if="uploadState.requestId" class="hint">导入任务：{{ uploadState.requestId }}</span>
            </div>
          </div>

          <div v-if="uploadState.requestId && !uploadState.file" class="upload-file">
            <p>当前导入任务：<strong>{{ uploadState.filename || uploadState.requestId }}</strong></p>
            <p class="hint">{{ uploadState.requestId }}</p>
            <div class="toolbar">
              <el-button type="primary" :loading="uploadBusy" @click="analyzeDocument">{{ uploadState.analysis ? '重新分析' : '继续分析' }}</el-button>
              <el-button :disabled="uploadBusy" @click="clearImportSelection">重新选择文件</el-button>
            </div>
          </div>

          <div v-if="recentImports.length" class="recent-imports">
            <div class="recent-head">
              <span>最近上传</span>
              <el-button link type="primary" :loading="recentLoading" @click="loadRecentImports">刷新</el-button>
            </div>
            <el-table :data="recentImports" size="small" max-height="180" empty-text="暂无最近上传">
              <el-table-column prop="originalFilename" label="文件" min-width="160" show-overflow-tooltip />
              <el-table-column label="分析状态" min-width="170">
                <template #default="{ row }"><span :class="{ 'review-hint': row.analysisStatus === 'FAILED' }">{{ importStatusLabel(row) }}</span></template>
              </el-table-column>
              <el-table-column label="上传时间" width="170"><template #default="{ row }">{{ formatKnowledgeTime(row.createdAt) }}</template></el-table-column>
              <el-table-column label="操作" width="120"><template #default="{ row }"><el-button link type="primary" :disabled="uploadBusy" @click="resumeImport(row)">{{ recentAction(row) }}</el-button></template></el-table-column>
            </el-table>
          </div>

          <el-alert v-if="uploadState.error" :title="uploadState.error" type="error" :closable="false" show-icon class="notice">
            <p class="hint">文件已保留，可重试分析，或切换到“文字添加”人工整理。</p>
            <div class="toolbar">
              <el-button size="small" :disabled="uploadBusy || !uploadState.requestId" @click="analyzeDocument">重试分析</el-button>
              <el-button size="small" @click="switchToManual">转到文字添加</el-button>
            </div>
          </el-alert>

          <template v-if="uploadState.analysis">
            <div class="analysis-summary">
              <div class="analysis-title">
                <el-tag type="success">AI 分析完成</el-tag>
                <strong>{{ uploadState.analysis.title }}</strong>
                <span class="hint">{{ uploadState.analysis.originalFilename }}</span>
              </div>
              <p class="hint">来源文档 #{{ uploadState.analysis.sourceDocumentId }} · 来源版本 #{{ uploadState.analysis.sourceVersionId }} · 模型 {{ uploadState.analysis.model }} · 审核状态 {{ uploadState.analysis.reviewStatus }}</p>
              <p v-if="uploadState.analysis.warning" class="review-hint">{{ uploadState.analysis.warning }}</p>
            </div>

            <div class="candidate-toolbar">
              <el-checkbox :model-value="allSelected" @change="toggleAll">全选</el-checkbox>
              <span class="hint">已选 {{ selectedCandidates.length }} / {{ candidates.length }} 条；保存后进入原有知识草稿，不会自动审核或发布。</span>
            </div>

            <div v-for="(candidate, index) in candidates" :key="candidate.candidateId" class="candidate-card" :class="{ excluded: !candidate.included }">
              <div class="candidate-head">
                <el-checkbox v-model="candidate.included" />
                <span class="candidate-badge">候选 {{ index + 1 }}</span>
                <el-input v-if="candidate.editing" v-model="candidate.title" maxlength="80" show-word-limit class="candidate-title-input" />
                <strong v-else>{{ candidate.title }}</strong>
                <div class="candidate-actions">
                  <el-button link type="primary" @click="candidate.editing = !candidate.editing">{{ candidate.editing ? '完成编辑' : '编辑' }}</el-button>
                  <el-button link type="danger" @click="candidate.included = false">排除</el-button>
                </div>
              </div>

              <div v-if="candidate.editing" class="candidate-edit">
                <el-form label-position="top">
                  <el-form-item label="知识标题"><el-input v-model="candidate.title" maxlength="80" show-word-limit /></el-form-item>
                  <el-form-item label="知识正文"><el-input v-model="candidate.content" type="textarea" :rows="8" maxlength="20000" show-word-limit /></el-form-item>
                  <div class="form-grid">
                    <el-form-item label="检索关键词（每行一个，最多20个）"><el-input v-model="candidate.keywordsText" type="textarea" :rows="3" /></el-form-item>
                    <el-form-item label="用途页面（选填；不选则按全站通用知识发布）">
                      <el-select v-model="candidate.sourceUrl" clearable placeholder="不关联具体页面（全站通用）">
                        <el-option v-for="item in knowledgeSourcePages" :key="item.value" :label="item.label" :value="item.value" />
                      </el-select>
                    </el-form-item>
                  </div>
                </el-form>
              </div>
              <div v-else class="candidate-preview">
                <p class="hint">来源章节：{{ candidate.sourceSection || '未标注' }} · 置信度：{{ candidate.confidence == null ? '未提供' : candidate.confidence }}</p>
                <p v-if="candidate.sourceExcerpt" class="candidate-excerpt">原文摘录：{{ candidate.sourceExcerpt }}</p>
                <p v-if="candidateKeywords(candidate).length" class="candidate-keywords">关键词：<el-tag v-for="keyword in candidateKeywords(candidate)" :key="keyword" size="small">{{ keyword }}</el-tag></p>
                <p v-if="candidate.reviewHints.length" class="review-hint">审核提示：{{ candidate.reviewHints.join('；') }}</p>
                <el-button link type="primary" @click="candidate.expanded = !candidate.expanded">{{ candidate.expanded ? '收起正文' : '展开正文' }}</el-button>
                <pre v-if="candidate.expanded" class="document-text">{{ candidate.content }}</pre>
              </div>
            </div>
          </template>

          <p v-if="!uploadState.file && !uploadState.analysis" class="hint">选择文件后，系统会先保存到私有 MinIO 并解析正文，再调用现有 AI Provider 提炼候选知识。文档主题较多时会生成多条候选，管理员可逐条编辑和筛选。</p>
        </el-tab-pane>
        <el-tab-pane label="文字添加" name="manual" />
      </el-tabs>

      <el-form v-if="draftForm.draftId || createTab === 'manual'" label-position="top" class="editor">
        <el-alert v-if="!draftForm.draftId" title="保留原有新增知识能力：只需填写标题和正文即可保存草稿；用途页面、关键词和版本可按需补充。" type="info" :closable="false" show-icon class="notice" />
        <div class="form-grid">
          <el-form-item label="知识标题"><el-input v-model="draftForm.title" maxlength="80" show-word-limit /></el-form-item>
          <el-form-item label="用途页面（选填；不选则按全站通用知识发布）">
            <el-select v-model="draftForm.sourceUrl" clearable placeholder="不关联具体页面（全站通用）">
              <el-option v-for="item in knowledgeSourcePages" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="版本标识"><el-input v-model="draftForm.version" maxlength="40" placeholder="例如 manual-1" /></el-form-item>
          <el-form-item label="检索关键词（每行一个，最多20个）"><el-input v-model="draftForm.keywords" type="textarea" :rows="3" /></el-form-item>
        </div>
        <el-form-item label="知识正文"><el-input v-model="draftForm.text" type="textarea" :rows="12" placeholder="写清真实可用的功能、入口、限制和必要条件" /></el-form-item>
        <el-checkbox v-model="draftPreview">预览正文</el-checkbox>
        <pre v-if="draftPreview" class="document-text">{{ draftForm.text }}</pre>
        <p v-if="draftForm.draftId && draftForm.sourceRef?.startsWith('imp:')" class="hint">来源：由上传文档经 AI 提炼后保存的知识草稿。</p>
      </el-form>

      <template #footer>
        <el-button :disabled="busy || uploadBusy || saveBusy" @click="editorVisible = false">取消</el-button>
        <template v-if="draftForm.draftId || createTab === 'manual'">
          <el-button type="primary" :loading="busy" :disabled="!validDraft" @click="saveDraft">保存草稿</el-button>
        </template>
        <template v-else>
          <el-button v-if="uploadState.requestId" :disabled="uploadBusy || saveBusy" @click="analyzeDocument">重新分析</el-button>
          <el-button type="primary" :loading="saveBusy" :disabled="uploadBusy || !selectedCandidates.length" @click="saveSelectedCandidates">保存所选草稿</el-button>
        </template>
      </template>
    </el-dialog>

    <el-dialog v-model="draftsVisible" class="ai-management-dialog" title="知识草稿" width="min(1100px, 96vw)">
      <p class="hint">草稿不参与检索；发布后立即写入知识目录并同步索引。发布前可按当前知识正文重新核对。</p>
      <DataTable :loading="draftsLoading" :data="visibleDrafts" :show-id="false" :show-create-time="false" :show-actions="false" empty-text="暂无知识草稿">
        <el-table-column label="知识名称" min-width="250" show-overflow-tooltip>
          <template #default="{ row }"><span>{{ row.title }}</span><el-tag v-if="isImportDraft(row)" size="small" type="info" class="source-tag">文档提炼</el-tag></template>
        </el-table-column>
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
        <el-alert v-if="!publishTarget.sourceUrl" title="未选择具体页面，将按全站通用知识发布（复用平台首页锚点，仍受原目录白名单校验）。" type="info" :closable="false" show-icon class="notice" />
        <el-alert title="发布后立即进入小辰知识目录并同步检索索引；后续更新会保留历史版本。" type="warning" :closable="false" show-icon />
        <el-checkbox v-model="publishConsent">已核对标题、正文和用途页面/全站通用语义，确认内容真实且可以发布</el-checkbox>
        <el-alert v-if="publishError" :title="publishError" type="error" :closable="false" show-icon class="notice" />
      </template>
      <template #footer><el-button :disabled="busy" @click="publishVisible = false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!publishConsent || !status.capabilities?.ragManage" @click="publishDraft">确认发布</el-button></template>
    </el-dialog>
  </ManagementCard>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, UploadFilled } from '@element-plus/icons-vue'
import ManagementCard from '@/components/management/ManagementCard.vue'
import DataTable from '@/components/data/DataTable.vue'
import TableActions from '@/components/data/TableActions.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import Pagination from '@/components/data/Pagination.vue'
import KeywordSearch from '@/components/search/KeywordSearch.vue'
import SearchButtons from '@/components/search/SearchButtons.vue'
import { knowledgeApi } from '@/api/knowledge'
import {
  analyzeKnowledgeImport, getKnowledgeImportAnalysis, listRecentKnowledgeImports,
  saveKnowledgeImportDrafts, uploadKnowledgeFile,
} from '@/api/knowledgeDoc'
import { formatKnowledgeTime, knowledgeSourcePages, knowledgeSourcePageLabel } from '@/utils/aiPresentation'

const MAX_UPLOAD_BYTES = 2 * 1024 * 1024
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
const createTab = ref('document')
const draftForm = reactive({ draftId: '', title: '', text: '', version: 'manual-1', sourceUrl: '', keywords: '', sourceRef: '' })
const uploadState = reactive({
  file: null, filename: '', title: '', requestId: '', key: '', error: '', analysis: null,
})
const recentImports = ref([])
const recentLoading = ref(false)
const uploadBusy = ref(false)
const saveBusy = ref(false)
const candidates = ref([])
const batchId = ref('')
const publishVisible = ref(false)
const publishTarget = ref(null)
const publishConsent = ref(false)
const publishError = ref('')
const publishRequestId = ref('')
const publishGeneration = ref(0)

const selectedCandidates = computed(() => candidates.value.filter((candidate) => candidate.included))
const allSelected = computed(() => candidates.value.length > 0 && candidates.value.every((candidate) => candidate.included))
const validEdit = computed(() => operation.value !== 'edit' || Boolean(edit.title.trim() && edit.text.trim() && edit.version.trim() && edit.version.trim() !== selected.value?.version))
const validDraft = computed(() => Boolean(draftForm.title.trim() && draftForm.text.trim() && /^[a-zA-Z0-9._-]{1,40}$/.test(draftForm.version.trim())))
const friendlyError = computed(() => /[\u3400-\u9fff]/.test(error.value) ? error.value : '知识暂未加载，请稍后刷新重试。')

function message(failure) { return failure?.response?.data?.msg || failure?.msg || failure?.message || '操作失败，请刷新重试' }
function errorCode(failure) { return failure?.response?.headers?.['x-error-code'] || failure?.code || 'AI_ANALYSIS_FAILED' }
function requestKey(prefix) {
  if (window.crypto?.randomUUID) return `${prefix}-${window.crypto.randomUUID()}`
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}
function formatFileSize(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return '未知大小'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MiB`
}
function fileExtension(name) { return String(name || '').split('.').pop().toLowerCase() }
function parseKeywords(text) {
  return [...new Set(String(text || '').split(/[\n,，、;；]+/).map((value) => value.trim()).filter(Boolean))]
}
function candidateKeywords(candidate) { return parseKeywords(candidate.keywordsText) }
function isImportDraft(draft) { return String(draft?.sourceRef || '').startsWith('imp:') }

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
  Object.assign(draftForm, { draftId: '', title: '', text: '', version: 'manual-1', sourceUrl: '', keywords: '', sourceRef: '' })
  draftPreview.value = false
}
function resetUploadState() {
  uploadState.file = null
  uploadState.filename = ''
  uploadState.title = ''
  uploadState.requestId = ''
  uploadState.key = ''
  uploadState.error = ''
  uploadState.analysis = null
  uploadBusy.value = false
  saveBusy.value = false
  candidates.value = []
  batchId.value = ''
}
function onEditorClosed() {
  resetDraftForm()
  resetUploadState()
  createTab.value = 'document'
}
function openCreate() {
  resetDraftForm()
  resetUploadState()
  createTab.value = 'document'
  editorVisible.value = true
  loadRecentImports()
}
function editDraft(draft) {
  Object.assign(draftForm, {
    draftId: draft.draftId, title: draft.title, text: draft.text, version: draft.version,
    sourceUrl: draft.sourceUrl || '', keywords: (draft.keywords || []).join('\n'), sourceRef: draft.sourceRef || '',
  })
  draftPreview.value = false
  createTab.value = 'manual'
  draftsVisible.value = false
  editorVisible.value = true
}
async function saveDraft() {
  if (busy.value || !validDraft.value) return
  busy.value = true
  error.value = ''
  const data = {
    title: draftForm.title.trim(), text: draftForm.text, version: draftForm.version.trim(),
    sourceUrl: draftForm.sourceUrl || '',
    keywords: parseKeywords(draftForm.keywords).slice(0, 20),
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

function onDocumentFileChange(file) {
  const raw = file?.raw
  if (!raw) return
  const extension = fileExtension(raw.name)
  if (!['md', 'markdown', 'txt'].includes(extension)) {
    resetUploadState()
    uploadState.error = 'UNSUPPORTED_FILE：仅支持 .md、.markdown、.txt 文件'
    return
  }
  if (raw.size <= 0) {
    resetUploadState()
    uploadState.error = 'EMPTY_FILE：文件内容为空'
    return
  }
  if (raw.size > MAX_UPLOAD_BYTES) {
    resetUploadState()
    uploadState.error = 'FILE_TOO_LARGE：文件超过 2 MiB 限制'
    return
  }
  resetUploadState()
  uploadState.file = raw
  uploadState.key = requestKey('imp')
}

async function uploadAndAnalyze() {
  if (uploadBusy.value || (!uploadState.file && !uploadState.requestId)) return
  uploadBusy.value = true
  uploadState.error = ''
  try {
    if (!uploadState.requestId) {
      if (!uploadState.key) uploadState.key = requestKey('imp')
      const uploaded = await uploadKnowledgeFile(uploadState.file, uploadState.title.trim(), uploadState.key)
      uploadState.requestId = uploaded?.data?.requestId || ''
      uploadState.filename = uploaded?.data?.originalFilename || uploadState.file?.name || ''
      if (!uploadState.requestId) throw new Error('上传成功但未返回导入任务标识，请重试')
    }
    await analyzeDocument()
  } catch (failure) {
    uploadState.error = `${errorCode(failure)}：${message(failure)}`
  } finally { uploadBusy.value = false }
}

function normalizeCandidates(list) {
  return (list || []).map((candidate) => ({
    ...candidate,
    included: true,
    editing: false,
    expanded: false,
    title: candidate.title || '',
    content: candidate.content || '',
    keywordsText: (candidate.keywords || []).join('\n'),
    sourceUrl: candidate.sourceUrl || '',
    reviewHints: candidate.reviewHints || [],
  }))
}

async function analyzeDocument() {
  if (!uploadState.requestId) {
    uploadState.error = 'IMPORT_REQUIRED：请先上传文件'
    return
  }
  uploadBusy.value = true
  uploadState.error = ''
  uploadState.analysis = null
  candidates.value = []
  batchId.value = ''
  try {
    const result = await analyzeKnowledgeImport(uploadState.requestId)
    const data = result?.data || {}
    const list = normalizeCandidates(data.candidates)
    if (!list.length) throw new Error('AI 未返回可用的候选知识')
    uploadState.analysis = data
    uploadState.filename = data.originalFilename || uploadState.filename
    candidates.value = list
    batchId.value = requestKey('batch')
    if (data.warning) ElMessage.warning(data.warning)
  } catch (failure) {
    uploadState.error = `${errorCode(failure)}：${message(failure)}`
  } finally {
    uploadBusy.value = false
    loadRecentImports()
  }
}

async function loadRecentImports() {
  if (!status.value.capabilities?.ragManage) return
  recentLoading.value = true
  try {
    const result = await listRecentKnowledgeImports({ limit: 8 })
    recentImports.value = result?.data || []
  } catch {
    recentImports.value = []
  } finally {
    recentLoading.value = false
  }
}

function importStatusLabel(row) {
  if (row.analysisStatus === 'COMPLETED') return `已分析 · ${row.candidateCount} 条候选`
  if (row.analysisStatus === 'FAILED') return `分析失败${row.analysisErrorCode ? '：' + row.analysisErrorCode : ''}`
  return '待分析'
}

function recentAction(row) {
  if (row.analysisStatus === 'COMPLETED') return '恢复候选'
  if (row.analysisStatus === 'FAILED') return '查看错误'
  return '继续分析'
}

async function resumeImport(row) {
  if (uploadBusy.value || !row?.requestId) return
  resetUploadState()
  uploadState.requestId = row.requestId
  uploadState.filename = row.originalFilename || ''
  uploadBusy.value = true
  try {
    const result = await getKnowledgeImportAnalysis(row.requestId)
    const state = result?.data || {}
    if (state.status === 'COMPLETED') {
      uploadState.analysis = state
      candidates.value = normalizeCandidates(state.candidates)
      batchId.value = requestKey('batch')
      if (!candidates.value.length) {
        uploadState.error = '候选快照不可用，请重新分析'
      } else if (state.warning) {
        ElMessage.warning(state.warning)
      }
    } else if (state.status === 'FAILED') {
      uploadState.error = `${state.errorCode || 'AI_ANALYSIS_FAILED'}：${state.errorMessage || 'AI分析失败，请重试'}`
    } else {
      await analyzeDocument()
    }
  } catch (failure) {
    uploadState.error = `${errorCode(failure)}：${message(failure)}`
  } finally {
    uploadBusy.value = false
  }
}

function clearImportSelection() {
  resetUploadState()
}

function toggleAll(value) {
  candidates.value.forEach((candidate) => { candidate.included = Boolean(value) })
}
function switchToManual() {
  createTab.value = 'manual'
  if (uploadState.analysis?.title) draftForm.title = uploadState.analysis.title
}
function validateCandidate(candidate) {
  if (!candidate.title.trim()) return '知识标题不能为空'
  if (candidate.title.trim().length > 80) return '知识标题最多80字'
  if (!candidate.content.trim()) return '知识正文不能为空'
  if (candidate.content.length > 20000) return '知识正文最多20000字'
  const keywords = candidateKeywords(candidate)
  if (keywords.length > 20) return '检索关键词最多20个'
  if (keywords.some((keyword) => keyword.length > 30)) return '每个检索关键词最多30字'
  return ''
}
async function saveSelectedCandidates() {
  if (saveBusy.value) return
  const selectedList = selectedCandidates.value
  if (!selectedList.length) { ElMessage.warning('请至少保留一条候选知识'); return }
  for (const candidate of selectedList) {
    const invalid = validateCandidate(candidate)
    if (invalid) {
      candidate.editing = true
      candidate.expanded = true
      ElMessage.warning(`候选“${candidate.title || '未命名'}”：${invalid}`)
      return
    }
  }
  if (!batchId.value) batchId.value = requestKey('batch')
  saveBusy.value = true
  uploadState.error = ''
  try {
    const payload = {
      batchId: batchId.value,
      candidates: selectedList.map((candidate) => ({
        candidateId: candidate.candidateId,
        title: candidate.title.trim(),
        content: candidate.content,
        keywords: candidateKeywords(candidate).slice(0, 20),
        sourceUrl: candidate.sourceUrl || null,
      })),
    }
    const result = await saveKnowledgeImportDrafts(uploadState.requestId, payload)
    const data = result?.data || {}
    const saved = data.saved || []
    const failed = data.failed || []
    if (!saved.length) {
      uploadState.error = `DRAFT_SAVE_FAILED：${failed[0]?.message || '没有草稿被保存'}`
      return
    }
    const savedIds = new Set(saved.map((item) => item.candidateId))
    candidates.value = candidates.value.filter((candidate) => !savedIds.has(candidate.candidateId))
    await loadSnapshot()
    if (failed.length) {
      ElMessage.warning(`已保存 ${saved.length} 篇草稿；${failed.length} 条候选保存失败，请核对后重试`)
    } else {
      draftsVisible.value = true
      draftPage.value = 1
      editorVisible.value = false
      ElMessage.success(`已保存 ${saved.length} 篇知识草稿，需审核发布后才会进入检索`)
    }
  } catch (failure) {
    uploadState.error = `DRAFT_SAVE_FAILED：${message(failure)}`
  } finally { saveBusy.value = false }
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

<style lang="scss" scoped>
.knowledge-create-tabs {
  margin-top: 8px;
}
.upload-area {
  margin: 16px 0;
}
.upload-area :deep(.el-upload) {
  width: 100%;
}
.upload-area :deep(.el-upload-dragger) {
  width: 100%;
  padding: 28px 16px;
}
.upload-file {
  margin: 16px 0;
}
.recent-imports {
  margin: 16px 0;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.recent-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-weight: 600;
}
.upload-file p {
  margin: 0 0 10px;
  overflow-wrap: anywhere;
}
.upload-file .el-input {
  max-width: 560px;
}
.analysis-summary {
  margin: 18px 0 12px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.analysis-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.candidate-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  margin: 12px 0;
}
.candidate-card {
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
}
.candidate-card.excluded {
  opacity: .55;
  background: var(--el-fill-color-lighter);
}
.candidate-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.candidate-badge {
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.candidate-title-input {
  flex: 1 1 320px;
  min-width: 220px;
}
.candidate-actions {
  margin-left: auto;
}
.candidate-edit {
  margin-top: 14px;
}
.candidate-preview {
  margin-top: 8px;
}
.candidate-excerpt,
.candidate-keywords {
  margin: 6px 0;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.candidate-keywords .el-tag {
  margin-right: 6px;
}
.review-hint {
  color: var(--el-color-warning);
  font-size: 13px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.source-tag {
  margin-left: 8px;
  vertical-align: middle;
}
@media (max-width: 700px) {
  .candidate-actions { margin-left: 0; width: 100%; }
  .upload-file .el-input { max-width: 100%; }
}
</style>
