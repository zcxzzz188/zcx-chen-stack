import { describe, it, expect } from 'vitest'
import { readableWikiMarkdown } from '../src/utils/wikiPresentation'

describe('Wiki 管理页面的业务化展示', () => {
  it('已发布正文同样保留不确定性并翻译内部状态', () => {
    const rendered = readableWikiMarkdown('## 登录\n正文 [SEMANTIC_NOT_VERIFIED] [f1]')
    expect(rendered).toContain('正文 [待人工核查] [f1]')
    expect(rendered).not.toContain('SEMANTIC_NOT_VERIFIED')
  })
  it('草稿隐藏任务元数据并保留精确引用内容与来源标题', () => {
    const rendered = readableWikiMarkdown('# Wiki 编译草稿\n任务：internal-job\n## 登录\n- snapshot=2 source=PLATFORM_HELP:login version=v1 generation=1 selector=utf16:0-2 quote=“原文”', [{ snapshotId: 2, title: '登录帮助' }])
    expect(rendered).toContain('引用（登录帮助）：“原文”')
    expect(rendered).not.toContain('internal-job')
    expect(rendered).not.toContain('snapshot=')
    expect(rendered).not.toContain('selector=')
  })
  it('普通正文不丢失，非文本输入不会被显示成对象', () => {
    expect(readableWikiMarkdown('## 普通知识\n需要完整保留的规则')).toBe('## 普通知识\n需要完整保留的规则')
    expect(readableWikiMarkdown(null)).toBe('')
    expect(readableWikiMarkdown('## 来源\n- snapshot=1 source=x quote=“原文”', null)).toContain('引用：“原文”')
  })
})
