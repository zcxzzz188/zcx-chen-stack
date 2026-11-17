import { afterEach, beforeEach, expect, it, vi } from 'vite-plus/test'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import Library from '@/views/ai/library/index.vue'
import { runKnowledgeBatch } from '@/views/ai/library/batch'
import request from '@/utils/Request'

vi.mock('@/utils/Request', () => ({ default: vi.fn() }))

const time = '2026-11-13T10:05:41.138246'
const row = { id: 85, title: '文档导航', status: 'READY', updatedAt: time, enabled: true }
let wrapper

beforeEach(() => {
  request.mockReset().mockImplementation(async ({ url, params }) => ({
    data: url.endsWith('/capabilities') ? { ragManage: true, wikiPublish: true } :
      url.endsWith('/list') ? { items: [{ ...row, id: params.page === 2 ? 86 : 85 }], total: 20 } : null,
  }))
})
afterEach(() => { wrapper?.unmount(); vi.restoreAllMocks() })

async function open(path) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path, component: Library }] })
  await router.push(path)
  wrapper = mount(Library, { attachTo: document.body, global: { plugins: [ElementPlus, router] } })
  await flushPromises()
}

it('向服务端提交筛选和分页条件，翻页后清空当前页选择', async () => {
  await open('/ai/documents')
  await wrapper.get('input[placeholder="搜索标题"]').setValue('导航')
  await wrapper.getComponent({ name: 'SearchButtons' }).vm.$emit('search')
  await flushPromises()
  expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ params: expect.objectContaining({ page: 1, q: '导航', size: 10 }) }))
  const table = wrapper.getComponent({ name: 'DataTable' })
  table.vm.$emit('selection-change', [row])
  await flushPromises()
  expect(wrapper.text()).toContain('已选中 1 项')
  const pagination = wrapper.getComponent({ name: 'Pagination' })
  pagination.vm.$emit('update:currentPage', 2)
  pagination.vm.$emit('current-change', 2)
  await flushPromises()
  expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ params: expect.objectContaining({ page: 2, q: '导航' }) }))
  expect(wrapper.text()).toContain('已选中 0 项')
})

it('缺少写权限时列表不显示批量停用和编辑入口', async () => {
  request.mockImplementation(async ({ url }) => ({
    data: url.endsWith('/capabilities') ? {} : { items: [row], total: 1 },
  }))
  await open('/ai/rag')
  wrapper.getComponent({ name: 'DataTable' }).vm.$emit('selection-change', [row])
  await flushPromises()
  expect(wrapper.text()).not.toContain('批量停用')
  expect(wrapper.text()).not.toContain('编辑')
})

it('取消批量停用不会请求变更，确认提交选中记录的时间版本', async () => {
  await open('/ai/rag')
  wrapper.getComponent({ name: 'DataTable' }).vm.$emit('selection-change', [row])
  await flushPromises()
  const button = wrapper.findAll('button').find(item => item.text().trim() === '批量停用')
  const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel').mockResolvedValueOnce('confirm')
  await button.trigger('click')
  await flushPromises()
  expect(request.mock.calls.some(([config]) => config.method === 'put')).toBe(false)
  await button.trigger('click')
  await flushPromises()
  expect(confirm).toHaveBeenCalledTimes(2)
  expect(request).toHaveBeenCalledWith(expect.objectContaining({
    url: '/admin/knowledge-library/entries/85/enabled', method: 'put', data: { enabled: false, expectedUpdatedAt: time },
  }))
})

it('批量只执行合格项，保留权限失败的可核验原因并继续处理后续项', async () => {
  const update = vi.fn().mockRejectedValueOnce({ response: { status: 403 } }).mockResolvedValueOnce(undefined)
  const result = await runKnowledgeBatch([row, { ...row, id: 86, enabled: false }, { ...row, id: 87 }], item => item.enabled, update)
  expect(update.mock.calls.map(([item]) => item.id)).toEqual([85, 87])
  expect(result).toEqual({ success: 1, skipped: 1, failed: [{ id: 85, title: row.title, reason: '没有操作权限' }] })
})

const findButton = label => wrapper.findAll('button').find(button => button.text() === label)
const submitSearch = () => wrapper.get('.search-controls form').trigger('submit')

