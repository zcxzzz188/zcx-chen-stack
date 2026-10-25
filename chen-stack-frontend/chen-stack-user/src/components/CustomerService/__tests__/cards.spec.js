import { it, expect, vi, beforeEach, afterEach } from 'vite-plus/test'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from '@/stores/userStore'
import Cards from '../CustomerServiceCards.vue'

const api = vi.hoisted(() => ({ getEditableArticle: vi.fn(), listEditableArticles: vi.fn(), push: vi.fn() }))
vi.mock('@/api/ai', () => api)
vi.mock('vue-router', () => ({ useRouter: () => ({ push: api.push }) }))
const article = { articleId: 42, title: '<img src=x onerror=alert(1)>', editStatus: 1, examineStatus: 0, canEdit: true }
const selection = () => ({ type: 'ARTICLE_SELECTION', text: '请选择文章', articles: [article], page: 1, pageSize: 5, hasMore: true })
let store, wrapper
const render = () => { wrapper = mount(Cards, { props: { event: selection() }, global: { stubs: { ElButton: { template: '<button><slot /></button>' } } } }); return wrapper }
beforeEach(() => { vi.clearAllMocks(); setActivePinia(createPinia()); store = useUserStore(); store.user = { id: 7 } })
afterEach(() => wrapper?.unmount())

it.each([
  ['OPEN_ARTICLE_MANAGE', '去管理文章', '/creation/articlemanage'],
  ['OPEN_MESSAGES', '去站内私信', '/message'],
])('真实导航事件 %s 渲染可点击按钮并进入白名单页面', async (action, text, path) => {
  wrapper = mount(Cards, { props: { event: { type: 'NAVIGATION_ACTION', action, text, supportStatus: 'SUPPORTED' } },
    global: { stubs: { ElButton: { template: '<button><slot /></button>' } } } })
  expect(wrapper.find('button').text()).toBe(text)
  await wrapper.find('button').trigger('click')
  await flushPromises()
  expect(api.push).toHaveBeenCalledWith({ path })
  expect(wrapper.emitted('navigate')).toHaveLength(1)
})

it('文章标题按纯文本显示，点击前复核授权并导航准确文章', async () => {
  api.getEditableArticle.mockResolvedValue({ data: article })
  render()
  expect(wrapper.text()).toContain(article.title)
  expect(wrapper.find('img').exists()).toBe(false)
  await wrapper.find('button').trigger('click')
  await flushPromises()
  expect(api.getEditableArticle).toHaveBeenCalledWith(42)
  expect(api.push).toHaveBeenCalledWith({ path: '/editor', query: { articleId: 42 } })
})

it('权限已撤回时不导航，并允许认证接口重新分页查询', async () => {
  api.getEditableArticle.mockRejectedValue(new Error('forbidden'))
  api.listEditableArticles.mockResolvedValue({ data: { ...selection(), type: 'EMPTY_RESULT', articles: [], hasMore: false } })
  render()
  await wrapper.find('button').trigger('click')
  await flushPromises()
  expect(api.push).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('文章已不可编辑')
  await wrapper.find('button').trigger('click')
  await flushPromises()
  expect(api.listEditableArticles).toHaveBeenCalledWith(1, 5)
  expect(wrapper.findAll('.article-choice')).toHaveLength(0)
})

it('切换账号后丢弃旧账号授权结果', async () => {
  let resolve
  api.getEditableArticle.mockReturnValue(new Promise(done => { resolve = done }))
  render()
  await wrapper.find('button').trigger('click')
  store.user = { id: 8 }
  resolve({ data: article })
  await flushPromises()
  expect(api.push).not.toHaveBeenCalled()
})
