import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, it, expect, vi } from 'vite-plus/test'
import Chat from '../CustomerServiceChat.vue'
import { useUserStore } from '@/stores/userStore'

const api = vi.hoisted(() => ({ customerServiceEvents: vi.fn(), error: vi.fn() }))
vi.mock('@/api/ai', () => ({ ...api, CUSTOMER_SERVICE_TIMEOUT_MS: 1000 }))
vi.mock('element-plus', async importOriginal => ({
  ...await importOriginal(), ElMessage: { error: api.error, success: vi.fn() },
}))

const token = (id, exp) => `header.${btoa(JSON.stringify({ id, exp }))}.signature`
const frame = event => `data: ${JSON.stringify(event)}\n\n`
const response = events => ({ ok: true, headers: { get: () => 'text/event-stream' }, body: new ReadableStream({ start(c) {
  c.enqueue(new TextEncoder().encode(events.map(frame).join('')))
  c.close()
} }) })
let wrapper
beforeEach(() => {
  vi.clearAllMocks()
  localStorage.setItem('chen_stack_jwt', token(7, 1))
  vi.stubGlobal('ElMessage', { error: api.error, success: vi.fn() })
  setActivePinia(createPinia())
  useUserStore().user = { id: 7 }
  wrapper = mount(Chat, { global: { stubs: {
    ElDialog: { template: '<div><slot name="header"/><slot/></div>' },
    ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot/></button>' },
    ElInput: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
    ElAvatar: true, ElTag: true,
    CustomerServiceCards: { props: ['event'], template: '<div class="card-event">{{ event.type }} {{ event.action }}</div>' },
  } } })
  wrapper.vm.open()
})
afterEach(() => { wrapper.unmount(); localStorage.clear(); vi.useRealTimers(); vi.unstubAllGlobals() })
const send = async () => { await wrapper.find('textarea').setValue('如何创建专栏？'); await wrapper.find('.send-button').trigger('click'); await flushPromises() }

it('正常 TEXT、工具导航卡片与 DONE 完成后保留按钮并恢复输入', async () => {
  api.customerServiceEvents.mockResolvedValue(response([{ type: 'TEXT', text: '请到专栏管理创建专栏。' }, { type: 'NAVIGATION_ACTION', action: 'OPEN_COLUMN_MANAGE', text: '专栏管理' }, { type: 'DONE' }]))
  await send()
  expect(wrapper.text()).toContain('请到专栏管理创建专栏。')
  expect(wrapper.find('.card-event').text()).toContain('OPEN_COLUMN_MANAGE')
  expect(wrapper.find('.incomplete-indicator').exists()).toBe(false)
  expect(wrapper.find('textarea').element.disabled).toBe(false)
  expect(api.error).not.toHaveBeenCalled()
})

it('ERROR_STATE 清除已收到的操作卡片，下一次完整回答可以恢复', async () => {
  api.customerServiceEvents.mockResolvedValueOnce(response([{ type: 'TEXT', text: '部分回答' }, { type: 'NAVIGATION_ACTION', action: 'OPEN_COLUMN_MANAGE' }, { type: 'ERROR_STATE', text: '失败' }, { type: 'DONE' }]))
  await send()
  expect(wrapper.find('.incomplete-indicator').exists()).toBe(true)
  expect(wrapper.findAll('.card-event')).toHaveLength(0)
  expect(wrapper.find('textarea').element.disabled).toBe(false)
  api.customerServiceEvents.mockResolvedValueOnce(response([{ type: 'TEXT', text: '恢复后的完整回答' }, { type: 'DONE' }]))
  await send()
  const last = wrapper.findAll('.ai-message').at(-1)
  expect(last.text()).toContain('恢复后的完整回答')
  expect(last.find('.incomplete-indicator').exists()).toBe(false)
  expect(api.customerServiceEvents).toHaveBeenCalledTimes(2)
})

it('后台动作帧失败时清除已出现按钮，不展示后台入口', async () => {
  api.customerServiceEvents.mockResolvedValue(response([{ type: 'TEXT', text: '用户端服务' }, { type: 'NAVIGATION_ACTION', action: 'OPEN_SETTINGS' }, { type: 'NAVIGATION_ACTION', action: 'OPEN_ADMIN', text: '后台' }, { type: 'DONE' }]))
  await send()
  expect(wrapper.findAll('.card-event')).toHaveLength(0)
  expect(wrapper.text()).not.toContain('后台')
  expect(wrapper.find('.incomplete-indicator').exists()).toBe(true)
})

it('缺少 DONE 时保留部分文本、清除卡片并标记未完成', async () => {
  api.customerServiceEvents.mockResolvedValue(response([{ type: 'TEXT', text: '部分回答' }, { type: 'NAVIGATION_ACTION', action: 'OPEN_SETTINGS' }]))
  await send()
  expect(wrapper.text()).toContain('部分回答')
  expect(wrapper.findAll('.card-event')).toHaveLength(0)
  expect(wrapper.find('.incomplete-indicator').exists()).toBe(true)
})

it('超时中断请求并恢复输入，不自动重试', async () => {
  vi.useFakeTimers()
  api.customerServiceEvents.mockImplementation((_message, _chatId, signal) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), { once: true })
  }))
  await send()
  await vi.advanceTimersByTimeAsync(1001)
  await flushPromises()
  expect(api.error).toHaveBeenCalledWith('客服响应超时，请稍后重试')
  expect(api.customerServiceEvents).toHaveBeenCalledTimes(1)
  expect(wrapper.find('textarea').element.disabled).toBe(false)
})

it('同账号刷新继续回答，Token 已换账号而 Store 未更新时清空旧结果', async () => {
  let stream
  api.customerServiceEvents.mockResolvedValue({ ok: true, headers: { get: () => 'text/event-stream' }, body: new ReadableStream({ start(c) { stream = c } }) })
  await send()
  localStorage.setItem('chen_stack_jwt', token(7, 2))
  stream.enqueue(new TextEncoder().encode(frame({ type: 'TEXT', text: '同账号继续' })))
  await flushPromises()
  expect(wrapper.text()).toContain('同账号继续')
  localStorage.setItem('chen_stack_jwt', token(8, 3))
  stream.enqueue(new TextEncoder().encode(frame({ type: 'TEXT', text: '旧账号私有结果' })))
  await flushPromises()
  expect(wrapper.findAll('.message-item')).toHaveLength(0)
  expect(wrapper.text()).not.toContain('旧账号私有结果')
  expect(wrapper.find('textarea').element.disabled).toBe(false)
})
