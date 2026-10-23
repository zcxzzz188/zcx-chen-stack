import { beforeEach, afterEach, it, expect, vi } from 'vite-plus/test'
const auth = vi.hoisted(() => ({ token: 'account-A', set: vi.fn() }))
vi.mock('@/utils/Request', () => ({ default: vi.fn() }))
vi.mock('@/utils/Auth', () => ({ GetJwt: () => auth.token, SetJwt: auth.set }))
import { customerServiceEvents, listEditableArticles, getEditableArticle } from '../ai'

beforeEach(() => { auth.token = 'account-A'; auth.set.mockReset() })
afterEach(() => vi.unstubAllGlobals())

it('SSE 请求使用认证会话，不发送客户端 userId；非法文章 ID 不发查询', async () => {
  const fetch = vi.fn().mockResolvedValue({ ok: true, headers: { get: () => 'text/event-stream' } })
  vi.stubGlobal('fetch', fetch)
  await customerServiceEvents('我的文章', 'conversation', new AbortController().signal)
  expect(fetch.mock.calls[0][0]).toContain('/ai/customer-service/events')
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ message: '我的文章', chatId: 'conversation', mode: 'chat' })
  expect(fetch.mock.calls[0][1].headers.Authorization).toBe('account-A')
  await expect(getEditableArticle('1/../../admin')).rejects.toThrow()
  expect(fetch).toHaveBeenCalledTimes(1)
})

it('私有查询在账号切换后拒绝旧结果，并且不覆盖新 Token', async () => {
  let resolve
  vi.stubGlobal('fetch', vi.fn(() => new Promise(done => { resolve = done })))
  const result = listEditableArticles()
  auth.token = 'account-B'
  resolve({ ok: true, headers: { get: () => 'old-refreshed-token' }, json: async () => ({ code: 200, data: { articles: [] } }) })
  await expect(result).rejects.toThrow('登录状态已变化')
  expect(auth.set).not.toHaveBeenCalled()
})
