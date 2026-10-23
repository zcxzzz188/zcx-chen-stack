import request from '@/utils/Request'
import { GetJwt, SetJwt } from '@/utils/Auth'
import { sameJwtAccount } from '@/utils/JwtSession'

const configuredCustomerServiceTimeout = Number(import.meta.env.VITE_AI_CHAT_TIMEOUT_MS)
export const CUSTOMER_SERVICE_TIMEOUT_MS =
  Number.isFinite(configuredCustomerServiceTimeout) && configuredCustomerServiceTimeout > 0
    ? configuredCustomerServiceTimeout
    : 120_000

function localCustomerServiceMock(message, signal) {
  if (!import.meta.env.DEV || import.meta.env.VITE_AI_CHAT_MOCK !== '1') return null
  const match = /^#mock\s+(slow|timeout|network|provider)$/i.exec(message.trim())
  if (!match) {
    return {
      ok: false,
      status: 400,
      headers: { get: (name) => name.toLowerCase() === 'content-type' ? 'text/plain; charset=UTF-8' : null },
      body: null,
    }
  }

  const mode = match[1].toLowerCase()
  let releaseAbortListener = () => {}
  const body = new ReadableStream({
    start(controller) {
      const closeOnAbort = () => {
        releaseAbortListener()
        try { controller.close() } catch {}
      }
      releaseAbortListener = () => signal?.removeEventListener('abort', closeOnAbort)
      if (signal?.aborted) closeOnAbort()
      else signal?.addEventListener('abort', closeOnAbort, { once: true })
      if (mode === 'timeout') return

      void (async () => {
        const bytes = new TextEncoder().encode('这是中文 UTF-8 跨块模拟回复。')
        try {
          for (let offset = 0; offset < bytes.length; offset += 4) {
            await new Promise((resolve) => setTimeout(resolve, 500))
            if (signal?.aborted) return
            controller.enqueue(bytes.slice(offset, offset + 4))
            if (mode !== 'slow' && offset >= 8) {
              controller.error(new Error(mode === 'network' ? '模拟网络断开' : '模拟 Provider 异常'))
              return
            }
          }
          controller.close()
        } catch (error) {
          try { controller.error(error) } catch {}
        } finally {
          releaseAbortListener()
        }
      })()
    },
    cancel() {
      releaseAbortListener()
    },
  })

  return {
    ok: true,
    status: 200,
    headers: { get: (name) => name.toLowerCase() === 'content-type' ? 'text/plain; charset=UTF-8' : null },
    body,
  }
}

// AI 提取文章摘要
export function extractSummary(content) {
  return request({
    url: '/ai/extractSummary',
    method: 'post',
    data: {
      content: content,
    },
  })
}

// 获取AI调用配额（今日剩余次数）
export function getAiQuota() {
  return request({
    url: '/ai/quota',
    method: 'get',
  })
}

// 智能客服聊天（流式返回）
export function customerServiceChat(message, chatId, signal, mode = 'chat', structured = false) {
  const mockResponse = localCustomerServiceMock(message, signal)
  if (mockResponse) return Promise.resolve(mockResponse)

  // 使用GetJwt()获取token，与Request.js中的认证方式保持一致
  const token = GetJwt() || ''
  const backendServer = import.meta.env.VITE_BACKEND_SERVER || 'http://localhost:5000'
  return fetch(`${backendServer}/ai/customer-service${structured ? '/events' : ''}`, {
    method: 'POST',
    headers: {
      Authorization: token,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ message, chatId, mode }),
    signal,
  }).then(async (response) => {
    const refreshedToken = response.headers.get('X-Refresh-Token')
    if (refreshedToken && GetJwt() === token) {
      SetJwt(refreshedToken)
    }
    if (!response.ok || response.headers?.get?.('content-type')?.toLowerCase().includes('json')) {
      try { await response.body?.cancel() } catch {}
      throw new Error(response.status === 401 ? '请先登录后再与小辰聊天' : '客服暂时无法响应，请稍后重试')
    }
    return response
  })
}

export function customerServiceEvents(message, chatId, signal) {
  return customerServiceChat(message, chatId, signal, 'chat', true)
}

export function listEditableArticles(page = 1, pageSize = 5) {
  const params = new URLSearchParams({ page, pageSize })
  return customerServiceGet(`/articles?${params}`)
}

export function getEditableArticle(articleId) {
  if (!/^\d{1,10}$/.test(String(articleId)) || Number(articleId) <= 0) return Promise.reject(new Error('文章参数无效'))
  return customerServiceGet(`/articles/${articleId}`)
}

export function getEditableArticleDetail(articleId) {
  if (!/^[1-9]\d{0,9}$/.test(String(articleId))) return Promise.reject(new Error('文章参数无效'))
  return customerServiceGet(`/articles/${articleId}/detail`)
}

async function customerServiceGet(path) {
  const token = GetJwt()
  if (!token) throw new Error('请先登录')
  const backendServer = import.meta.env.VITE_BACKEND_SERVER || 'http://localhost:5000'
  const response = await fetch(`${backendServer}/ai/customer-service${path}`, { headers: { Authorization: token } })
  const data = await response.json()
  // 账号切换后的旧响应不得写回 Token 或作为新账号的文章依据。
  if (!sameJwtAccount(token, GetJwt())) throw new Error('登录状态已变化，请重新查询')
  const refreshedToken = response.headers.get('X-Refresh-Token')
  if (refreshedToken && GetJwt() === token) SetJwt(refreshedToken)
  if (!response.ok || data.code !== 200) throw new Error('文章不可编辑或查询失败，请重新登录或重试')
  return data
}

// 生成文章标题建议
export function generateTitles(content) {
  return request({
    url: '/ai/generate-titles',
    method: 'post',
    data: {
      content: content,
    },
  })
}

// 推荐文章标签
export function recommendTags(title, content) {
  return request({
    url: '/ai/recommend-tags',
    method: 'post',
    data: {
      title: title,
      content: content,
    },
  })
}

// 生成评论回复建议
export function generateCommentReplySuggestions(articleTitle, commentContent) {
  return request({
    url: '/ai/comment-reply-suggestions',
    method: 'post',
    data: {
      articleTitle: articleTitle,
      commentContent: commentContent,
    },
  })
}
