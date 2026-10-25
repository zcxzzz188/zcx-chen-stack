import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { useUserStore } from '@/stores/userStore'
import { useMessageStore } from '@/stores/messageStore'
import { usePrivateMessageRealtime } from '../privateMessageRealtime'

const socket = vi.hoisted(() => {
  const handlers = new Map()
  return {
    handlers,
    connect: vi.fn(), close: vi.fn(), markAsRead: vi.fn(),
    on: vi.fn((type, handler) => handlers.set(type, [...(handlers.get(type) || []), handler])),
    off: vi.fn((type, handler) => handlers.set(type, (handlers.get(type) || []).filter((item) => item !== handler))),
    emit: (type, data) => [...(handlers.get(type) || [])].forEach((handler) => handler(data)),
  }
})
const api = vi.hoisted(() => ({ getConversationList: vi.fn() }))
vi.mock('../WebSocketClient', () => ({ default: socket }))
vi.mock('@/api/conversation', () => api)

const jwt = (id) => `test.${btoa(JSON.stringify({ id }))}.fixture`
const incoming = (id, fields = {}) => ({
  messageId: id, fromUserId: 2, toUserId: 1, content: '文字', messageType: 1,
  createTime: '2026-10-25T13:00:00Z', code: 1, unreadCount: 1, ...fields,
})
const deferred = () => {
  let resolve
  const promise = new Promise((done) => { resolve = done })
  return { promise, resolve }
}

