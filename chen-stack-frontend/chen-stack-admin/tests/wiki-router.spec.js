import { describe, it, expect, vi, beforeEach } from 'vite-plus/test'
import router from '@/router'
import { useUserStore } from '@/stores/userStore'

vi.mock('@/stores/userStore', () => ({ useUserStore: vi.fn() }))
vi.mock('@/views/wiki/index.vue', () => ({ default: { template: '<main />' } }))
vi.mock('@/views/account/login.vue', () => ({ default: { template: '<main />' } }))
vi.mock('@/components/layout/Layout.vue', () => ({ default: { template: '<router-view />' } }))
let store
beforeEach(() => {
  localStorage.clear()
  store = { user: { id: 1, username: 'synthetic', roleCode: null }, routes: [], routesAdded: true, getUserInfo: vi.fn(), loadMenusAndRoutes: vi.fn(), checkWikiAccess: vi.fn().mockResolvedValue(false) }
  useUserStore.mockReturnValue(store)
})
describe('Wiki 管理端路由权限', () => {
  it('匿名不能进入管理页面', async () => { await router.push('/wiki'); expect(router.currentRoute.value.path).toBe('/login') })
  it('普通用户不能进入管理页面', async () => { localStorage.setItem('chen_stack_jwt', 'synthetic'); await router.push('/wiki'); expect(router.currentRoute.value.path).toBe('/home') })
  it('真实 SysUserVo 不含角色列表时，以后端授权预检允许 admin 进入', async () => { localStorage.setItem('chen_stack_jwt', 'synthetic'); store.checkWikiAccess.mockResolvedValue(true); await router.push('/wiki'); expect(router.currentRoute.value.name).toBe('wikiAdmin'); expect(store.checkWikiAccess).toHaveBeenCalledOnce() })
})
