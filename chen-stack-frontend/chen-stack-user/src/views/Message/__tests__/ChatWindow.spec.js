import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, reactive } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vite-plus/test'
import ChatWindow from '../ChatWindow.vue'
import { useMessageStore } from '@/stores/messageStore'
import { useUserStore } from '@/stores/userStore'
import { usePrivateMessageRealtime } from '@/utils/privateMessageRealtime'

const fake = vi.hoisted(() => {
  const listeners = new Map()
  return {
    history: vi.fn(), profile: vi.fn(), conversations: vi.fn(), photo: vi.fn(),
    error: vi.fn(), success: vi.fn(), push: vi.fn(), route: null, listeners,
    socket: {
      on: vi.fn((type, listener) => {
        if (!listeners.has(type)) listeners.set(type, new Set())
        listeners.get(type).add(listener)
      }),
      off: vi.fn((type, listener) => listeners.get(type)?.delete(listener)),
      connect: vi.fn(), close: vi.fn(), markAsRead: vi.fn(), sendTyping: vi.fn(),
      sendTextMessage: vi.fn(), sendImageMessage: vi.fn(), revokeMessage: vi.fn(),
    },
  }
})

vi.mock('@/api/privateMessage', () => ({ getChatHistory: fake.history }))
vi.mock('@/api/user', () => ({ getUserInfoById: fake.profile }))
vi.mock('@/api/conversation', () => ({ getConversationList: fake.conversations }))
vi.mock('@/api/photo', () => ({ uploadMessagePhoto: fake.photo }))
vi.mock('@/utils/WebSocketClient', () => ({ default: fake.socket }))
vi.mock('vue-router', async importOriginal => ({
  ...await importOriginal(),
  useRoute: () => fake.route,
  useRouter: () => ({ push: fake.push }),
}))
vi.mock('element-plus', async importOriginal => ({
  ...await importOriginal(), ElMessage: { error: fake.error, success: fake.success },
}))

