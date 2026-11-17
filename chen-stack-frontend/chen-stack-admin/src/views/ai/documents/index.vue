<template>
  <div class="knowledge-doc-page">
    <el-card shadow="never" class="status-card">
      <div class="status-row">
        <el-tag :type="status.enabled ? 'success' : 'info'">{{ status.enabled ? '文档通道已启用' : '文档通道未启用' }}</el-tag>
        <el-tag type="info">集合 {{ status.collection || '-' }}</el-tag>
        <el-tag :type="status.indexerAvailable ? 'success' : 'warning'">
          索引器 {{ status.indexerAvailable ? '可用' : '不可用' }}
        </el-tag>
        <span class="hint">目录代次 {{ status.revision }} · 已发布 {{ status.publishedDocuments }} 篇 / {{ status.publishedChunks }} 块</span>
        <el-button class="refresh" size="small" @click="loadAll">刷新</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="upload-card">
      <template #header><span>上传 Markdown / TXT</span></template>
      <el-form :inline="true">
        <el-form-item label="文档标题">
          <el-input v-model="uploadTitle" maxlength="80" placeholder="留空则取首个标题或文件名" style="width: 280px" />
        </el-form-item>
        <el-form-item>
          <el-upload
            :show-file-list="false"
            :auto-upload="false"
            accept=".md,.markdown,.txt"
            :on-change="onFileChange"
          >
            <el-button type="primary" :loading="uploading">选择文件并上传</el-button>
          </el-upload>
        </el-form-item>
      </el-form>
      <p class="hint">上传成功不等于审核通过；审核通过不等于索引完成。三个状态分别展示。</p>
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="导入任务" name="imports">
          <el-table :data="imports" size="small" empty-text="暂无导入任务">
            <el-table-column prop="originalFilename" label="文件名" min-width="180" show-overflow-tooltip />
            <el-table-column prop="contentType" label="类型" width="120" />
            <el-table-column prop="parsedChars" label="正文字数" width="100" />
            <el-table-column label="上传状态" width="110">
              <template #default="{ row }"><el-tag size="small">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="330">
              <template #default="{ row }">
                <el-button link type="primary" @click="openPreview(row)">预览</el-button>
                <el-button link type="primary" @click="downloadOriginal(row)">下载原件</el-button>
                <el-button link type="success" :disabled="row.status !== 'PARSED'" @click="approve(row)">审核通过</el-button>
                <el-button link type="danger" :disabled="row.status !== 'PARSED'" @click="reject(row)">驳回</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="知识文档" name="documents">
          <el-table :data="documents" size="small" empty-text="暂无知识文档">
            <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
            <el-table-column prop="sourceKey" label="业务键" width="150" />
            <el-table-column prop="documentKind" label="来源" width="150" />
            <el-table-column label="审核" width="110">
              <template #default="{ row }"><el-tag size="small" :type="row.reviewStatus === 'APPROVED' ? 'success' : 'info'">{{ row.reviewStatus }}</el-tag></template>
            </el-table-column>
            <el-table-column label="发布" width="120">
              <template #default="{ row }"><el-tag size="small" :type="row.publishStatus === 'PUBLISHED' ? 'success' : 'warning'">{{ row.publishStatus }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="420">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDetail(row)">详情</el-button>
                <el-button link type="success" :disabled="row.reviewStatus !== 'APPROVED'" @click="publish(row)">发布/索引</el-button>
                <el-button link type="warning" :disabled="row.publishStatus !== 'PUBLISHED'" @click="reindex(row)">重建索引</el-button>
                <el-button link type="info" :disabled="row.publishStatus !== 'PUBLISHED'" @click="withdraw(row)">下架</el-button>
                <el-button link type="danger" @click="revoke(row)">撤销</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="previewVisible" title="解析预览" width="min(900px, 95vw)">
      <p><strong>{{ preview.title }}</strong>（{{ preview.rawFormat }}，{{ preview.normalizedChars }} 字）</p>
      <el-divider content-position="left">原始 Markdown</el-divider>
      <pre class="doc-text">{{ preview.rawPreview }}</pre>
      <el-divider content-position="left">规范化正文</el-divider>
      <pre class="doc-text">{{ preview.normalizedPreview }}</pre>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="文档详情" width="min(1000px, 96vw)">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="标题">{{ detail.title }}</el-descriptions-item>
        <el-descriptions-item label="业务键">{{ detail.sourceKey }}</el-descriptions-item>
        <el-descriptions-item label="审核状态">{{ detail.reviewStatus }}</el-descriptions-item>
        <el-descriptions-item label="发布状态">{{ detail.publishStatus }}</el-descriptions-item>
        <el-descriptions-item label="当前版本">{{ detail.currentVersionNo }}</el-descriptions-item>
        <el-descriptions-item label="来源类型">{{ detail.documentKind }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">历史版本</el-divider>
      <el-table :data="versions" size="small">
        <el-table-column prop="versionLabel" label="版本" width="140" />
        <el-table-column prop="state" label="状态" width="120" />
        <el-table-column label="正文" width="100">
          <template #default="{ row }">{{ row.bodyAvailable ? '有' : '仅哈希' }}</template>
        </el-table-column>
        <el-table-column prop="bodyProvenance" label="来源" width="150" />
        <el-table-column prop="charLength" label="字数" width="90" />
      </el-table>

      <el-divider content-position="left">RAG 索引任务</el-divider>
      <el-table :data="indexTasks" size="small">
        <el-table-column prop="requestId" label="任务号" min-width="180" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="chunkCount" label="分块" width="80" />
        <el-table-column prop="expectedEmbeddingCalls" label="预计调用" width="100" />
        <el-table-column prop="embeddingRequests" label="已用" width="80" />
        <el-table-column prop="errorCode" label="错误码" min-width="160" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getKnowledgeStatus,
  listKnowledgeDocuments,
  listKnowledgeImports,
  previewKnowledgeImport,
  approveKnowledgeImport,
  rejectKnowledgeImport,
  uploadKnowledgeFile,
  downloadKnowledgeImport,
  getKnowledgeDocument,
  listKnowledgeVersions,
  listKnowledgeIndexTasks,
  publishKnowledgeDocument,
  reindexKnowledgeDocument,
  withdrawKnowledgeDocument,
  revokeKnowledgeDocument
} from '@/api/knowledgeDoc'

