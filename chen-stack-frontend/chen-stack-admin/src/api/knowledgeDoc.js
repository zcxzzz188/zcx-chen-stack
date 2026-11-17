import request from '@/utils/Request'

// 统一知识文档管理接口（后端 /admin/knowledge）
export const getKnowledgeStatus = () => request({ url: '/admin/knowledge/status', method: 'get' })

export const listKnowledgeDocuments = (params) =>
  request({ url: '/admin/knowledge/documents', method: 'get', params })

export const getKnowledgeDocument = (id) =>
  request({ url: `/admin/knowledge/documents/${id}`, method: 'get' })

export const listKnowledgeVersions = (id) =>
  request({ url: `/admin/knowledge/documents/${id}/versions`, method: 'get' })

export const listKnowledgeIndexTasks = (id) =>
  request({ url: `/admin/knowledge/documents/${id}/index-tasks`, method: 'get' })

export const listKnowledgeAudit = (id) =>
  request({ url: `/admin/knowledge/documents/${id}/audit`, method: 'get' })

export const publishKnowledgeDocument = (id) =>
  request({ url: `/admin/knowledge/documents/${id}/publish`, method: 'post' })

export const reindexKnowledgeDocument = (id) =>
  request({ url: `/admin/knowledge/documents/${id}/reindex`, method: 'post' })

export const withdrawKnowledgeDocument = (id) =>
  request({ url: `/admin/knowledge/documents/${id}/withdraw`, method: 'post' })

export const revokeKnowledgeDocument = (id) =>
  request({ url: `/admin/knowledge/documents/${id}`, method: 'delete' })

export const listKnowledgeImports = (params) =>
  request({ url: '/admin/knowledge/imports', method: 'get', params })

// 最近上传且仍可继续分析的导入任务；用于刷新/重进弹窗后的恢复
export const listRecentKnowledgeImports = (params) =>
  request({ url: '/admin/knowledge/imports/recent', method: 'get', params, timeout: 60000 })

// 恢复最近一次分析候选快照或失败原因
export const getKnowledgeImportAnalysis = (requestId) =>
  request({
    url: `/admin/knowledge/imports/${encodeURIComponent(requestId)}/analysis`,
    method: 'get',
    timeout: 60000
  })

export const getKnowledgeImport = (requestId) =>
  request({ url: `/admin/knowledge/imports/${encodeURIComponent(requestId)}`, method: 'get' })

export const previewKnowledgeImport = (requestId) =>
  request({ url: `/admin/knowledge/imports/${encodeURIComponent(requestId)}/preview`, method: 'get' })

export const approveKnowledgeImport = (requestId) =>
  request({ url: `/admin/knowledge/imports/${encodeURIComponent(requestId)}/approve`, method: 'post' })

export const rejectKnowledgeImport = (requestId, reasonCode) =>
  request({
    url: `/admin/knowledge/imports/${encodeURIComponent(requestId)}/reject`,
    method: 'post',
    data: { reasonCode }
  })

// 上传必须带幂等键；与文件 SHA 去重是两个独立概念
export const uploadKnowledgeFile = (file, title, idempotencyKey) => {
  const form = new FormData()
  form.append('file', file)
  if (title) form.append('title', title)
  return request({
    url: '/admin/knowledge/imports',
    method: 'post',
    data: form,
    headers: { 'Content-Type': 'multipart/form-data', 'Idempotency-Key': idempotencyKey },
    timeout: 120000
  })
}

// AI 分析：只返回待确认候选，不创建草稿、不发布
export const analyzeKnowledgeImport = (requestId) =>
  request({
    url: `/admin/knowledge/imports/${encodeURIComponent(requestId)}/analyze`,
    method: 'post',
    timeout: 180000
  })

// 管理员确认后，把所选候选保存到原有知识草稿箱
export const saveKnowledgeImportDrafts = (requestId, data) =>
  request({
    url: `/admin/knowledge/imports/${encodeURIComponent(requestId)}/drafts`,
    method: 'post',
    data,
    timeout: 120000
  })

// 原件下载走后端鉴权流式接口，不返回任何存储直链
export const downloadKnowledgeImport = async (requestId, fileName) => {
  const response = await request({
    url: `/admin/knowledge/imports/${encodeURIComponent(requestId)}/content`,
    method: 'get',
    responseType: 'blob'
  })
  const blob = response instanceof Blob ? response : response?.data
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName || 'knowledge-original.txt'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}
