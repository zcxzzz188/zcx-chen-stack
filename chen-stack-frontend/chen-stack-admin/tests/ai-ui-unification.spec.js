import { afterEach, beforeEach, describe, expect, it, vi } from 'vite-plus/test'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import Rag from '@/views/ai/rag/index.vue'
import Search from '@/views/ai/search/index.vue'
import Wiki from '@/views/wiki/index.vue'
import ManagementCard from '@/components/management/ManagementCard.vue'
import Pagination from '@/components/data/Pagination.vue'
import TableActions from '@/components/data/TableActions.vue'
import { knowledgeApi } from '@/api/knowledge'
import { wikiApi } from '@/api/wiki'
import { formatKnowledgeTime } from '@/utils/aiPresentation'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }))
vi.mock('@/api/knowledge', () => ({ knowledgeApi: Object.fromEntries(['status', 'documents', 'pending', 'usage'].map((name) => [name, vi.fn()])) }))
vi.mock('@/api/wiki', () => ({ wikiApi: Object.fromEntries(['sources', 'jobs', 'pages'].map((name) => [name, vi.fn()])) }))
let wrapper
let count
const rows = () => Array.from({ length: count }, (_, i) => ({ id: i + 1, title: `知识 ${i + 1}`, topics: [`主题 ${i + 1}`], state: 'DRAFT_READY', knowledgeMode: 'rag', sources: [{ title: `知识 ${i + 1}` }], effective: true }))
const paged = ({ page, size }) => ({ data: { items: rows().slice((page - 1) * size, page * size), total: count } })
beforeEach(() => {
  vi.clearAllMocks()
  knowledgeApi.status.mockResolvedValue({ data: { supportedModes: ['rag', 'wiki'], capabilities: {} } })
  knowledgeApi.documents.mockImplementation(async (params) => paged(params))
  knowledgeApi.pending.mockResolvedValue({ data: [] })
  knowledgeApi.usage.mockImplementation(async () => ({ data: rows() }))
  wikiApi.sources.mockImplementation(async (params) => paged(params))
  wikiApi.jobs.mockImplementation(async (params) => paged(params))
  wikiApi.pages.mockImplementation(async () => ({ data: rows() }))
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

describe.each([
  ['原始知识', Rag, {}, knowledgeApi.documents],
  ['整理知识', Wiki, { section: 'pages' }],
  ['知识来源', Wiki, { section: 'sources' }, wikiApi.sources],
  ['智能整理', Wiki, { section: 'jobs' }, wikiApi.jobs],
  ['检索验证', Search, {}],
])('%s 统一分页', (_, component, props, api) => {
  it.each([0, 1, 25])('%i 条数据使用后台分页，边界和下一页正常', async (total) => {
    count = total
    wrapper = mount(component, { props, attachTo: document.body, global: { plugins: [ElementPlus] } })
    await flushPromises()
    const pagination = wrapper.findComponent(ManagementCard).findAllComponents(Pagination).at(-1)
    expect(pagination.props('layout')).toBe('total, prev, pager, next, jumper')
    expect(pagination.props('total')).toBe(total)
    expect(pagination.find('.el-pagination__jump input').exists()).toBe(true)
    expect(pagination.find('.btn-prev').attributes('disabled')).toBeDefined()
    expect(pagination.find('.btn-next').element.disabled).toBe(total <= 12)
    if (total > 12) {
      await pagination.find('.btn-next').trigger('click'); await flushPromises()
      expect(pagination.props('currentPage')).toBe(2)
      if (api) expect(api).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2, size: 12 }))
      const table = wrapper.findAll('.data-table').at(-1)
      expect(table.text()).toContain(props.section === 'jobs' ? '主题 13' : '知识 13')
    }
  })
})

it('操作按钮按权限及行状态禁用，其他操作仍可执行', async () => {
  wrapper = mount(TableActions, { props: { showView: true, showEdit: true, showDelete: true, disabledActions: ['edit', 'delete'] }, global: { plugins: [ElementPlus] } })
  const buttons = wrapper.findAll('button')
  expect(buttons.map((button) => button.element.disabled)).toEqual([false, true, true])
  await buttons[0].trigger('click'); await buttons[1].trigger('click'); await buttons[2].trigger('click')
  expect(wrapper.emitted('view')).toHaveLength(1)
  expect(wrapper.emitted('edit')).toBeUndefined()
  expect(wrapper.emitted('delete')).toBeUndefined()
  await wrapper.setProps({ disabled: true })
  expect(buttons[0].element.disabled).toBe(true)
})

it('时间统一显示到秒，空值和无效时间不输出原始技术字符串', () => {
  expect(formatKnowledgeTime(new Date(2026, 10, 3, 14, 5, 6))).toBe('2026-11-03 14:05:06')
  expect(formatKnowledgeTime(null)).toBe('—')
  expect(formatKnowledgeTime('invalid')).toBe('—')
})
