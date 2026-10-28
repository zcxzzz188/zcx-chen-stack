import { describe, it, expect } from 'vite-plus/test'
import { renderWikiMarkdown, safeWikiLink } from '@/utils/wikiMarkdown'

describe('Wiki 管理预览的 Markdown 安全边界', () => {
  it('保留标题、目录、代码块、表格和引用', () => {
    const result = renderWikiMarkdown('# 缓存\n\n## 配置\n\n```java\nint ttl = 60;\n```\n\n> 引文\n\n| 键 | 值 |\n| - | - |\n| TTL | 60 |')
    expect(result.headings.map((heading) => heading.title)).toEqual(['缓存', '配置'])
    const root = document.createElement('div'); root.innerHTML = result.html
    expect(root.querySelector('#wikih-2')?.textContent).toBe('配置')
    expect(root.querySelector('pre code')?.textContent).toContain('int ttl = 60;')
    expect(root.querySelector('blockquote')).not.toBeNull()
    expect(root.querySelector('table')).not.toBeNull()
  })
  it('禁用原始 HTML、事件属性、script、iframe、SVG 和外部图片', () => {
    const { html } = renderWikiMarkdown('<script>alert(1)</script>\n\n<img src=x onerror=alert(2)>\n<iframe src="https://evil.example"></iframe>\n<svg onload=alert(3) />\n\n![secret](https://evil.example/pixel)')
    const root = document.createElement('div'); root.innerHTML = html
    expect(root.querySelector('script, img, iframe, svg, [onerror], [onload]')).toBeNull()
  })
  it('拒绝危险协议、协议相对 URL、凭据、控制符和未知站内路径', () => {
    for (const url of ['javascript:alert(1)', 'data:text/html,x', '//evil.example/x', 'https://name:secret@example.org/', '/admin/wiki', '/wiki/../admin', '/wiki/0', '/wiki/1?debug=1', 'https://example.org\\evil', 'https://example.org\n/x', '#untrusted']) {
      expect(safeWikiLink(url)).toBe('')
    }
  })
  it('只允许 Wiki、公开文章和固定标题锚点及 http/https 外链', () => {
    for (const url of ['/wiki/12', '/user/3/article/21', '#wikih-3', 'https://example.org/wiki', 'http://example.org/']) expect(safeWikiLink(url)).toBe(url)
  })
  it('净化 Markdown 链接并限制外链行为', () => {
    const { html } = renderWikiMarkdown('[外站](https://example.org/) [后台](/admin/wiki) [页面](/wiki/1) [危险](javascript:alert(1))')
    const root = document.createElement('div'); root.innerHTML = html
    expect(root.querySelector('a[href="https://example.org/"]')?.getAttribute('rel')).toBe('noopener noreferrer nofollow')
    expect(root.querySelector('a[href="/wiki/1"]')).not.toBeNull()
    expect(root.querySelector('a[href="/admin/wiki"],a[href^="javascript:"]')).toBeNull()
  })
})