const token = owner => `header.${btoa(JSON.stringify({ id: owner }))}.signature`
const history = messages => ({ data: { data: messages.map(message => ({ ...message })) } })
const message = (id, fields = {}) => ({
  id, messageId: id, fromUserId: 8, toUserId: 7, fromUserNickname: '作者甲',
  content: `实时消息${id}`, messageType: 1, examineStatus: 1, code: 1, isRead: 0,
  createTime: new Date(Date.UTC(2026, 9, 25, 12) + id * 1000).toISOString(),
  ...fields,
})
const deferred = () => {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
const emit = (type, data) => {
  for (const listener of [...(fake.listeners.get(type) || [])]) listener(data)
}

const Harness = defineComponent({
  setup() {
    usePrivateMessageRealtime()
    return () => h(ChatWindow)
  },
})

let wrapper
let pinia
beforeEach(() => {
  vi.clearAllMocks()
  fake.listeners.clear()
  fake.route = reactive({ params: { userId: '8' } })
  fake.history.mockReset().mockImplementation(async () => history([]))
  fake.profile.mockReset().mockImplementation(async id => ({ data: { id, nickname: `用户${id}` } }))
  fake.conversations.mockReset().mockImplementation(async () => ({
    data: [{ targetUserId: 8, targetUserNickname: '作者甲', unreadCount: 0 }],
  }))
  fake.photo.mockReset()
  fake.socket.sendTextMessage.mockReturnValue(true)
  fake.socket.sendImageMessage.mockReturnValue(true)
  localStorage.setItem('chen_stack_jwt', token(7))
  vi.stubGlobal('ElMessage', { error: fake.error, success: fake.success })
  pinia = createPinia()
  setActivePinia(pinia)
  useUserStore().user = { id: 7 }
  wrapper = null
})

afterEach(() => {
  wrapper?.unmount()
  localStorage.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

const openChat = async () => {
  wrapper = mount(Harness, { global: {
    plugins: [pinia],
    directives: { loading: {} },
    stubs: {
      ElButton: { props: ['disabled', 'loading'], template: '<button :disabled="disabled || loading"><slot/></button>' },
      ElInput: {
        props: ['modelValue'], emits: ['update:modelValue', 'input'],
        template: '<div><textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value); $emit(\'input\', $event)"/></div>',
      },
      ElImage: { props: ['src', 'previewSrcList'], template: '<img :src="src" :data-preview="previewSrcList[0]" />' },
      ElEmpty: { props: ['description'], template: '<div class="empty-state">{{ description }}</div>' },
      ElAvatar: true,
      ElIcon: { template: '<span><slot/></span>' },
    },
  } })
  await flushPromises()
}
const renderedTexts = () => wrapper.findAll('.text-bubble').map(bubble => bubble.text())
const currentIds = () => useMessageStore().currentChatMessages.map(item => item.id)

it('重连漏收超过50条时继续分页到已知消息，而不是只恢复第一页', async () => {
  fake.history.mockResolvedValueOnce(history([message(100)]))
  await openChat()
  const persisted = Array.from({ length: 56 }, (_, index) => message(155 - index))
  fake.history.mockImplementation(async (peer, page, size) => ({
    data: { data: persisted.slice((page - 1) * size, page * size), total: persisted.length },
  }))
  emit('open')
  await flushPromises()
  expect(fake.history).toHaveBeenCalledWith(8, 2, 50)
  expect(currentIds()).toEqual(Array.from({ length: 56 }, (_, index) => 100 + index))
})

it('实时文字和图片立即渲染，晚到的初始历史不覆盖已收到的消息', async () => {
  const pending = deferred()
  fake.history.mockReturnValueOnce(pending.promise)
  await openChat()
  emit('NEW_MESSAGE', message(201))
  emit('NEW_MESSAGE', message(202, { messageType: 2, content: '[图片]', imageUrl: 'https://example.invalid/private-image.png' }))
  await flushPromises()

  expect(renderedTexts()).toContain('实时消息201')
  expect(wrapper.find('.image-bubble').attributes('src')).toBe('https://example.invalid/private-image.png')
  expect(wrapper.find('.image-bubble').attributes('data-preview')).toBe('https://example.invalid/private-image.png')
  pending.resolve(history([message(100, { content: '较早的历史' })]))
  await flushPromises()

  expect(currentIds()).toEqual([100, 201, 202])
  expect(renderedTexts()).toEqual(['较早的历史', '实时消息201'])
  expect(wrapper.findAll('.image-bubble')).toHaveLength(1)
  expect(fake.socket.markAsRead).toHaveBeenCalledWith(8)
})

it('重连补查恢复漏收消息，重复实时帧和多次刷新不会重复显示', async () => {
  const pending = deferred()
  const snapshot = [message(203), message(202), message(201), message(100)]
  fake.history.mockImplementation(async () => history(snapshot))
    .mockResolvedValueOnce(history([message(100)]))
  await openChat()
  emit('NEW_MESSAGE', message(201))
  fake.history.mockReturnValueOnce(pending.promise)
  emit('open')
  emit('NEW_MESSAGE', message(201))
  emit('NEW_MESSAGE', message(202))
  pending.resolve(history(snapshot))
  await flushPromises()
  emit('NEW_MESSAGE', message(203))
  emit('open')
  await flushPromises()

  expect(fake.history).toHaveBeenCalledTimes(3)
  expect(fake.history.mock.calls.every(([peer, page, size]) => peer === 8 && page === 1 && size === 50)).toBe(true)
  expect(currentIds()).toEqual([100, 201, 202, 203])
  expect(wrapper.findAll('.message-item')).toHaveLength(4)
  expect(useMessageStore().totalUnreadCount).toBe(0)
})

it('历史查询期间同 ID 的审核成功回执不会被旧的审核中记录降级', async () => {
  const pending = deferred()
  const waiting = message(400, { fromUserId: 7, toUserId: 8, content: '等待审核的本人消息', code: 0, examineStatus: 0 })
  fake.history.mockResolvedValueOnce(history([waiting]))
  await openChat()
  expect(wrapper.find('.audit-status--waiting').exists()).toBe(true)
  fake.history.mockReturnValueOnce(pending.promise)
  emit('open')
  emit('SEND_SUCCESS', { ...waiting, code: 1, examineStatus: 1 })
  await flushPromises()
  expect(wrapper.find('.audit-status--waiting').exists()).toBe(false)

  pending.resolve(history([waiting]))
  await flushPromises()
  expect(currentIds()).toEqual([400])
  expect(useMessageStore().currentChatMessages[0].examineStatus).toBe(1)
  expect(wrapper.find('.audit-status--waiting').exists()).toBe(false)
})

it('历史查询期间已经撤回的消息不会被旧历史响应重新显示', async () => {
  const pending = deferred()
  const rows = [message(101), message(100)]
  fake.history.mockResolvedValueOnce(history(rows))
  await openChat()
  fake.history.mockReturnValueOnce(pending.promise)
  emit('open')
  emit('MESSAGE_REVOKED', { messageId: 100, fromUserId: 8, toUserId: 7, content: '撤回了一条消息' })
  await flushPromises()
  expect(renderedTexts()).not.toContain('实时消息100')

  pending.resolve(history(rows))
  await flushPromises()
  expect(currentIds()).toEqual([101])
  expect(renderedTexts()).toEqual(['实时消息101'])
})

it('切换聊天对象后，旧历史和旧用户信息不能污染新窗口', async () => {
  const oldHistory = deferred()
  const oldProfile = deferred()
  fake.history.mockImplementation(peer => peer === 8 ? oldHistory.promise : Promise.resolve(history([
    message(290, { fromUserId: 9, content: '新对象的私信' }),
  ])))
  fake.profile.mockImplementation(peer => peer === 8 ? oldProfile.promise : Promise.resolve({ data: { id: peer, nickname: '新聊天对象' } }))
  await openChat()
  fake.route.params.userId = '9'
  await flushPromises()
  oldHistory.resolve(history([message(280, { content: '旧对象的私人正文' })]))
  oldProfile.resolve({ data: { id: 8, nickname: '旧聊天对象' } })
  await flushPromises()

  expect(useMessageStore().currentChatUserId).toBe(9)
  expect(currentIds()).toEqual([290])
  expect(wrapper.text()).toContain('新聊天对象')
  expect(wrapper.text()).not.toContain('旧聊天对象')
  expect(wrapper.text()).not.toContain('旧对象的私人正文')
  expect(fake.socket.markAsRead).not.toHaveBeenCalledWith(8)
})

it('切换账号丢弃旧历史及已移除的旧连接回调，不串入新账号的私信', async () => {
  const oldHistory = deferred()
  fake.history.mockImplementation(() => useUserStore().user.id === 7 ? oldHistory.promise : Promise.resolve(history([
    message(900, { toUserId: 9, content: '新账号的私信' }),
  ])))
  await openChat()
  const staleReceive = [...fake.listeners.get('NEW_MESSAGE')][0]
  localStorage.setItem('chen_stack_jwt', token(9))
  useUserStore().user = { id: 9 }
  await flushPromises()
  staleReceive(message(700, { content: '旧连接的私人正文' }))
  emit('NEW_MESSAGE', message(701, { content: '发给旧账号的正文' }))
  oldHistory.resolve(history([message(702, { content: '旧账号的历史正文' })]))
  emit('NEW_MESSAGE', message(901, { toUserId: 9, content: '新账号的实时消息' }))
  await flushPromises()

  expect(currentIds()).toEqual([900, 901])
  expect(renderedTexts()).toEqual(['新账号的私信', '新账号的实时消息'])
  expect(wrapper.text()).not.toContain('旧连接的私人正文')
  expect(wrapper.text()).not.toContain('发给旧账号的正文')
  expect(wrapper.text()).not.toContain('旧账号的历史正文')
})

it('离线发送保留输入并提示连接失败，不清空草稿或制造成功气泡', async () => {
  fake.socket.sendTextMessage.mockReturnValue(false)
  await openChat()
  await wrapper.find('textarea').setValue('离线时保留的草稿')
  await wrapper.find('.send-btn').trigger('click')
  await flushPromises()

  expect(fake.socket.sendTextMessage).toHaveBeenCalledWith(8, '离线时保留的草稿')
  expect(wrapper.find('textarea').element.value).toBe('离线时保留的草稿')
  expect(fake.error).toHaveBeenCalledWith('私信连接已断开，请等待重连后发送')
  expect(fake.success).not.toHaveBeenCalled()
  expect(wrapper.findAll('.message-item')).toHaveLength(0)
})

it('在线发送也等待服务端回执才显示本人消息，后续审核回执只更新同一气泡', async () => {
  await openChat()
  await wrapper.find('textarea').setValue('由服务端确认的消息')
  await wrapper.find('.send-btn').trigger('click')
  expect(wrapper.find('textarea').element.value).toBe('')
  expect(wrapper.findAll('.message-item')).toHaveLength(0)
  const sent = message(500, { fromUserId: 7, toUserId: 8, content: '由服务端确认的消息', code: 0, examineStatus: 0 })
  emit('SEND_SUCCESS', sent)
  await flushPromises()
  expect(wrapper.find('.message-bubble.is-mine .text-bubble').text()).toBe('由服务端确认的消息')
  expect(wrapper.find('.audit-status--waiting').exists()).toBe(true)
  emit('SEND_SUCCESS', { ...sent, code: 1, examineStatus: 1 })
  await flushPromises()

  expect(currentIds()).toEqual([500])
  expect(wrapper.findAll('.message-item')).toHaveLength(1)
  expect(wrapper.find('.audit-status--waiting').exists()).toBe(false)
})

it.each(['peer', 'account'])('图片上传期间切换 %s 后不向旧聊天对象发送图片', async change => {
  const upload = deferred()
  let picker
  vi.spyOn(HTMLInputElement.prototype, 'click').mockImplementation(function () { picker = this })
  fake.photo.mockReturnValueOnce(upload.promise)
  await openChat()
  await wrapper.findAll('button').find(button => button.text() === '图片').trigger('click')
  const file = new File(['synthetic-image'], 'test.png', { type: 'image/png' })
  Object.defineProperty(picker, 'files', { configurable: true, value: [file] })
  picker.dispatchEvent(new Event('change'))
  await flushPromises()
  expect(fake.photo).toHaveBeenCalledWith(file)
  if (change === 'peer') fake.route.params.userId = '9'
  else {
    localStorage.setItem('chen_stack_jwt', token(9))
    useUserStore().user = { id: 9 }
  }
  await flushPromises()
  upload.resolve({ data: 'https://example.invalid/stale-upload.png' })
  await flushPromises()

  expect(fake.socket.sendImageMessage).not.toHaveBeenCalled()
  expect(wrapper.findAll('.image-bubble')).toHaveLength(0)
})
