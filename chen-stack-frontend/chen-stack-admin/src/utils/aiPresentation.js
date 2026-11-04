const dateTime = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})

export function formatKnowledgeTime(value) {
  if (value == null || value === '') return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : dateTime.format(date).replaceAll('/', '-')
}

/**
 * 与后端 AiRuntimeConfigurationService 的三档产品模式保持一致，仅用于展示。
 * chat 只作为历史配置值保留标签，不再作为可选模式出现。
 */
export const knowledgeModeOptions = [
  { mode: 'rag', label: 'RAG 知识检索', description: '依据管理员审校过的原始资料回答，适合规则、限制和故障类问题。' },
  { mode: 'wiki', label: 'Wiki 知识问答', description: '仅依据当前已发布且来源有效的整理知识回答，适合完整操作步骤。' },
  { mode: 'hybrid', label: '智能混合', description: '按问题自动选择 RAG 或 Wiki，简单交流不检索知识。' },
]

const legacyModeLabels = { chat: '普通对话（历史配置）' }
const shortLabels = { rag: 'RAG', wiki: 'Wiki', hybrid: '智能混合', chat: '普通对话' }

export function knowledgeModeLabel(mode) {
  return knowledgeModeOptions.find((item) => item.mode === mode)?.label || legacyModeLabels[mode] || '未设置'
}

export function knowledgeModeShortLabel(mode) {
  return shortLabels[mode] || '未设置'
}

/** 人工新增知识可关联的真实用户端页面，与后端知识目录白名单一致。 */
export const knowledgeSourcePages = [
  { value: '/', label: '首页' },
  { value: '/register', label: '注册页' },
  { value: '/login', label: '登录页' },
  { value: '/reset', label: '找回密码页' },
  { value: '/search', label: '站内搜索' },
  { value: '/editor', label: '文章编辑器' },
  { value: '/creation/articlemanage', label: '文章管理' },
  { value: '/creation/columnmanage', label: '专栏管理' },
  { value: '/article', label: '文章详情' },
  { value: '/setting', label: '个人设置' },
]

export function knowledgeSourcePageLabel(value) {
  return knowledgeSourcePages.find((item) => item.value === value)?.label || '平台页面'
}