describe('账号作用域内的全局私信同步', () => {
  let user, store, stop
  beforeEach(() => {
    vi.clearAllMocks()
    socket.handlers.clear()
    localStorage.clear()
    setActivePinia(createPinia())
    user = useUserStore()
    store = useMessageStore()
    localStorage.setItem('chen_stack_jwt', jwt(1))
    user.user = { id: 1, nickname: '测试本人' }
    api.getConversationList.mockResolvedValue({ data: [] })
    stop = usePrivateMessageRealtime()
  })
  afterEach(() => { stop(); localStorage.clear() })

  it('T1/T4：会话外文字即时更新入口未读，无需挂载聊天页', () => {
    socket.emit('NEW_MESSAGE', incoming(10))
    expect(store.conversationList[0]).toMatchObject({ targetUserId: 2, unreadCount: 1, lastMessageContent: '文字' })
    expect(store.totalUnreadCount).toBe(1)
    expect(store.currentChatMessages).toEqual([])
    expect(socket.markAsRead).not.toHaveBeenCalled()
  })

  it('T2/T3/T5：当前会话收到完整图片并标读，入口不残留未读', () => {
    store.setCurrentChatUser(2)
    socket.emit('NEW_MESSAGE', incoming(11, { messageType: 2, content: '[图片]', imageUrl: '/fixture/image.png' }))
    expect(store.currentChatMessages).toEqual([expect.objectContaining({ id: 11, messageType: 2, imageUrl: '/fixture/image.png' })])
    expect(socket.markAsRead).toHaveBeenCalledWith(2)
    expect(store.totalUnreadCount).toBe(0)
    expect(store.conversationList[0].unreadCount).toBe(0)
  })

  it('T6：重复实时事件只插入和计数一次', () => {
    store.setCurrentChatUser(2)
    socket.emit('NEW_MESSAGE', incoming(12))
    socket.emit('NEW_MESSAGE', incoming(12, { unreadCount: 8 }))
    expect(store.currentChatMessages).toHaveLength(1)
    expect(socket.markAsRead).toHaveBeenCalledTimes(1)
    store.setCurrentChatUser(null)
    socket.emit('NEW_MESSAGE', incoming(13))
    socket.emit('NEW_MESSAGE', incoming(13, { unreadCount: 2 }))
    expect(store.totalUnreadCount).toBe(1)
  })

  it('T7：每次连接恢复均查询持久化会话和未读状态', async () => {
    api.getConversationList.mockResolvedValueOnce({ data: [{ targetUserId: 2, unreadCount: 3 }] })
    socket.emit('open')
    await flushPromises()
    expect(store.totalUnreadCount).toBe(3)
    api.getConversationList.mockResolvedValueOnce({ data: [{ targetUserId: 2, unreadCount: 4 }] })
    socket.emit('open')
    await flushPromises()
    expect(api.getConversationList).toHaveBeenCalledTimes(2)
    expect(store.totalUnreadCount).toBe(4)
  })

  it('旧会话快照不能覆盖查询期间新到达消息，其他离线会话仍恢复', async () => {
    const request = deferred()
    api.getConversationList.mockReturnValueOnce(request.promise)
    socket.emit('open')
    socket.emit('NEW_MESSAGE', incoming(14, { unreadCount: 2, content: '较新消息' }))
    request.resolve({ data: [{ targetUserId: 2, unreadCount: 1, lastMessageContent: '旧快照' }, { targetUserId: 3, unreadCount: 5 }] })
    await flushPromises()
    expect(store.conversationList.find((item) => item.targetUserId === 2).lastMessageContent).toBe('较新消息')
    expect(store.totalUnreadCount).toBe(7)
  })

  it('T8：切换账号会清空私信，丢弃旧响应和已经排队的旧回调', async () => {
    const request = deferred()
    api.getConversationList.mockReturnValueOnce(request.promise)
    socket.emit('open')
    const oldReceive = socket.handlers.get('NEW_MESSAGE')[0]
    socket.emit('NEW_MESSAGE', incoming(15))
    localStorage.setItem('chen_stack_jwt', jwt(3))
    user.user = { id: 3 }
    oldReceive(incoming(16))
    request.resolve({ data: [{ targetUserId: 2, unreadCount: 99 }] })
    await flushPromises()
    expect(store.conversationList).toEqual([])
    expect(store.currentChatMessages).toEqual([])
    expect(store.totalUnreadCount).toBe(0)
    expect(socket.handlers.get('NEW_MESSAGE')).toHaveLength(1)
  })

  it('T8：投递给其他账号或未审核的事件不能更新当前账号', () => {
    socket.emit('NEW_MESSAGE', incoming(17, { toUserId: 99 }))
    socket.emit('NEW_MESSAGE', incoming(18, { code: 0 }))
    expect(store.totalUnreadCount).toBe(0)
    expect(store.conversationList).toEqual([])
  })

  it('持久化完整回执按ID显示，重复/晚到WAIT不会重复或回退审核结果', () => {
    store.setCurrentChatUser(2)
    const ack = incoming(19, { fromUserId: 1, toUserId: 2, messageType: 2, imageUrl: '/fixture/image.png', code: 0 })
    socket.emit('SEND_SUCCESS', ack)
    socket.emit('SEND_SUCCESS', { ...ack, code: 1 })
    socket.emit('SEND_SUCCESS', { ...ack, code: 1 })
    socket.emit('SEND_SUCCESS', ack)
    expect(store.currentChatMessages).toHaveLength(1)
    expect(store.currentChatMessages[0]).toMatchObject({ id: 19, examineStatus: 1, fromUserNickname: '测试本人' })
    expect(store.totalUnreadCount).toBe(0)
  })

  it('旧快照不能复活已删除会话或恢复已经清除的未读', () => {
    store.setConversationList([{ targetUserId: 2, unreadCount: 4 }, { targetUserId: 3, unreadCount: 2 }])
    const version = store.getConversationVersion()
    store.clearConversationUnread(2)
    store.removeConversation(3)
    store.setConversationList([{ targetUserId: 2, unreadCount: 4 }, { targetUserId: 3, unreadCount: 2 }], version)
    expect(store.conversationList).toEqual([{ targetUserId: 2, unreadCount: 0 }])
    expect(store.totalUnreadCount).toBe(0)
  })
})
