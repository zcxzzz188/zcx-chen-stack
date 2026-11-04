import { afterEach, beforeEach, describe, expect, it, vi } from 'vite-plus/test'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AiConfig from '@/views/ai/config/index.vue'
import ManagementCard from '@/components/management/ManagementCard.vue'
import { aiConfigApi } from '@/api/config'

vi.mock('@/api/config', () => ({ aiConfigApi: { status: vi.fn(), setMode: vi.fn(), setDailyLimit: vi.fn() } }))
let wrapper
const modes = [
  { mode: 'rag', label: 'RAG 知识检索', available: true, note: '只依据已审校的原始知识目录回答。' },
  { mode: 'wiki', label: 'Wiki 知识问答', available: true, note: '只依据当前已发布且来源有效的 Wiki 正文回答。' },
  { mode: 'hybrid', label: '智能混合', available: true, note: '按问题自动选择 RAG 或 Wiki，简单交流不检索知识。' },
]
const view = (overrides = {}) => ({
  status: {
    effectiveMode: 'rag', desiredMode: 'rag', applied: true, applyError: null,
    dailyLimit: 100, desiredDailyLimit: 100, quotaApplied: true, quotaApplyError: null,
    updatedAt: '2026-11-03 20:00:00', updatedBy: '管理员', configVersion: 2, source: 'DATABASE',
    modes,
    knowledge: { ragReady: true, ragSources: 54, wikiReady: true, wikiPages: 17 },
    recentChanges: [
      { label: '小辰工作模式', oldValue: 'chat', newValue: 'rag', operator: '管理员', changedAt: '2026-11-03 20:00:00' },
      { label: '每日使用额度', oldValue: '100', newValue: '150', operator: '管理员', changedAt: '2026-11-03 19:00:00' },
    ],
    ...overrides.status,
  },
  canManage: overrides.canManage ?? true,
})
function button(text) { return [...window.document.querySelectorAll('button')].find((item) => item.textContent.trim() === text) }
function radio(value) { return window.document.querySelector(`input[type="radio"][value="${value}"]`) }
beforeEach(() => {
  vi.clearAllMocks()
  aiConfigApi.status.mockResolvedValue({ data: view() })
})
afterEach(() => { wrapper?.unmount(); window.document.body.innerHTML = '' })
async function open() { wrapper = mount(AiConfig, { attachTo: window.document.body, global: { plugins: [ElementPlus] } }); await flushPromises() }

describe('AI配置', () => {
  it('只提供 RAG、Wiki 与智能混合三种可选模式，不再出现普通对话', async () => {
    await open()
    const labels = [...window.document.querySelectorAll('.mode-item__title')].map((item) => item.textContent.trim())
    expect(labels).toEqual(['RAG 知识检索', 'Wiki 知识问答', '智能混合'])
    expect(radio('chat')).toBeNull()
    expect(wrapper.text()).toContain('推荐')
  })

  it('摘要卡片使用后端返回的真实模式、额度与知识数量', async () => {
    await open()
    expect(wrapper.text()).toContain('当前小辰模式')
    expect(wrapper.text()).toContain('每日使用上限')
    expect(wrapper.text()).toContain('100 次')
    expect(wrapper.text()).toContain('已审校 54 条主题')
    expect(wrapper.text()).toContain('已发布 17 篇')
    expect(wrapper.text()).toContain('管理员配置')
  })

  it('同时显示管理员期望模式与后端实际生效模式', async () => {
    await open()
    expect(wrapper.text()).toContain('实际生效：RAG 知识检索')
    expect(wrapper.text()).toContain('当前生效')
  })

  it('修改记录能读懂历史 chat 配置且不显示技术值', async () => {
    await open()
    expect(wrapper.text()).toContain('普通对话（历史配置） → RAG 知识检索')
    expect(wrapper.text()).toContain('100 → 150 次')
    expect(wrapper.text()).not.toContain('rag 次')
  })

  it('未生效时明确提示原因，不冒充已生效', async () => {
    aiConfigApi.status.mockResolvedValue({ data: view({ status: { effectiveMode: 'rag', desiredMode: 'wiki', applied: false, applyError: '整理知识服务未启用，不能切换到此模式。' } }) })
    await open()
    expect(wrapper.text()).toContain('已保存待生效')
    expect(wrapper.text()).toContain('整理知识服务未启用，不能切换到此模式。')
  })

  it('选择智能混合会持久化并显示保存结果', async () => {
    aiConfigApi.setMode.mockResolvedValue({ data: view({ status: { effectiveMode: 'hybrid', desiredMode: 'hybrid' } }) })
    await open()
    radio('hybrid').click(); await flushPromises()
    button('保存工作模式').click(); await flushPromises()
    expect(aiConfigApi.setMode).toHaveBeenCalledExactlyOnceWith('hybrid')
    expect(wrapper.text()).toContain('实际生效：智能混合')
  })

  it('不可用模式不能被选择或保存', async () => {
    aiConfigApi.status.mockResolvedValue({ data: view({ status: { modes: modes.map((item) => item.mode === 'wiki' ? { ...item, available: false, note: '整理知识服务未启用。' } : item) } }) })
    await open()
    expect(radio('wiki').disabled).toBe(true)
    expect(wrapper.text()).toContain('暂不可用')
  })

  it('每日额度保存后显示新的生效值', async () => {
    aiConfigApi.setDailyLimit.mockResolvedValue({ data: view({ status: { dailyLimit: 150, desiredDailyLimit: 150 } }) })
    await open()
    const input = window.document.querySelector('.quota-row input')
    input.value = '150'; input.dispatchEvent(new Event('input', { bubbles: true })); input.dispatchEvent(new Event('change', { bubbles: true }))
    await flushPromises()
    button('保存额度').click(); await flushPromises()
    expect(aiConfigApi.setDailyLimit).toHaveBeenCalledExactlyOnceWith(150)
    expect(wrapper.text()).toContain('150 次')
  })

  it('只读账号不能修改配置，但仍可查看当前值', async () => {
    aiConfigApi.status.mockResolvedValue({ data: view({ canManage: false }) })
    await open()
    expect(button('保存工作模式').disabled).toBe(true)
    expect(button('保存额度').disabled).toBe(true)
    expect(wrapper.text()).toContain('当前账号没有修改AI配置的权限')
    expect(wrapper.text()).toContain('RAG 知识检索')
  })

  it('使用自然页面滚动，不再套用带内部滚动的管理卡片', async () => {
    await open()
    expect(wrapper.findComponent(ManagementCard).exists()).toBe(false)
    expect(wrapper.findAll('.desktop-view')).toHaveLength(0)
  })

  it('RAG 索引不可用时摘要明确说明且不影响其余配置', async () => {
    aiConfigApi.status.mockResolvedValue({ data: view({ status: { knowledge: { ragReady: false, ragSources: null, wikiReady: true, wikiPages: 17 } } }) })
    await open()
    expect(wrapper.text()).toContain('知识索引未就绪，RAG 模式暂不可切换')
    expect(wrapper.text()).toContain('100 次')
  })
})
