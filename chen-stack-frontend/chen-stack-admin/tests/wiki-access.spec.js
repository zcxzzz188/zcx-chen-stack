import { describe, it, expect, vi, beforeEach } from 'vite-plus/test'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from '@/stores/userStore'
import { wikiApi } from '@/api/wiki'

vi.mock('@/api/wiki', () => ({ wikiApi: { access: vi.fn() } }))
vi.mock('@/api/user', () => ({ info: vi.fn() }))
vi.mock('@/api/menu', () => ({ getMenuList: vi.fn() }))
beforeEach(() => { setActivePinia(createPinia()); localStorage.clear(); vi.clearAllMocks() })
describe('Wiki 管理能力使用可信后端判定', () => {
  it('当前管理用户没有 sysRoles 时也能由后端确认能力', async () => {
    const store = useUserStore()
    store.setUser({ id: 1, username: 'synthetic', roleCode: null })
    wikiApi.access.mockResolvedValue({ data: true })
    expect(await store.checkWikiAccess()).toBe(true)
    expect(store.wikiAccess).toBe(true)
  })
  it('403 隐藏入口但不会清除现有登录态', async () => {
    const store = useUserStore()
    localStorage.setItem('chen_stack_jwt', 'synthetic-session')
    wikiApi.access.mockRejectedValue({ response: { status: 403 } })
    expect(await store.checkWikiAccess()).toBe(false)
    expect(localStorage.getItem('chen_stack_jwt')).toBe('synthetic-session')
  })
  it('退出或换用户时清除旧能力，产品未启用时失败关闭', async () => {
    const store = useUserStore()
    wikiApi.access.mockResolvedValue({ data: true })
    await store.checkWikiAccess()
    store.setUser({ id: 2 })
    expect(store.wikiAccess).toBe(false)
    wikiApi.access.mockRejectedValue({ response: { status: 404 } })
    expect(await store.checkWikiAccess()).toBe(false)
    store.clearUser()
    expect(store.wikiAccess).toBe(false)
  })
})
