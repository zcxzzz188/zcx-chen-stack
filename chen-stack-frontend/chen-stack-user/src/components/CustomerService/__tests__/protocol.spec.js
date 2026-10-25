import { describe, it, expect } from 'vite-plus/test'
import { createCustomerServiceDecoder, navigationTarget, validateCustomerServiceEvent } from '../customerServiceProtocol'

const frame = event => `data: ${JSON.stringify(event)}\r\n\r\n`

describe('客服协议与动作边界', () => {
  it('逐字节 UTF-8 分块后仍解析完整文本和 DONE', () => {
    const events = []
    const protocol = createCustomerServiceDecoder(event => events.push(event))
    const decoder = new TextDecoder()
    const bytes = new TextEncoder().encode(frame({ type: 'TEXT', text: '你好，小辰' }) + frame({ type: 'DONE' }))
    for (const byte of bytes) protocol.push(decoder.decode(new Uint8Array([byte]), { stream: true }))
    protocol.push(decoder.decode())
    protocol.finish()
    expect(events).toEqual([{ type: 'TEXT', text: '你好，小辰' }, { type: 'DONE' }])
  })

  it('拒绝缺失 DONE、截断、超大帧和 DONE 后的额外事件', () => {
    const protocol = createCustomerServiceDecoder(() => {})
    protocol.push(frame({ type: 'TEXT', text: '部分内容' }))
    expect(() => protocol.finish()).toThrow()
    const truncated = createCustomerServiceDecoder(() => {})
    truncated.push('data: {"type":"TEXT"')
    expect(() => truncated.finish()).toThrow()
    expect(() => createCustomerServiceDecoder(() => {}).push('a'.repeat(65537))).toThrow()
    expect(() => createCustomerServiceDecoder(() => {}).push(frame({ type: 'DONE' }) + frame({ type: 'TEXT', text: '额外' }))).toThrow()
  })

  it('所有用户端导航均为固定路由，后台、URL 和伪造动作均拒绝', () => {
    const targets = {
      OPEN_LOGIN: '/login', OPEN_CREATE_ARTICLE: '/editor', OPEN_COLUMN_MANAGE: '/creation/columnmanage',
      OPEN_SETTINGS: '/setting', OPEN_SEARCH: '/search', OPEN_ARTICLES: '/article',
      OPEN_MESSAGES: '/message', OPEN_NOTIFICATIONS: '/notification',
      OPEN_ARTICLE_MANAGE: '/creation/articlemanage', OPEN_COMMENT_MANAGE: '/creation/commentmanage',
    }
    for (const [action, path] of Object.entries(targets)) {
      expect(navigationTarget(action)).toEqual({ path })
      expect(validateCustomerServiceEvent({ type: 'NAVIGATION_ACTION', action }).action).toBe(action)
    }
    for (const action of ['OPEN_ADMIN', '/admin', 'javascript:alert(1)', 'https://invalid.example', '__proto__', 'toString']) {
      expect(() => navigationTarget(action)).toThrow()
      expect(() => validateCustomerServiceEvent({ type: 'NAVIGATION_ACTION', action })).toThrow()
    }
  })

  it('编辑与个人主页使用有效授权参数，知识未知不等于不支持', () => {
    expect(navigationTarget('OPEN_EDIT_ARTICLE', { articleId: 42, canEdit: true })).toEqual({ path: '/editor', query: { articleId: 42 } })
    expect(() => navigationTarget('OPEN_EDIT_ARTICLE', { articleId: 42, canEdit: false })).toThrow()
    expect(navigationTarget('OPEN_MY_PROFILE', undefined, 7)).toEqual({ path: '/user/7' })
    expect(() => navigationTarget('OPEN_MY_PROFILE', undefined, '7')).toThrow()
    expect(() => validateCustomerServiceEvent({ type: 'ARTICLE_SELECTION', articles: [{ articleId: '42' }] })).toThrow()
    for (const supportStatus of ['SUPPORTED', 'NOT_SUPPORTED', 'UNKNOWN']) {
      expect(validateCustomerServiceEvent({ type: 'KNOWLEDGE_STATUS', supportStatus }).supportStatus).toBe(supportStatus)
    }
  })

  it('保留可选安全错误码并拒绝非字符串与未知错误码', () => {
    const event = { type: 'ERROR_STATE', errorCode: 'MODEL_TIMEOUT', text: '智能客服响应超时，请稍后重试。' }
    expect(validateCustomerServiceEvent(event)).toEqual(event)
    expect(validateCustomerServiceEvent({ type: 'ERROR_STATE', text: '旧协议安全提示' }).text).toBe('旧协议安全提示')
    for (const errorCode of [7, {}, 'UNKNOWN_SECRET']) {
      expect(() => validateCustomerServiceEvent({ type: 'ERROR_STATE', errorCode })).toThrow()
    }
  })

  it('缺 DONE 和 JSON 解析错误携带安全协议错误码', () => {
    expect(() => createCustomerServiceDecoder(() => {}).finish()).toThrow(expect.objectContaining({ errorCode: 'SSE_INCOMPLETE' }))
    expect(() => createCustomerServiceDecoder(() => {}).push('data: {raw-secret}\n\n')).toThrow(expect.objectContaining({ errorCode: 'SSE_INCOMPLETE' }))
  })
})
