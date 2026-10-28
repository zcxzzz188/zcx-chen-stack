import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import Directory from '../index.vue'
import Detail from '../Detail.vue'
import { listWikiPages, getWikiPage } from '@/api/wiki'

vi.mock('@/api/wiki', () => ({ listWikiPages: vi.fn(), getWikiPage: vi.fn() }))

const card = { id: 7, title: 'Redis 缓存', summary: '缓存失效与一致性', version: 2, publishedAt: '2026-10-26T10:00:00', topics: ['redis'] }
const publication = { ...card, markdown: '# Redis\n\n正式正文\n\n```java\nreturn value;\n```', citations: [{ title: '公开来源', url: '/user/1/article/2', version: 'v1' }], related: [{ ...card, id: 8, title: '一致性' }] }

async function open(path) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/wiki', component: Directory }, { path: '/wiki/:id', component: Detail }] })
  await router.push(path)
  await router.isReady()
  const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  vi.resetAllMocks()
  listWikiPages.mockResolvedValue({ data: { items: [card], topics: ['redis'], total: 1, page: 1, size: 12 } })
  getWikiPage.mockResolvedValue({ data: publication })
})

describe('Public Wiki pages', () => {
  it('searches the independent Wiki API and opens the published page', async () => {
    const { wrapper, router } = await open('/wiki?q=Redis&topic=redis')
    expect(listWikiPages).toHaveBeenCalledWith({ q: 'Redis', topic: 'redis', page: 1, size: 12 })
    expect(wrapper.text()).toContain('缓存失效与一致性')
    await wrapper.get('a[href="/wiki/7"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/wiki/7')
    expect(getWikiPage).toHaveBeenCalledWith('7')
    expect(wrapper.text()).toContain('正式正文')
    expect(wrapper.get('a[href="/user/1/article/2"]').text()).toBe('公开来源')
    expect(wrapper.get('a[href="/wiki/8"]').text()).toBe('一致性')
    expect(wrapper.text()).toContain('SEMANTIC_NOT_VERIFIED')
    wrapper.unmount()
  })

  it('submits keyword and category and supports paged results', async () => {
    listWikiPages.mockResolvedValue({ data: { items: [card], topics: ['redis'], total: 24 } })
    const { wrapper, router } = await open('/wiki')
    await wrapper.get('input').setValue(' 缓存 ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.query.q).toBe('缓存')
    await wrapper.get('.wiki-pagination button:last-child').trigger('click')
    await flushPromises()
    expect(listWikiPages).toHaveBeenLastCalledWith({ q: '缓存', topic: '', page: 2, size: 12 })
    wrapper.unmount()
  })

  it('shows empty and retryable failed directory states', async () => {
    listWikiPages.mockResolvedValue({ data: { items: [], topics: [], total: 0 } })
    const { wrapper } = await open('/wiki')
    expect(wrapper.text()).toContain('暂无已发布')
    listWikiPages.mockRejectedValue(new Error('network'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('重新加载')
    wrapper.unmount()
  })

  it('never displays old content while loading or after withdrawal', async () => {
    const { wrapper, router } = await open('/wiki/7')
    let rejectRequest
    getWikiPage.mockImplementation(() => new Promise((resolve, reject) => { rejectRequest = reject }))
    await router.push('/wiki/8')
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('正在加载')
    expect(wrapper.text()).not.toContain('正式正文')
    rejectRequest({ response: { status: 404 } })
    await flushPromises()
    expect(wrapper.text()).toContain('页面当前不可访问')
    expect(wrapper.text()).not.toContain('正式正文')
    wrapper.unmount()
  })

  it('rejects invalid IDs without requesting another object', async () => {
    const { wrapper } = await open('/wiki/not-an-id')
    expect(getWikiPage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('页面地址无效')
    wrapper.unmount()
  })
})
