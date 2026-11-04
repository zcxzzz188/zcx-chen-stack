import request from '@/utils/Request'

const base = '/admin/ai-knowledge'
const id = encodeURIComponent
export const knowledgeApi = {
  status: () => request({ url: `${base}/status`, method: 'get', timeout: 60000 }),
  documents: (params) => request({ url: `${base}/rag/documents`, method: 'get', params, timeout: 60000 }),
  document: (documentId) => request({ url: `${base}/rag/documents/${id(documentId)}`, method: 'get', timeout: 60000 }),
  versions: (documentId) => request({ url: `${base}/rag/documents/${id(documentId)}/versions`, method: 'get', timeout: 60000 }),
  update: (documentId, data) => request({ url: `${base}/rag/documents/${id(documentId)}`, method: 'put', data, timeout: 120000 }),
  revoke: (documentId, data) => request({ url: `${base}/rag/documents/${id(documentId)}/revoke`, method: 'post', data, timeout: 120000 }),
  drafts: (params) => request({ url: `${base}/rag/drafts`, method: 'get', params, timeout: 60000 }),
  createDraft: (data) => request({ url: `${base}/rag/drafts`, method: 'post', data, timeout: 60000 }),
  updateDraft: (draftId, data) => request({ url: `${base}/rag/drafts/${id(draftId)}`, method: 'put', data, timeout: 60000 }),
  discardDraft: (draftId) => request({ url: `${base}/rag/drafts/${id(draftId)}`, method: 'delete', timeout: 60000 }),
  publishDraft: (draftId, data) => request({ url: `${base}/rag/drafts/${id(draftId)}/publish`, method: 'post', data, timeout: 120000 }),
  pending: () => request({ url: `${base}/rag/operations/pending`, method: 'get' }),
  resume: (operationId) => request({ url: `${base}/rag/operations/${id(operationId)}/resume`, method: 'post', data: { confirmRecovery: true }, timeout: 120000 }),
  test: (data) => request({ url: `${base}/test`, method: 'post', data, timeout: 60000 }),
  usage: () => request({ url: '/admin/ai/knowledge-usage', method: 'get', params: { limit: 20 } }),
}
