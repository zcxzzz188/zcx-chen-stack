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

it.each([
  ['LOCAL_BUDGET_EXHAUSTED', '智能客服当前模型调用额度已用尽，请联系管理员增加额度后再试。'],
  ['MODEL_RATE_LIMITED', '模型服务请求受到限流，暂时不可用，请稍后重试。'],
  ['MODEL_BALANCE_INSUFFICIENT', '模型服务商余额不足，暂时不可用，请联系管理员处理。'],
  ['MODEL_TIMEOUT', '智能客服响应超时，请稍后重试。'],
  ['MODEL_NETWORK_ERROR', '模型服务网络连接异常，请稍后重试。'],
  ['MODEL_SERVICE_ERROR', '模型服务暂时不可用，请稍后重试；具体原因请联系管理员检查。'],
  ['KNOWLEDGE_UNAVAILABLE', '知识查询服务暂时不可用，当前无法确认相关知识。'],
  ['SSE_INCOMPLETE', '智能客服响应异常，回答未完成，请稍后重试。'],
  ['SERVICE_UNAVAILABLE', '智能客服服务暂时不可用，请稍后重试。'],
])('非 SSE 响应只按已知 %s 映射安全文案', async (errorCode, message) => {
  const fetch = vi.fn().mockResolvedValue({ ok: false, status: 503,
    headers: { get: name => name.toLowerCase() === 'content-type' ? 'application/json' : null },
    json: async () => ({ errorCode, message: 'raw-secret-exception', body: 'Bearer secret' }),
  })
  vi.stubGlobal('fetch', fetch)
  await expect(customerServiceEvents('问题', 'conversation')).rejects.toMatchObject({ errorCode, message })
  expect(fetch).toHaveBeenCalledTimes(1)
})

it.each([200, 402, 429, 500])('HTTP %s 未知错误不推测余额或额度且不泄露正文', async status => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: status === 200, status,
    headers: { get: name => name.toLowerCase() === 'content-type' ? 'application/json' : null },
    json: async () => ({ errorCode: 'raw-secret-unknown', message: '余额不足 Bearer secret', data: { errorCode: 'LOCAL_BUDGET_EXHAUSTED' } }),
  }))
  await expect(customerServiceEvents('问题', 'conversation')).rejects.toMatchObject({ errorCode: 'SERVICE_UNAVAILABLE', message: '智能客服服务暂时不可用，请稍后重试。' })
})

it('HTTP 200 文本错误响应不作为客服成功正文，取消其流且不读取原文', async () => {
  const cancel = vi.fn()
  const text = vi.fn()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200,
    headers: { get: name => name.toLowerCase() === 'content-type' ? 'text/plain' : null },
    body: { cancel }, text,
  }))
  await expect(customerServiceEvents('问题', 'conversation')).rejects.toMatchObject({ errorCode: 'SERVICE_UNAVAILABLE' })
  expect(cancel).toHaveBeenCalledOnce()
  expect(text).not.toHaveBeenCalled()
})

it.each([
  ['缺省', undefined], ['数组', ['LOCAL_BUDGET_EXHAUSTED']], ['对象', { toString: 'raw-secret' }], ['原型键', '__proto__'],
])('非字符串或未知错误码%s不触发类型转换与额度误报', async (_label, errorCode) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500,
    headers: { get: name => name.toLowerCase() === 'content-type' ? 'application/json' : null },
    json: async () => ({ errorCode }),
  }))
  await expect(customerServiceEvents('问题', 'conversation')).rejects.toMatchObject({ errorCode: 'SERVICE_UNAVAILABLE', message: '智能客服服务暂时不可用，请稍后重试。' })
})

it('非 SSE JSON 解析失败和鉴权失败均只返回安全提示', async () => {
  const fetch = vi.fn().mockResolvedValueOnce({ ok: false, status: 500,
    headers: { get: name => name.toLowerCase() === 'content-type' ? 'application/json' : null },
    json: async () => { throw new Error('raw-secret-json') },
  }).mockResolvedValueOnce({ ok: false, status: 401, headers: { get: () => null } })
  vi.stubGlobal('fetch', fetch)
  await expect(customerServiceEvents('问题', 'conversation')).rejects.toMatchObject({ errorCode: 'SERVICE_UNAVAILABLE', message: '智能客服服务暂时不可用，请稍后重试。' })
  await expect(customerServiceEvents('问题', 'conversation')).rejects.toThrow('请先登录后再与小辰聊天')
  expect(fetch).toHaveBeenCalledTimes(2)
})
