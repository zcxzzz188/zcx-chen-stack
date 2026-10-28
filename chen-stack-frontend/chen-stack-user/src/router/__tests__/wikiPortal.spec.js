import { beforeEach, describe, expect, it, vi } from 'vitest'
import { isWikiPortalEnabled } from '@/utils/wikiPortal'

vi.mock('@/utils/wikiPortal', () => ({ isWikiPortalEnabled: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))
vi.mock('@/views/Layout/index.vue', () => ({ default: { template: '<router-view />' } }))
vi.mock('@/views/Home/index.vue', () => ({ default: { template: '<main>Home</main>' } }))
vi.mock('@/views/Wiki/index.vue', () => ({ default: { template: '<main>Wiki</main>' } }))
vi.mock('@/views/Wiki/Detail.vue', () => ({ default: { template: '<main>Detail</main>' } }))

describe('Public Wiki route policy', () => {
  let router
  beforeEach(async () => {
    router = (await import('@/router')).default
    await router.push('/')
  })
  it('redirects directory and detail to home while portal is disabled', async () => {
    vi.mocked(isWikiPortalEnabled).mockResolvedValue(false)
    await router.push('/wiki')
    expect(router.currentRoute.value.path).toBe('/')
    await router.push('/wiki/1')
    expect(router.currentRoute.value.path).toBe('/')
  })
  it('keeps the existing reading routes available when enabled', async () => {
    vi.mocked(isWikiPortalEnabled).mockResolvedValue(true)
    await router.push('/wiki')
    expect(router.currentRoute.value.path).toBe('/wiki')
    await router.push('/wiki/1')
    expect(router.currentRoute.value.path).toBe('/wiki/1')
  })
})
