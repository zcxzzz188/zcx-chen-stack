import request from '@/utils/Request'

const base = '/admin/wiki'
const id = encodeURIComponent
export const wikiApi = {
  access: () => request({ url: `${base}/access`, method: 'get' }),
  sources: (params) => request({ url: `${base}/sources`, method: 'get', params }),
  inspect: (data) => request({ url: `${base}/sources/inspect`, method: 'post', data }),
  authorize: (data) => request({ url: `${base}/sources/authorize`, method: 'post', data }),
  revoke: (data) => request({ url: `${base}/sources/revoke`, method: 'post', data }),
  jobs: (params) => request({ url: `${base}/jobs`, method: 'get', params }),
  compile: (data) => request({ url: `${base}/jobs`, method: 'post', data }),
  cancel: (requestId) => request({ url: `${base}/jobs/${id(requestId)}/cancel`, method: 'post' }),
  draft: (requestId) => request({ url: `${base}/jobs/${id(requestId)}/draft`, method: 'get' }),
  affected: (params) => request({ url: `${base}/affected`, method: 'get', params }),
  pages: () => request({ url: `${base}/pages`, method: 'get' }),
  versions: (pageId) => request({ url: `${base}/pages/${id(pageId)}/versions`, method: 'get' }),
  revision: (data) => request({ url: `${base}/revisions`, method: 'post', data }),
  submit: (revisionId, data) => request({ url: `${base}/revisions/${id(revisionId)}/submit`, method: 'post', data }),
  approve: (revisionId, data) => request({ url: `${base}/revisions/${id(revisionId)}/approve`, method: 'post', data }),
  publish: (revisionId, data) => request({ url: `${base}/revisions/${id(revisionId)}/publish`, method: 'post', data }),
  withdraw: (pageId, data) => request({ url: `${base}/pages/${id(pageId)}/withdraw`, method: 'post', data }),
  restore: (pageId, data) => request({ url: `${base}/pages/${id(pageId)}/restore`, method: 'post', data }),
}
