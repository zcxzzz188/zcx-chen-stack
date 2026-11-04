import { describe, it, expect, vi, beforeEach } from 'vite-plus/test'
import router, { refreshDynamicRoutes } from '@/router'
import { useUserStore } from '@/stores/userStore'

vi.mock('@/stores/userStore', () => ({ useUserStore: vi.fn() }))
vi.mock('@/views/account/login.vue', () => ({ default: { template: '<main />' } }))
vi.mock('@/components/layout/Layout.vue', () => ({ default: { template: '<router-view />' } }))
let store
const wikiMenu = [{ path: '/ai', name: 'AI知识管理', children: [{ path: '/ai/wiki', name: 'Wiki知识', component: { template: '<main />' } }] }]
beforeEach(async () => {
  localStorage.clear()
  store = { user: { id: 1 }, routes: [], routesAdded: false, getUserInfo: vi.fn(), loadMenusAndRoutes: vi.fn().mockResolvedValue([]) }
  useUserStore.mockReturnValue(store)
  await refreshDynamicRoutes()
})
describe('AI知识页面沿用真实动态菜单路由', () => {
  it('匿名不能进入管理页面', async () => { await router.push('/ai/wiki'); expect(router.currentRoute.value.path).toBe('/login') })
  it('未分配菜单的用户没有知识路由，也没有独立静态Wiki入口', async () => {
    localStorage.setItem('chen_stack_jwt', 'synthetic')
    await router.push('/ai/wiki')
    expect(router.currentRoute.value.name).toBe('notFound')
    expect(router.hasRoute('wikiAdmin')).toBe(false)
  })
  it('服务端分配知识菜单后可以进入，刷新权限后撤销对应路由', async () => {
    localStorage.setItem('chen_stack_jwt', 'synthetic')
    store.loadMenusAndRoutes.mockResolvedValue(wikiMenu)
    await refreshDynamicRoutes()
    await router.push('/ai/wiki')
    expect(router.currentRoute.value.name).toBe('Wiki知识')
    store.loadMenusAndRoutes.mockResolvedValue([])
    await refreshDynamicRoutes()
    expect(router.hasRoute('Wiki知识')).toBe(false)
    expect(router.currentRoute.value.path).toBe('/home')
  })
})