const status = ref({ enabled: false, collection: '', revision: 0, publishedDocuments: 0, publishedChunks: 0, indexerAvailable: false })
const imports = ref([])
const documents = ref([])
const activeTab = ref('imports')
const uploadTitle = ref('')
const uploading = ref(false)
const previewVisible = ref(false)
const preview = ref({})
const detailVisible = ref(false)
const detail = ref({})
const versions = ref([])
const indexTasks = ref([])

const loadAll = async () => {
  try {
    const [s, i, d] = await Promise.all([
      getKnowledgeStatus(),
      listKnowledgeImports({ limit: 100 }),
      listKnowledgeDocuments({ limit: 200 })
    ])
    status.value = s?.data || status.value
    imports.value = i?.data || []
    documents.value = d?.data || []
  } catch (error) {
    ElMessage.error(error?.message || '加载知识文档失败')
  }
}

const onFileChange = async (file) => {
  if (!file?.raw) return
  uploading.value = true
  try {
    const key = `imp-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    await uploadKnowledgeFile(file.raw, uploadTitle.value, key)
    ElMessage.success('上传并解析成功，等待审核')
    uploadTitle.value = ''
    await loadAll()
  } catch (error) {
    ElMessage.error(error?.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

const openPreview = async (row) => {
  try {
    const result = await previewKnowledgeImport(row.requestId)
    preview.value = result?.data || {}
    previewVisible.value = true
  } catch (error) {
    ElMessage.error(error?.message || '预览失败')
  }
}

const downloadOriginal = async (row) => {
  try {
    await downloadKnowledgeImport(row.requestId, row.originalFilename)
  } catch (error) {
    ElMessage.error(error?.message || '下载失败')
  }
}

const approve = async (row) => {
  try {
    await approveKnowledgeImport(row.requestId)
    ElMessage.success('审核通过，已生成文档与首版正文')
    await loadAll()
  } catch (error) {
    ElMessage.error(error?.message || '审核失败')
  }
}

const reject = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入驳回原因代码', '驳回导入', { inputValue: 'CONTENT_INVALID' })
    await rejectKnowledgeImport(row.requestId, value)
    ElMessage.success('已驳回')
    await loadAll()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(error?.message || '驳回失败')
  }
}

const publish = async (row) => {
  try {
    const result = await publishKnowledgeDocument(row.id)
    const data = result?.data || {}
    if (data.indexed) ElMessage.success('已发布并完成索引')
    else ElMessage.warning(`已发布，但索引未完成：${data.indexError || data.indexStatus || '未知原因'}`)
    await loadAll()
  } catch (error) {
    ElMessage.error(error?.message || '发布失败')
  }
}

const reindex = async (row) => {
  try {
    await reindexKnowledgeDocument(row.id)
    ElMessage.success('索引任务已完成')
    await loadAll()
  } catch (error) {
    ElMessage.error(error?.message || '重建索引失败')
  }
}

const withdraw = async (row) => {
  try {
    await withdrawKnowledgeDocument(row.id)
    ElMessage.success('已下架')
    await loadAll()
  } catch (error) {
    ElMessage.error(error?.message || '下架失败')
  }
}

const revoke = async (row) => {
  try {
    await ElMessageBox.confirm('撤销后 RAG 与 Wiki 都将停止使用该知识，历史版本与审计保留。', '确认撤销', { type: 'warning' })
    await revokeKnowledgeDocument(row.id)
    ElMessage.success('已撤销')
    await loadAll()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(error?.message || '撤销失败')
  }
}

const openDetail = async (row) => {
  try {
    const [doc, vers, tasks] = await Promise.all([
      getKnowledgeDocument(row.id),
      listKnowledgeVersions(row.id),
      listKnowledgeIndexTasks(row.id)
    ])
    detail.value = doc?.data || {}
    versions.value = vers?.data || []
    indexTasks.value = tasks?.data || []
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(error?.message || '加载详情失败')
  }
}

onMounted(loadAll)
</script>

<style scoped>
.knowledge-doc-page { display: flex; flex-direction: column; gap: 16px; }
.status-card :deep(.el-card__body) { padding: 12px 16px; }
.status-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.refresh { margin-left: auto; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.doc-text {
  max-height: 260px; overflow: auto; padding: 12px; border-radius: 8px;
  background: var(--el-fill-color-light); white-space: pre-wrap; word-break: break-word;
}
</style>
