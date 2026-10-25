import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import client from '../WebSocketClient'

class FixtureSocket {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  static instances = []
  constructor(url) { this.url = url; this.readyState = 0; this.send = vi.fn(); FixtureSocket.instances.push(this) }
  close() { this.readyState = 3 }
  open() { this.readyState = 1; this.onopen?.() }
  message(data) { this.onmessage?.({ data: JSON.stringify(data) }) }
}

describe('WebSocket connection ownership', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('WebSocket', FixtureSocket)
    FixtureSocket.instances = []
    client.messageHandlers.clear()
    localStorage.setItem('chen_stack_jwt', 'fixture-account-A')
  })
  afterEach(() => { client.close(); localStorage.clear(); vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals() })

  it('重复connect不会替换正在连接或已连接的同一账号socket', () => {
    client.connect(); client.connect()
    expect(FixtureSocket.instances).toHaveLength(1)
    FixtureSocket.instances[0].open()
    client.connect()
    expect(FixtureSocket.instances).toHaveLength(1)
  })

  it('断线自动重连，旧socket延迟close不会关闭新连接', () => {
    client.connect()
    const old = FixtureSocket.instances[0]
    const oldClosed = old.onclose
    old.open(); old.readyState = 3; oldClosed()
    vi.advanceTimersByTime(5000)
    const replacement = FixtureSocket.instances[1]
    replacement.open(); oldClosed()
    expect(client.isConnected()).toBe(true)
    expect(FixtureSocket.instances).toHaveLength(2)
  })

  it('旧账号socket的已排队事件不会进入新账号处理器', () => {
    const handler = vi.fn()
    client.on('NEW_MESSAGE', handler)
    client.connect()
    const queued = FixtureSocket.instances[0].onmessage
    localStorage.setItem('chen_stack_jwt', 'fixture-account-B')
    client.connect()
    queued({ data: JSON.stringify({ type: 'NEW_MESSAGE', content: '旧账号' }) })
    expect(handler).not.toHaveBeenCalled()
  })

  it('单个订阅者抛异常不会吞掉其他页面的图片事件', () => {
    client.on('NEW_MESSAGE', () => { throw new Error('fixture handler failure') })
    const receiver = vi.fn()
    client.on('NEW_MESSAGE', receiver)
    client.connect()
    FixtureSocket.instances[0].message({ type: 'NEW_MESSAGE', messageType: 2, imageUrl: '/fixture.png' })
    expect(receiver).toHaveBeenCalledWith(expect.objectContaining({ messageType: 2 }))
  })

  it('离线、跨账号或写入失败不会报告发送成功', () => {
    expect(client.sendTextMessage(2, 'hello')).toBe(false)
    client.connect()
    const socket = FixtureSocket.instances[0]
    socket.open()
    socket.send.mockImplementationOnce(() => { throw new Error('closed') })
    expect(client.sendImageMessage(2, '/fixture.png')).toBe(false)
    localStorage.setItem('chen_stack_jwt', 'fixture-account-B')
    expect(client.sendTextMessage(2, 'private')).toBe(false)
  })

  it('手动关闭会停止心跳和重连并移除旧socket回调', () => {
    client.connect()
    const socket = FixtureSocket.instances[0]
    socket.open(); client.close()
    vi.advanceTimersByTime(60000)
    expect(socket.send).not.toHaveBeenCalled()
    expect(socket.onmessage).toBeNull()
    expect(FixtureSocket.instances).toHaveLength(1)
  })
})
