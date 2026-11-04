import { afterEach, beforeEach, describe, expect, it, vi } from 'vite-plus/test'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import RagManagement from '@/views/ai/rag/index.vue'
import KnowledgeSearch from '@/views/ai/search/index.vue'
import { knowledgeApi } from '@/api/knowledge'
import { getMenuList } from '@/api/menu'
import { useUserStore } from '@/stores/userStore'

vi.mock('@/api/knowledge', () => ({ knowledgeApi: Object.fromEntries(['status', 'documents', 'document', 'versions', 'update', 'revoke', 'drafts', 'createDraft', 'updateDraft', 'discardDraft', 'publishDraft', 'pending', 'resume', 'test', 'usage'].map((key) => [key, vi.fn()])) }))
vi.mock('@/api/menu', () => ({ getMenuList: vi.fn() }))
vi.mock('@/api/user', () => ({ info: vi.fn() }))
let wrapper
const document = { sourceId: 'account-help', title: '账号帮助', text: '使用账号密码登录', version: 'v1', sourceUrl: '/login', effective: true, generation: 2, contentHash: 'a'.repeat(64), keywords: ['登录'] }
beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  knowledgeApi.status.mockResolvedValue({ data: { mode: 'rag', supportedModes: ['rag', 'wiki'], documentCount: 1, generation: 5, rag: { ragAvailability: 'CONFIGURED' }, capabilities: { ragRead: true, ragManage: true, test: true } } })
  knowledgeApi.documents.mockResolvedValue({ data: { items: [document], total: 1 } })
  knowledgeApi.document.mockResolvedValue({ data: document })
  knowledgeApi.versions.mockResolvedValue({ data: [] })
  knowledgeApi.drafts.mockResolvedValue({ data: [] })
  knowledgeApi.pending.mockResolvedValue({ data: [] })
  knowledgeApi.usage.mockResolvedValue({ data: [] })
})
afterEach(() => { wrapper?.unmount(); window.document.body.innerHTML = ''; vi.restoreAllMocks() })
async function open(component) { wrapper = mount(component, { attachTo: window.document.body, global: { plugins: [ElementPlus] } }); await flushPromises() }
function button(text) { return [...window.document.querySelectorAll('button')].find((item) => item.textContent.trim() === text) }
function dialogButtonIn(dialog, text) { return [...dialog.querySelectorAll('button')].find((item) => item.textContent.trim() === text) }
describe('AI知识管理产品体验', () => {
  it.each([
    ['rag', 'RAG 知识检索'],
    ['wiki', 'Wiki 知识问答'],
    ['hybrid', '智能混合'],
    ['chat', '普通对话（历史配置）'],
  ])('检索验证统一展示 %s 模式及历史使用记录', async (mode, label) => {
    knowledgeApi.status.mockResolvedValue({ data: { mode, supportedModes: ['rag', 'wiki'], capabilities: { test: true } } })
    knowledgeApi.usage.mockResolvedValue({ data: [{ time: '2026-11-04T00:00:00Z', knowledgeMode: mode, sources: [] }] })
    await open(KnowledgeSearch)
    expect(wrapper.find('.search-form > p').text()).toContain(label)
    expect(wrapper.find('.el-table').text()).toContain(label)
  })

  it('读取失败不冒充空知识，原页刷新成功后展示实际文档', async () => {
    knowledgeApi.status.mockRejectedValueOnce(new Error('知识或索引状态已变化，请重新读取后重试'))
    await open(RagManagement)
    expect(wrapper.text()).toContain('知识列表暂未加载，请刷新重试')
    expect(wrapper.text()).not.toContain('暂无符合条件的知识文档')
    expect(wrapper.find('.el-pagination').exists()).toBe(false)
    expect(knowledgeApi.documents).not.toHaveBeenCalled()
    button('刷新').click(); await flushPromises()
    expect(wrapper.text()).toContain('账号帮助')
    expect(wrapper.text()).not.toContain('知识列表暂未加载')
    expect(wrapper.find('.el-pagination').exists()).toBe(true)
  })
  it('所有知识页面组件通过服务端菜单生成，未分配时不补造知识路由', async () => {
    const children = ['rag', 'wiki', 'sources', 'tasks', 'search'].map((name, index) => ({ id: index + 2, parentId: 1, name, path: `/ai/${name}`, component: `/ai/${name}`, sort: index }))
    getMenuList.mockResolvedValue({ data: [{ id: 1, parentId: 0, name: 'AI知识管理', path: '/ai', sort: 10 }, ...children] })
    const store = useUserStore()
    const routes = await store.loadMenusAndRoutes(true)
    expect(routes[0].children).toHaveLength(5)
    expect(routes[0].children.every((route) => typeof route.component === 'function')).toBe(true)
    getMenuList.mockResolvedValue({ data: [] })
    expect(await store.loadMenusAndRoutes(true)).toEqual([])
  })
  it('文档展示有效性并移除索引技术详情', async () => {
    await open(RagManagement)
    expect(wrapper.text()).toContain('账号帮助')
    expect(wrapper.text()).toContain('已审校可用')
    expect(wrapper.find('details').exists()).toBe(false)
    button('查看详情与来源').click(); await flushPromises()
    expect(window.document.body.textContent).toContain('使用账号密码登录')
    expect(window.document.body.textContent).toContain('来源：平台使用说明')
    expect(window.document.querySelector('a[href="/login"]')).toBeNull()
  })
  it('更新必须核验并使用新版本；失败重试保留同一幂等键和并发条件', async () => {
    knowledgeApi.update.mockRejectedValueOnce(new Error('TIMEOUT')).mockResolvedValueOnce({ data: { status: 'COMPLETED' } })
    await open(RagManagement)
    button('更新知识').click(); await flushPromises()
    const dialog = window.document.querySelector('.el-dialog')
    expect(button('确认更新').disabled).toBe(true)
    const inputs = dialog.querySelectorAll('input')
    inputs[1].value = 'v2'; inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
    dialog.querySelector('input[type="checkbox"]').click(); await flushPromises()
    button('确认更新').click(); await flushPromises()
    expect(knowledgeApi.update).toHaveBeenCalledOnce()
    const initial = knowledgeApi.update.mock.calls[0]
    expect(initial[1]).toMatchObject({ expectedGeneration: 2, expectedContentHash: 'a'.repeat(64), version: 'v2' })
    button('确认更新').click(); await flushPromises()
    expect(knowledgeApi.update.mock.calls[1]).toEqual(initial)
  })
  it('撤权必须明确确认，保留当前版本校验且没有自动重试', async () => {
    knowledgeApi.revoke.mockRejectedValue(new Error('GENERATION_CONFLICT'))
    await open(RagManagement)
    button('撤销使用').click(); await flushPromises()
    expect(button('确认撤销').disabled).toBe(true)
    window.document.querySelector('.el-dialog input[type="checkbox"]').click(); await flushPromises()
    button('确认撤销').click(); await flushPromises()
    expect(knowledgeApi.revoke).toHaveBeenCalledOnce()
    expect(knowledgeApi.revoke.mock.calls[0][1]).toMatchObject({ expectedGeneration: 2, expectedContentHash: 'a'.repeat(64) })
    expect(window.document.body.textContent).toContain('知识已被其他操作更新')
  })
  it('只读角色不显示知识写操作', async () => {
    knowledgeApi.status.mockResolvedValue({ data: { capabilities: { ragRead: true } } })
    await open(RagManagement)
    expect(button('更新知识')).toBeUndefined()
    expect(button('撤销使用')).toBeUndefined()
    expect(button('查看详情与来源')).toBeTruthy()
    expect(knowledgeApi.pending).not.toHaveBeenCalled()
  })
  it('刷新发现未完成操作，确认后只恢复原操作且不会自动重试', async () => {
    knowledgeApi.pending.mockResolvedValue({ data: [{ operationId: 31, title: '账号帮助', action: 'UPDATE', status: 'COMMITTED' }] })
    knowledgeApi.resume.mockRejectedValue(new Error('INDEX_UNAVAILABLE'))
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    await open(RagManagement)
    expect(wrapper.text()).toContain('以下操作尚未完成，可继续处理')
    expect(knowledgeApi.resume).not.toHaveBeenCalled()
    button('恢复原操作').click(); await flushPromises()
    expect(confirm).toHaveBeenCalledOnce()
    expect(knowledgeApi.resume).toHaveBeenCalledExactlyOnceWith(31)
    expect(knowledgeApi.update).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('知识暂未加载')
    expect(wrapper.text()).not.toContain('INDEX_UNAVAILABLE')
  })
  it('检索使用所选真实引擎，错误不会冒充未命中', async () => {
    knowledgeApi.status.mockResolvedValue({ data: { mode: 'wiki', supportedModes: ['rag', 'wiki'], capabilities: { test: true } } })
    knowledgeApi.test.mockResolvedValueOnce({ data: { items: [{ id: '1', title: '密码帮助', text: '从登录页进入忘记密码', version: '2' }] } }).mockRejectedValueOnce(new Error('INDEX_UNAVAILABLE'))
    await open(KnowledgeSearch)
    await wrapper.find('input').setValue('忘记密码怎么办？')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(knowledgeApi.test).toHaveBeenCalledExactlyOnceWith({ query: '忘记密码怎么办？', mode: 'wiki' })
    expect(wrapper.text()).toContain('密码帮助')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('知识服务暂不可用')
    expect(wrapper.text()).not.toContain('未找到可用知识')
    expect(wrapper.text()).not.toContain('密码帮助')
  })
  it('真实切换知识类型时提示Wiki关键词，切回RAG保留问句输入', async () => {
    knowledgeApi.test.mockResolvedValue({ data: { mode: 'wiki', items: [] } })
    await open(KnowledgeSearch)
    expect(wrapper.get('.el-form-item__label').text()).toBe('要验证的问题')
    expect(wrapper.get('input[placeholder="例如：忘记密码怎么办？"]').attributes('maxlength')).toBe('500')
    expect(wrapper.text()).not.toContain('完整问句可能无法命中')
    await wrapper.get('.el-select__wrapper').trigger('click'); await flushPromises()
    const option = (label) => [...window.document.querySelectorAll('.el-select-dropdown__item')].find((item) => item.textContent.trim() === label)
    option('Wiki 知识问答').click(); await flushPromises()
    expect(wrapper.get('.el-form-item__label').text()).toBe('要检索的关键词')
    const input = wrapper.get('input[placeholder="例如：关注"]')
    expect(input.attributes('maxlength')).toBe('80')
    expect(wrapper.text()).toContain('完整问句可能无法命中')
    await input.setValue('关注')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(knowledgeApi.test).toHaveBeenCalledExactlyOnceWith({ query: '关注', mode: 'wiki' })
    await wrapper.get('.el-select__wrapper').trigger('click'); await flushPromises()
    option('RAG 知识检索').click(); await flushPromises()
    expect(wrapper.get('.el-form-item__label').text()).toBe('要验证的问题')
    expect(wrapper.get('input[placeholder="例如：忘记密码怎么办？"]').attributes('maxlength')).toBe('500')
    expect(wrapper.text()).not.toContain('完整问句可能无法命中')
    expect(knowledgeApi.test).toHaveBeenCalledOnce()
  })

  it('新增知识先保存草稿，预览并确认后才发布到知识目录', async () => {
    knowledgeApi.createDraft.mockResolvedValue({ data: { draftId: 'draft-1', title: '审核状态说明', text: '在文章管理查看审核状态。', version: 'manual-1', sourceUrl: '/creation/articlemanage', keywords: ['审核'] } })
    knowledgeApi.drafts.mockResolvedValue({ data: [{ draftId: 'draft-1', title: '审核状态说明', text: '在文章管理查看审核状态。', version: 'manual-1', sourceUrl: '/creation/articlemanage', sourceUrlLabel: '文章管理', keywords: ['审核'], state: 'DRAFT', updatedAt: '2026-11-03 20:00:00' }] })
    knowledgeApi.publishDraft.mockResolvedValue({ data: { operationId: 'manage-knowledge-1', status: 'COMPLETED', generation: 6 } })
    await open(RagManagement)
    button('新增知识').click(); await flushPromises()
    const dialog = [...window.document.querySelectorAll('.el-dialog')].find((item) => item.textContent.includes('新增知识'))
    const inputs = dialog.querySelectorAll('input')
    inputs[0].value = '审核状态说明'; inputs[0].dispatchEvent(new Event('input', { bubbles: true }))
    const sourceSelect = wrapper.findAllComponents({ name: 'ElSelect' }).find((item) => String(item.props('placeholder') || '').includes('关联'))
    sourceSelect.vm.$emit('update:modelValue', '/creation/articlemanage'); await flushPromises()
    const textareas = dialog.querySelectorAll('textarea')
    textareas[0].value = '审核'; textareas[0].dispatchEvent(new Event('input', { bubbles: true }))
    textareas[1].value = '在文章管理查看审核状态。'; textareas[1].dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const saveDraftButton = dialogButtonIn(dialog, '保存草稿'); expect(saveDraftButton.disabled).toBe(false); saveDraftButton.click(); await flushPromises()
    expect(knowledgeApi.createDraft).toHaveBeenCalledWith(expect.objectContaining({
      title: '审核状态说明', text: '在文章管理查看审核状态。', version: 'manual-1', sourceUrl: '/creation/articlemanage', keywords: ['审核'],
    }))
    const draftsDialog = [...window.document.querySelectorAll('.el-dialog')].find((item) => item.textContent.includes('知识草稿'))
    expect(draftsDialog).toBeTruthy()
    dialogButtonIn(draftsDialog, '预览并发布').click(); await flushPromises()
    const publishDialog = [...window.document.querySelectorAll('.el-dialog')].find((item) => item.textContent.includes('发布前核对'))
    expect(publishDialog.textContent).toContain('在文章管理查看审核状态。')
    publishDialog.querySelector('input[type="checkbox"]').click(); await flushPromises()
    dialogButtonIn(publishDialog, '确认发布').click(); await flushPromises()
    expect(knowledgeApi.publishDraft).toHaveBeenCalledWith('draft-1', expect.objectContaining({
      expectedGeneration: 5, confirmReviewed: true,
    }))
  })

  it('知识详情展示被替换的历史版本正文', async () => {
    knowledgeApi.versions.mockResolvedValue({ data: [{ version: 'v1', title: '账号帮助', text: '旧版登录说明', sourceUrl: '/login', keywords: ['登录'], state: 'SUPERSEDED', archivedAt: '2026-11-02 10:00:00' }] })
    await open(RagManagement)
    button('查看详情与来源').click(); await flushPromises()
    expect(window.document.body.textContent).toContain('历史版本')
    expect(window.document.body.textContent).toContain('v1')
    const versionDialog = [...window.document.querySelectorAll('.el-dialog')].find((item) => item.textContent.includes('知识详情与来源'))
    dialogButtonIn(versionDialog, '查看正文').click(); await flushPromises()
    expect(versionDialog.textContent).toContain('旧版登录说明')
  })
})
