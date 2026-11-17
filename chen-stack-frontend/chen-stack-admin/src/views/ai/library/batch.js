export async function runKnowledgeBatch(rows, eligible, update) {
  const result = { success: 0, failed: [], skipped: 0 }
  for (const row of rows) {
    if (!eligible(row)) {
      result.skipped += 1
      continue
    }
    try {
      await update(row)
      result.success += 1
    } catch (error) {
      result.failed.push({
        id: row.id,
        title: row.title,
        reason: error?.response?.status === 403 ? '没有操作权限' : error?.msg || error?.message || '请求失败',
      })
    }
  }
  return result
}
