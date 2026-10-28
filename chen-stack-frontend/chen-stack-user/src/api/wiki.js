import request from '@/utils/Request'

export const listWikiPages = (params) => request({ url: '/wiki/pages', method: 'get', params })
export const getWikiPage = (id) => request({ url: `/wiki/pages/${encodeURIComponent(id)}`, method: 'get' })
