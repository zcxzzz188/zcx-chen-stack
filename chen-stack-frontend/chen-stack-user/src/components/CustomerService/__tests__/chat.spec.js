import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, it, expect, vi } from 'vite-plus/test'
import { createMemoryHistory, createRouter } from 'vue-router'
import Chat from '../CustomerServiceChat.vue'
import { useUserStore } from '@/stores/userStore'

const api = vi.hoisted(() => ({
  customerServiceEvents: vi.fn(), customerServiceMessages: vi.fn(), error: vi.fn(), confirm: vi.fn(),
}))
vi.mock('@/api/ai', () => ({ ...api, CUSTOMER_SERVICE_TIMEOUT_MS: 1000 }))
vi.mock('element-plus', async importOriginal => ({
  ...await importOriginal(), ElMessage: { error: api.error, success: vi.fn() },
  ElMessageBox: { confirm: api.confirm },
}))

const token = (id, exp) => `header.${btoa(JSON.stringify({ id, exp }))}.signature`
const frame = event => `data: ${JSON.stringify(event)}\n\n`
const response = events => ({ ok: true, headers: { get: () => 'text/event-stream' }, body: new ReadableStream({ start(c) {
  c.enqueue(new TextEncoder().encode(events.map(frame).join('')))
  c.close()
} }) })
let wrapper, router
const mountChat = () => mount(Chat, { global: { plugins: [router], directives: { loading: () => {} }, stubs: {
    ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue" role="dialog"><slot name="header"/><slot/></div>' },
    ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot/></button>' },
    ElInput: { props: ['modelValue', 'disabled'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
    ElAvatar: true, ElTag: true, ElAlert: { props: ['title'], template: '<div>{{ title }}</div>' },
    CustomerServiceCards: { props: ['event'], template: '<div class="card-event">{{ event.type }} {{ event.action }}</div>' },
} } })
beforeEach(async () => {
  vi.clearAllMocks()
  api.customerServiceMessages.mockReset().mockResolvedValue({ data: { messages: [], before: null } })
  localStorage.setItem('chen_stack_jwt', token(7, 1))
  vi.stubGlobal('ElMessage', { error: api.error, success: vi.fn() })
  setActivePinia(createPinia())
  useUserStore().user = { id: 7 }
  router = createRouter({ history: createMemoryHistory(),
    routes: ['/', '/article', '/editor'].map(path => ({ path, component: { template: '<div />' } })),
  })
  await router.push('/')
  wrapper = mountChat()
  wrapper.vm.open()
})
afterEach(() => { wrapper.unmount(); localStorage.clear(); sessionStorage.clear(); vi.useRealTimers(); vi.unstubAllGlobals() })
const send = async () => { await wrapper.find('textarea').setValue('如何创建专栏？'); await wrapper.find('.send-button').trigger('click'); await flushPromises() }

it('打开有效来源后关闭对话框，返回原页时恢复回答；同页来源不关闭', async () => {
  api.customerServiceEvents.mockResolvedValue(response([
    { type: 'TEXT', text: '可以在编辑器保存草稿。' },
    { type: 'KNOWLEDGE_SOURCES', sources: [{ title: '首页', url: '/' }, { title: '编辑器', url: '/editor' }] },
    { type: 'DONE' },
  ]))
  await send()
  await wrapper.get('textarea').setValue('尚未发送的下一条')
  expect(wrapper.find('[aria-label="历史会话"]').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('查看更早消息')
  await wrapper.get('a[href="/"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[role="dialog"]').text()).toContain('可以在编辑器保存草稿')
  await wrapper.get('a[href="/editor"]').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/editor')
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  router.back()
  await vi.waitFor(() => expect(wrapper.get('[role="dialog"]').text()).toContain('可以在编辑器保存草稿'))
  expect(wrapper.get('textarea').element.value).toBe('尚未发送的下一条')
  await wrapper.get('a[href="/editor"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('返回客服')
  wrapper.vm.open()
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/editor')
  expect(wrapper.text()).toContain('可以在编辑器保存草稿')
  expect(wrapper.get('textarea').element.value).toBe('尚未发送的下一条')
  await wrapper.get('.close-btn').trigger('click')
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  wrapper.vm.open()
  await flushPromises()
  expect(wrapper.get('textarea').element.value).toBe('尚未发送的下一条')
  expect(api.customerServiceMessages).not.toHaveBeenCalled()
})

it('按真实落地页显示客服按钮，并修正历史来源中的评论和专栏入口', async () => {
  api.customerServiceEvents.mockResolvedValue(response([
    { type: 'TEXT', text: '请从创作中心管理你的内容。' },
    { type: 'KNOWLEDGE_SOURCES', sources: [
      { title: '管理发出的和收到的评论', url: '/' },
      { title: '把文章加入专栏或从专栏移除', url: '/editor' },
      { title: '编辑自己的文章', url: '/editor' },
      { title: '创建并提交文章', url: '/editor' },
      { title: '公开文章原题', url: '/user/7/article/1' },
    ] },
    { type: 'DONE' },
  ]))
  await send()

  expect(wrapper.findAll('.knowledge-links a').map(link => [link.text(), link.attributes('href')])).toEqual([
    ['打开评论管理', '/creation/commentmanage'],
    ['打开专栏管理', '/creation/columnmanage'],
    ['打开文章编辑器', '/editor'],
    ['公开文章原题', '/user/7/article/1'],
  ])
})

it('从来源页刷新再返回时，直接恢复该账号保存的旧会话', async () => {
  wrapper.unmount()
  sessionStorage.setItem('chen-stack-customer-service-view', JSON.stringify({
    open: false, path: '/article', returnPath: '/', scroll: 20, chatId: 'active-chat', userId: 7, draft: '保留的草稿',
  }))
  api.customerServiceMessages.mockResolvedValueOnce({ data: { before: 42, messages: [
    { role: 'ASSISTANT', content: '这是此前打开的对话。', events: [] },
  ] } }).mockResolvedValueOnce({ data: { before: null, messages: [
    { role: 'USER', content: '这是第一页之前的问题。' },
  ] } })
  await router.push('/article')
  wrapper = mountChat()
  await flushPromises()
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  wrapper.vm.open()
  await flushPromises()
  expect(wrapper.get('[role="dialog"]').text()).toContain('这是此前打开的对话')
  expect(wrapper.get('textarea').element.value).toBe('保留的草稿')
  expect(api.customerServiceMessages).toHaveBeenCalledWith('active-chat', null)
  expect(api.customerServiceMessages).toHaveBeenCalledWith('active-chat', 42)
  expect(wrapper.findAll('.bubble-text').map(row => row.text())).toEqual([
    '这是第一页之前的问题。', '这是此前打开的对话。',
  ])
  router.back()
  await flushPromises()
  expect(wrapper.get('[role="dialog"]').text()).toContain('这是此前打开的对话')
})

it('恢复失败后保留活动会话，重新打开时重试且不会发送到新会话', async () => {
  wrapper.unmount()
  sessionStorage.setItem('chen-stack-customer-service-view', JSON.stringify({
    open: true, path: '/', chatId: 'active-chat', userId: 7, draft: '保留的草稿',
  }))
  api.customerServiceMessages.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({
    data: { before: null, messages: [{ role: 'ASSISTANT', content: '重新恢复的回答' }] },
  })
  wrapper = mountChat()
  await flushPromises()
  expect(wrapper.text()).toContain('暂时无法恢复当前对话')
  expect(wrapper.get('.send-button').element.disabled).toBe(true)
  expect(api.customerServiceEvents).not.toHaveBeenCalled()
  await wrapper.get('.close-btn').trigger('click')
  wrapper.vm.open()
  await flushPromises()
  expect(wrapper.text()).toContain('重新恢复的回答')
  expect(wrapper.get('textarea').element.value).toBe('保留的草稿')
  expect(api.customerServiceMessages.mock.calls).toEqual([['active-chat', null], ['active-chat', null]])
})

it('清空取消保留当前消息和草稿，确认后刷新不会重新加载旧会话', async () => {
  api.customerServiceEvents.mockResolvedValue(response([{ type: 'TEXT', text: '原来的回答' }, { type: 'DONE' }]))
  await send()
  const originalChatId = api.customerServiceEvents.mock.calls[0][1]
  await wrapper.get('textarea').setValue('保留的草稿')
  window.dispatchEvent(new Event('pagehide'))

  api.confirm.mockRejectedValueOnce('cancel').mockResolvedValueOnce('confirm')
  await wrapper.get('[aria-label="清空聊天记录"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('原来的回答')
  expect(wrapper.get('textarea').element.value).toBe('保留的草稿')
  expect(api.confirm).toHaveBeenCalledWith(expect.any(String), '清空聊天记录？', expect.objectContaining({
    confirmButtonText: '确认清空', cancelButtonText: '取消',
  }))

  await wrapper.get('[aria-label="清空聊天记录"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('.welcome-message').exists()).toBe(true)
  expect(wrapper.get('textarea').element.value).toBe('')
  wrapper.unmount()
  wrapper = mountChat()
  await flushPromises()
  expect(wrapper.get('.welcome-message').exists()).toBe(true)
  expect(api.customerServiceMessages).not.toHaveBeenCalled()

  api.customerServiceEvents.mockResolvedValue(response([{ type: 'TEXT', text: '新的回答' }, { type: 'DONE' }]))
  await send()
  expect(api.customerServiceEvents.mock.calls[1][1]).not.toBe(originalChatId)
  expect(wrapper.text()).toContain('新的回答')
})

it.each(['USAGE_LIMIT', 'ERROR_STATE'])('每日额度用尽通过 %s 展示普通提示，不报错也不自动重试', async type => {
  const reason = '今日可用次数已用完，请明天再来。'
  api.customerServiceEvents.mockResolvedValue(response([
    { type, text: reason, ...(type === 'ERROR_STATE' ? { errorCode: 'DAILY_LIMIT_REACHED' } : {}) },
    { type: 'DONE' },
  ]))
  await send()
  const last = wrapper.findAll('.ai-message').at(-1)
  expect(last.text()).toContain(reason)
  expect(last.find('.incomplete-indicator').exists()).toBe(false)
  expect(last.text()).not.toMatch(/回答失败|请求失败|生成失败|系统异常|网络异常|回答未完成/)
  expect(api.error).not.toHaveBeenCalled()
  expect(api.customerServiceEvents).toHaveBeenCalledTimes(1)
  expect(wrapper.find('textarea').element.disabled).toBe(false)
})

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

it('预算耗尽时显示后端原因，DONE 不掩盖失败且不会自动重试', async () => {
  const reason = '客服AI调用预算已用尽，请联系管理员补充预算后再试。'
  api.customerServiceEvents.mockResolvedValue(response([{ type: 'ERROR_STATE', text: reason }, { type: 'DONE' }]))
  await send()
  const last = wrapper.findAll('.ai-message').at(-1)
  expect(last.text()).toContain(reason)
  expect(last.text()).not.toContain('请稍后重试')
  expect(last.find('.incomplete-indicator').exists()).toBe(true)
  expect(last.findAll('.card-event')).toHaveLength(0)
  expect(wrapper.find('textarea').element.disabled).toBe(false)
  expect(api.customerServiceEvents).toHaveBeenCalledTimes(1)
  expect(api.error).not.toHaveBeenCalled()
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
  expect(wrapper.find('.incomplete-indicator').attributes('data-error-code')).toBe('SSE_INCOMPLETE')
  expect(wrapper.text()).toContain('智能客服响应异常，回答未完成，请稍后重试。')
})

it('超时中断请求并恢复输入，不自动重试', async () => {
  vi.useFakeTimers()
  api.customerServiceEvents.mockImplementation((_message, _chatId, signal) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), { once: true })
  }))
  await send()
  await vi.advanceTimersByTimeAsync(1001)
  await flushPromises()
  expect(api.error).toHaveBeenCalledWith('智能客服响应超时，请稍后重试。')
  expect(wrapper.find('.incomplete-indicator').attributes('data-error-code')).toBe('MODEL_TIMEOUT')
  expect(api.customerServiceEvents).toHaveBeenCalledTimes(1)
  expect(wrapper.find('textarea').element.disabled).toBe(false)
})

it.each([
  ['LOCAL_BUDGET_EXHAUSTED', '智能客服当前模型调用额度已用尽，请联系管理员增加额度后再试。'],
  ['MODEL_RATE_LIMITED', '模型服务请求受到限流，暂时不可用，请稍后重试。'],
  ['MODEL_BALANCE_INSUFFICIENT', '模型服务商余额不足，暂时不可用，请联系管理员处理。'],
  ['MODEL_TIMEOUT', '智能客服响应超时，请稍后重试。'],
])('部分正文后的 %s 原因和错误码在 DONE 后仍可见', async (errorCode, reason) => {
  api.customerServiceEvents.mockResolvedValue(response([
    { type: 'TEXT', text: '已接收的部分回答' },
    { type: 'ERROR_STATE', errorCode, text: reason }, { type: 'DONE' },
  ]))
  await send()
  const last = wrapper.findAll('.ai-message').at(-1)
  expect(last.text()).toContain('已接收的部分回答')
  expect(last.text()).toContain(reason)
  expect(last.find('.incomplete-indicator').attributes('data-error-code')).toBe(errorCode)
  expect(api.customerServiceEvents).toHaveBeenCalledTimes(1)
})

it('知识查询不可用明确警告，仍允许已核实回答正常结束', async () => {
  api.customerServiceEvents.mockResolvedValue(response([
    { type: 'KNOWLEDGE_STATUS', supportStatus: 'UNKNOWN', errorCode: 'KNOWLEDGE_UNAVAILABLE', text: '知识查询暂不可用' },
    { type: 'TEXT', text: '请先确认要查询的主题。' }, { type: 'DONE' },
  ]))
  await send()
  expect(wrapper.find('.knowledge-warning').text()).toBe('知识查询暂不可用')
  expect(wrapper.text()).toContain('请先确认要查询的主题。')
  expect(wrapper.find('.incomplete-indicator').exists()).toBe(false)
  expect(api.error).not.toHaveBeenCalled()
})

it('安全错误帧之后断流保留首个原因，不显示底层异常', async () => {
  const reason = '智能客服当前模型调用额度已用尽，请联系管理员增加额度后再试。'
  let sent = false
  api.customerServiceEvents.mockResolvedValue({ ok: true, headers: { get: () => 'text/event-stream' }, body: new ReadableStream({ pull(c) {
    if (!sent) {
      sent = true
      c.enqueue(new TextEncoder().encode(frame({ type: 'TEXT', text: '部分回答' }) + frame({ type: 'ERROR_STATE', errorCode: 'LOCAL_BUDGET_EXHAUSTED', text: reason })))
    } else c.error(new TypeError('raw-secret-provider-body'))
  } }) })
  await send()
  const last = wrapper.findAll('.ai-message').at(-1)
  expect(last.text()).toContain(reason)
  expect(last.find('.incomplete-indicator').attributes('data-error-code')).toBe('LOCAL_BUDGET_EXHAUSTED')
  expect(wrapper.text()).not.toContain('raw-secret-provider-body')
  expect(api.error).toHaveBeenCalledWith(reason)
})

it('本地网络 TypeError 使用安全网络提示，不猜测余额或额度', async () => {
  api.customerServiceEvents.mockRejectedValue(new TypeError('raw-secret-fetch-error'))
  await send()
  expect(wrapper.text()).toContain('模型服务网络连接异常，请稍后重试。')
  expect(wrapper.find('.incomplete-indicator').attributes('data-error-code')).toBe('MODEL_NETWORK_ERROR')
  expect(wrapper.text()).not.toMatch(/raw-secret|余额|额度/)
  expect(api.customerServiceEvents).toHaveBeenCalledTimes(1)
})

it('解析错误使用协议未完成提示，不显示原始 JSON', async () => {
  api.customerServiceEvents.mockResolvedValue({ ok: true, headers: { get: () => 'text/event-stream' }, body: new ReadableStream({ start(c) {
    c.enqueue(new TextEncoder().encode('data: {raw-secret-json}\n\n'))
    c.close()
  } }) })
  await send()
  expect(wrapper.text()).toContain('智能客服响应异常，回答未完成，请稍后重试。')
  expect(wrapper.find('.incomplete-indicator').attributes('data-error-code')).toBe('SSE_INCOMPLETE')
  expect(wrapper.text()).not.toContain('raw-secret-json')
})

it('已知 HTTP 安全错误保留原因，未知异常只显示服务不可用', async () => {
  api.customerServiceEvents.mockRejectedValueOnce(Object.assign(new Error('raw-secret-body'), { errorCode: 'MODEL_BALANCE_INSUFFICIENT' }))
  await send()
  expect(wrapper.findAll('.ai-message').at(-1).text()).toContain('模型服务商余额不足，暂时不可用，请联系管理员处理。')
  api.customerServiceEvents.mockRejectedValueOnce(new Error('raw-secret-body'))
  await send()
  const last = wrapper.findAll('.ai-message').at(-1)
  expect(last.text()).toContain('智能客服服务暂时不可用，请稍后重试。')
  expect(last.text()).not.toMatch(/raw-secret|余额|额度/)
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
