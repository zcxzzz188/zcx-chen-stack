// 仅用于丢弃跨账号旧响应；身份与权限始终由后端验证。允许同账号正常刷新 JWT。
export function sameJwtAccount(sent, current) {
  if (sent === current) return true
  try {
    const owner = token => JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).id
    const id = owner(sent)
    return Number.isSafeInteger(id) && id > 0 && id === owner(current)
  } catch { return false }
}
