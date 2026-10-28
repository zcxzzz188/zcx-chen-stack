import { describe, expect, it } from 'vitest'
import { renderWikiMarkdown, safeWikiLink } from '../wikiMarkdown'

describe('Wiki Markdown trust boundary', () => {
  it('escapes HTML and never embeds active content or remote images', () => {
    const { html } = renderWikiMarkdown('<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n<iframe src="https://example.com"></iframe>\n\n![tracking](https://example.com/pixel)\n\n[bad](javascript:alert(1))\n\n[svg](data:image/svg+xml;base64,AAA)')
    const body = document.createElement('div')
    body.innerHTML = html
    expect(body.querySelector('script,img,iframe,svg,object,embed')).toBeNull()
    expect(body.querySelector('[onerror], [onclick], [style]')).toBeNull()
    expect(body.querySelector('a[href^="javascript"], a[href^="data:"]')).toBeNull()
  })

  it('keeps headings, code, quotes and safe links with fixed anchor identities', () => {
    const { html, headings } = renderWikiMarkdown('# 重复标题\n\n## 重复标题\n\n```js\nconst x = "<script>"\n```\n\n> 引用\n\n[目录](#wikih-1) [Wiki](/wiki/2) [来源](/user/1/article/2) [外链](https://example.com/docs) [后台](/admin/wiki)')
    const body = document.createElement('div')
    body.innerHTML = html
    expect(headings.map((heading) => heading.id)).toEqual(['wikih-1', 'wikih-2'])
    expect(body.querySelector('pre code').textContent).toContain('<script>')
    expect(body.querySelector('blockquote').textContent).toContain('引用')
    expect(body.querySelector('a[href="/wiki/2"]')).not.toBeNull()
    expect(body.querySelector('a[href="/user/1/article/2"]')).not.toBeNull()
    expect(body.querySelector('a[href="/admin/wiki"]')).toBeNull()
    const external = body.querySelector('a[href="https://example.com/docs"]')
    expect(external.getAttribute('rel')).toBe('noopener noreferrer nofollow')
    expect(external.getAttribute('target')).toBe('_blank')
  })

  it.each(['javascript:alert(1)', '//example.com', '/admin/wiki', '/wiki/1?debug=1', '/wiki/01', '/user/1', 'https://user:pass@example.com', 'https://example.com/\u000afoo', 'https://example.com\\evil', 'file:///secret', 'data:text/html,test', '#location'])('rejects unsafe or unapproved link %s', (value) => {
    expect(safeWikiLink(value)).toBe('')
  })
})
