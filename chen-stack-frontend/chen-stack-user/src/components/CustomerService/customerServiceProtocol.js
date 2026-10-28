const pagePaths = new Map([
  ['OPEN_LOGIN', '/login'], ['OPEN_CREATE_ARTICLE', '/editor'], ['OPEN_COLUMN_MANAGE', '/creation/columnmanage'],
  ['OPEN_SETTINGS', '/setting'], ['OPEN_SEARCH', '/search'], ['OPEN_ARTICLES', '/article'],
  ['OPEN_MESSAGES', '/message'], ['OPEN_NOTIFICATIONS', '/notification'],
  ['OPEN_ARTICLE_MANAGE', '/creation/articlemanage'], ['OPEN_COMMENT_MANAGE', '/creation/commentmanage'],
])
const actions = new Set([...pagePaths.keys(), 'OPEN_MY_PROFILE'])
const errorMessages = Object.freeze({
  LOCAL_BUDGET_EXHAUSTED: '智能客服当前模型调用额度已用尽，请联系管理员增加额度后再试。',
  MODEL_RATE_LIMITED: '模型服务请求受到限流，暂时不可用，请稍后重试。',
  MODEL_BALANCE_INSUFFICIENT: '模型服务商余额不足，暂时不可用，请联系管理员处理。',
  MODEL_TIMEOUT: '智能客服响应超时，请稍后重试。',
  MODEL_NETWORK_ERROR: '模型服务网络连接异常，请稍后重试。',
  MODEL_SERVICE_ERROR: '模型服务暂时不可用，请稍后重试；具体原因请联系管理员检查。',
  KNOWLEDGE_UNAVAILABLE: '知识查询服务暂时不可用，当前无法确认相关知识。',
  SSE_INCOMPLETE: '智能客服响应异常，回答未完成，请稍后重试。',
  SERVICE_UNAVAILABLE: '智能客服服务暂时不可用，请稍后重试。',
})

export function customerServiceError(errorCode) {
  const code = typeof errorCode === 'string' && Object.hasOwn(errorMessages, errorCode) ? errorCode : 'SERVICE_UNAVAILABLE'
  return Object.assign(new Error(errorMessages[code]), { errorCode: code })
}

export function navigationTarget(action, authorizedArticle, currentUserId) {
  if (pagePaths.has(action)) return { path: pagePaths.get(action) }
  if (action === 'OPEN_MY_PROFILE' && Number.isSafeInteger(currentUserId) && currentUserId > 0) {
    return { path: `/user/${currentUserId}` }
  }
  if (action === 'OPEN_EDIT_ARTICLE' && authorizedArticle?.canEdit === true &&
      Number.isSafeInteger(authorizedArticle.articleId) && authorizedArticle.articleId > 0) {
    return { path: '/editor', query: { articleId: authorizedArticle.articleId } }
  }
  throw new Error('不支持的客服动作')
}

export function validateCustomerServiceEvent(event) {
  if (!event || typeof event !== 'object') throw new Error('客服事件无效')
  if (!['TEXT', 'DONE', 'NAVIGATION_ACTION', 'ARTICLE_SELECTION', 'EMPTY_RESULT', 'ERROR_STATE', 'KNOWLEDGE_STATUS', 'KNOWLEDGE_SOURCES'].includes(event.type)) {
    throw new Error('未知客服事件')
  }
  if (event.text != null && typeof event.text !== 'string') throw new Error('客服文本无效')
  if (event.errorCode != null && (typeof event.errorCode !== 'string' || !Object.hasOwn(errorMessages, event.errorCode))) throw new Error('客服错误码无效')
  if (event.type === 'KNOWLEDGE_STATUS' && !['SUPPORTED', 'NOT_SUPPORTED', 'UNKNOWN'].includes(event.supportStatus)) throw new Error('知识状态无效')
  if (event.type === 'NAVIGATION_ACTION' && !actions.has(event.action)) throw new Error('客服动作无效')
  if (event.type === 'KNOWLEDGE_SOURCES') {
    if (!Array.isArray(event.sources) || event.sources.length < 1 || event.sources.length > 96) throw new Error('知识来源无效')
    for (const source of event.sources) {
      if (!source || typeof source.title !== 'string' || !source.title.trim() || source.title.length > 300 ||
          (source.version != null && (typeof source.version !== 'string' || source.version.length > 200)) ||
          (source.url != null && (typeof source.url !== 'string' || !/^\/user\/[1-9]\d*\/article\/[1-9]\d*$/.test(source.url)))) throw new Error('知识来源无效')
    }
  }
  if (event.type === 'ARTICLE_SELECTION' || event.type === 'EMPTY_RESULT') {
    if (!Array.isArray(event.articles) || event.articles.length > 20 ||
        !Number.isInteger(event.page) || event.page < 1 || event.page > 1000 ||
        !Number.isInteger(event.pageSize) || event.pageSize < 1 || event.pageSize > 20 ||
        typeof event.hasMore !== 'boolean') throw new Error('文章分页无效')
    for (const article of event.articles) {
      if (!Number.isSafeInteger(article.articleId) || article.articleId <= 0 ||
          typeof article.title !== 'string' || article.canEdit !== true ||
          ![0, 1].includes(article.editStatus) || ![0, 1, 2].includes(article.examineStatus)) {
        throw new Error('文章卡片无效')
      }
    }
  }
  return event
}

// UTF-8 解码由调用方流式完成；SSE 帧可以跨任意网络分块。
export function createCustomerServiceDecoder(onEvent) {
  let buffer = ''
  let finished = false
  return {
    push(text) {
      buffer += text
      let match
      while ((match = /\r?\n\r?\n/.exec(buffer))) {
        const frame = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match[0].length)
        if (frame.length > 65536) throw customerServiceError('SSE_INCOMPLETE')
        const data = frame.split(/\r?\n/).filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart()).join('\n')
        if (!data) continue
        if (finished) throw customerServiceError('SSE_INCOMPLETE')
        let event
        try { event = validateCustomerServiceEvent(JSON.parse(data)) }
        catch { throw customerServiceError('SSE_INCOMPLETE') }
        if (event.type === 'DONE') finished = true
        onEvent(event)
      }
      if (buffer.length > 65536) throw customerServiceError('SSE_INCOMPLETE')
    },
    finish() {
      if (buffer.trim() || !finished) throw customerServiceError('SSE_INCOMPLETE')
    },
  }
}
