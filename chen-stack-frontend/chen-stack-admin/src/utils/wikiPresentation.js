export function readableWikiMarkdown(markdown, sources = []) {
  if (typeof markdown !== 'string') return ''
  let inBody = !markdown.startsWith('# Wiki 编译草稿\n')
  return markdown.split('\n').filter((line) => {
    if (line.startsWith('## ')) inBody = true
    return inBody || !['# Wiki 编译草稿', '状态：DRAFT', '编译版本：', '任务：', '来源清单版本：'].some((prefix) => line.startsWith(prefix))
  }).map((line) => {
    const match = line.match(/^(\s*)- snapshot=(\d+) .*? quote=“(.*)”$/)
    if (match) {
      const source = sources?.find((item) => String(item.snapshotId) === match[2])
      const title = source?.title ? `（${source.title.replace(/[\\`*_{}\[\]()#+.!>|-]/g, '\\$&')}）` : ''
      return `${match[1]}- 引用${title}：“${match[3]}”`
    }
    return line.replaceAll('SEMANTIC_NOT_VERIFIED', '待人工核查')
  }).join('\n')
}