it.each([
  ['/ai/documents', '/documents/list', { message: '请求超时，请重试' }],
  ['/ai/rag', '/entries/list', { msg: '没有查询权限' }],
  ['/ai/wiki', '/pages/list', { msg: '查询参数无效，请重新筛选' }],
])('%s 列表失败后保留刷新入口，恢复时清除错误', async (path, endpoint, failure) => {
  let reads = 0
  request.mockImplementation(async ({ url }) => {
    if (url.endsWith('/capabilities')) return { data: {} }
    expect(url).toBe('/admin/knowledge-library' + endpoint)
    if (++reads === 1) throw failure
    return { data: { items: [row], total: 1 } }
  })
  await open(path)
  const message = failure.message || failure.msg
  expect(wrapper.text()).toContain(message)
  const refresh = findButton('刷新')
  expect(refresh.element.disabled).toBe(false)
  await refresh.trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain(row.title)
  expect(wrapper.text()).not.toContain(message)
  expect(reads).toBe(2)
})

it.each([['RAG', 'rag', 'rag'], ['Wiki', 'wiki', 'wiki'], ['Hybrid', 'hybrid', 'wiki']])(
  '检索 %s 使用实际 API 契约，展示请求、实际命中、片段与来源',
  async (label, mode, engine) => {
    const content = '这是可展开的完整片段。'.repeat(35)
    const answer = '这是完整回答。'.repeat(60)
    request.mockResolvedValueOnce({ data: {} }).mockResolvedValueOnce({
      data: { engine, elapsedMs: 12, hits: [{ title: '知识标题', content, url: '/setting' }], answer },
    })
    await open('/ai/search')
    expect(findButton('刷新')).toBeUndefined()
    await wrapper.get(`input[type="radio"][value="${mode}"]`).setValue()
    await wrapper.get('textarea').setValue(' 如何开启评论通知？ ')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await submitSearch()
    await flushPromises()
    expect(request).toHaveBeenLastCalledWith(expect.objectContaining({
      url: '/admin/knowledge-library/verification', method: 'post', data: { mode, question: '如何开启评论通知？', answer: true },
    }))
    expect(wrapper.text()).toContain(`请求 ${label}`)
    if (mode !== engine) expect(wrapper.text()).toContain('实际 Wiki')
    expect(wrapper.text()).toContain('1 条命中 · 12 ms')
    expect(wrapper.get('.search-hit details').text()).toContain(content)
    expect(wrapper.get('.answer-text').text()).toBe(answer)
    expect(wrapper.get('.answer-sources').text()).toContain('知识标题 · /setting')
    await findButton('重置').trigger('click')
    expect(wrapper.get('textarea').element.value).toBe('')
    expect(wrapper.get('input[type="checkbox"]').element.checked).toBe(false)
    expect(wrapper.find('.search-hit').exists()).toBe(false)
    expect(wrapper.find('.answer-text').exists()).toBe(false)
    expect(wrapper.text()).toContain('输入问题后开始检索')
  },
)

it('展示空结果和错误，重置后丢弃此前仍在运行的请求', async () => {
  let resolveSearch
  request.mockResolvedValueOnce({ data: {} })
    .mockResolvedValueOnce({ data: { engine: 'wiki', hits: [], elapsedMs: 0 } })
    .mockRejectedValueOnce({ message: '检索服务暂不可用' })
    .mockImplementationOnce(() => new Promise(resolve => { resolveSearch = resolve }))
  await open('/ai/search')
  await wrapper.get('textarea').setValue('关键词')
  await submitSearch()
  await flushPromises()
  expect(wrapper.text()).toContain('没有匹配的知识')
  expect(wrapper.text()).toContain('0 条命中 · 0 ms')
  await submitSearch()
  await flushPromises()
  expect(wrapper.text()).toContain('检索服务暂不可用')
  await submitSearch()
  expect(wrapper.text()).toContain('正在检索知识')
  await findButton('重置').trigger('click')
  resolveSearch({ data: { engine: 'wiki', hits: [], elapsedMs: 1 } })
  await flushPromises()
  expect(wrapper.text()).toContain('输入问题后开始检索')
  expect(wrapper.text()).not.toContain('没有匹配的知识')
})

