export async function isWikiPortalEnabled() {
  const base = (import.meta.env.VITE_BACKEND_SERVER || 'http://localhost:5000').replace(/\/+$/, '')
  try {
    const response = await fetch(`${base}/wiki/pages?portalStatus`, { cache: 'no-store', signal: AbortSignal.timeout(5000) })
    if (!response.ok) return false
    const result = await response.json()
    return result.code === 200 && result.data === true
  } catch {
    return false
  }
}
