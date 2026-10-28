import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const markdown = new MarkdownIt({ html: false, linkify: false, breaks: true })
markdown.renderer.rules.image = () => ''

export function safeWikiLink(value) {
  if (typeof value !== 'string' || /[\u0000-\u0020\u007f\\]/.test(value)) return ''
  if (/^#wikih-\d+$/.test(value)) return value
  if (/^\/wiki\/[1-9]\d*$/.test(value) || /^\/user\/[1-9]\d*\/article\/[1-9]\d*$/.test(value)) return value
  if (!/^https?:\/\//i.test(value)) return ''
  try {
    const url = new URL(value)
    return url.username || url.password ? '' : url.href
  } catch {
    return ''
  }
}

export function renderWikiMarkdown(value) {
  const tokens = markdown.parse(typeof value === 'string' ? value : '', {})
  const headings = []
  tokens.forEach((token, index) => {
    if (token.type !== 'heading_open') return
    const id = `wikih-${headings.length + 1}`
    token.attrSet('id', id)
    headings.push({ id, level: Number(token.tag.slice(1)), title: tokens[index + 1]?.content || '' })
  })
  const fragment = DOMPurify.sanitize(markdown.renderer.render(tokens, markdown.options, {}), {
    ALLOWED_TAGS: ['p', 'br', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'em', 'strong', 's', 'hr', 'a', 'table', 'thead', 'tbody', 'tr', 'th', 'td'],
    ALLOWED_ATTR: ['href', 'title', 'id', 'class'],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
    RETURN_DOM_FRAGMENT: true,
  })
  for (const link of fragment.querySelectorAll('a')) {
    const href = safeWikiLink(link.getAttribute('href'))
    if (!href) link.removeAttribute('href')
    else {
      link.setAttribute('href', href)
      if (/^https?:/.test(href)) {
        link.setAttribute('target', '_blank')
        link.setAttribute('rel', 'noopener noreferrer nofollow')
      }
    }
  }
  const container = document.createElement('div')
  container.append(fragment)
  return { html: container.innerHTML, headings }
}