const initialSettings = {
  mode: 'hybrid', model: 'deepseek-flash', temperature: 0.2, maxTokens: 1024,
  topK: 5, dailyLimit: 100, prompt: '请使用中文回答。', replyStyle: 'PROFESSIONAL_CONCISE',
}

it('只显示固定回复风格，变更后单独保存并读回生效值', async () => {
  let live = { ...initialSettings }
  request.mockImplementation(async ({ url, method, data }) => {
    if (url.endsWith('/capabilities')) return { data: { configManage: true } }
    if (method === 'put') live = { ...live, ...data }
    return { data: { ...live } }
  })
  await open('/ai/config')
  expect(wrapper.find('textarea').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('模型参数')
  expect(wrapper.get('[aria-label="客服回复风格"]').findAll('input[type="radio"]')).toHaveLength(3)
  expect(findButton('保存回复风格').element.disabled).toBe(true)
  await wrapper.get('input[value="FRIENDLY_NATURAL"]').setValue()
  await findButton('保存回复风格').element.form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
  expect(request.mock.calls.filter(([req]) => req.method === 'put')).toEqual([[expect.objectContaining({
    url: '/admin/knowledge-library/settings/reply-style', data: { replyStyle: 'FRIENDLY_NATURAL' },
  })]])
  expect(findButton('保存回复风格').element.disabled).toBe(true)
})

it('配置分区保存合并最新配置，防止重复提交并读回生效状态', async () => {
  let live = { ...initialSettings }
  request.mockImplementation(async ({ url, method, data }) => {
    if (url.endsWith('/capabilities')) return { data: { configManage: true } }
    if (method === 'put') live = data
    return { data: { ...live } }
  })
  await open('/ai/config')
  expect(wrapper.get('.settings-summary').text()).toContain('deepseek-flash')
  expect(wrapper.get('.settings-summary').text()).toContain('100 次')
  await wrapper.get('input[type="radio"][value="rag"]').setValue()
  const form = wrapper.get('.settings-grid form')
  live = { ...live, topK: 6 }
  await form.trigger('submit')
  await form.trigger('submit')
  await flushPromises()
  expect(request.mock.calls.filter(([req]) => req.method === 'put')).toEqual([[expect.objectContaining({
    url: '/admin/knowledge-library/settings', data: { ...initialSettings, mode: 'rag', topK: 6 },
  })]])
  expect(wrapper.get('.settings-summary').text()).toContain('RAG')
  expect(wrapper.get('input[type="radio"][value="rag"]').element.checked).toBe(true)
})

it('只读配置没有写入口，密钥也不会渲染到表单', async () => {
  request.mockImplementation(async ({ url }) => ({
    data: url.endsWith('/capabilities') ? { configManage: false } : { ...initialSettings, apiKey: 'fixture-secret' },
  }))
  await open('/ai/config')
  expect(findButton('保存工作模式')).toBeUndefined()
  expect(findButton('保存回复风格')).toBeUndefined()
  expect(wrapper.get('input[value="FRIENDLY_NATURAL"]').element.disabled).toBe(true)
  expect(wrapper.get('input[type="radio"][value="rag"]').element.disabled).toBe(true)
  expect(wrapper.html()).not.toContain('fixture-secret')
  await wrapper.get('.settings-grid form').trigger('submit')
  expect(request.mock.calls.some(([req]) => req.method === 'put')).toBe(false)
})

it('配置保存失败时保留编辑值并显示服务端错误', async () => {
  request.mockImplementation(async ({ url, method }) => {
    if (url.endsWith('/capabilities')) return { data: { configManage: true } }
    if (method === 'put') throw { message: 'AI 配置暂无法保存' }
    return { data: initialSettings }
  })
  await open('/ai/config')
  await wrapper.get('input[type="radio"][value="rag"]').setValue()
  await wrapper.get('.settings-grid form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('AI 配置暂无法保存')
  expect(wrapper.get('input[type="radio"][value="rag"]').element.checked).toBe(true)
  expect(wrapper.get('.settings-summary').text()).toContain('Hybrid')
})
