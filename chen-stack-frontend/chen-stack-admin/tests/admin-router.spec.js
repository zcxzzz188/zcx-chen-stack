import { describe, it, expect, vi, beforeEach } from 'vite-plus/test'
import { createPinia, setActivePinia } from 'pinia'
import router from '@/router'
import { useUserStore } from '@/stores/userStore'

vi.mock('@/stores/userStore', () => ({ useUserStore: vi.fn() }))
// 登录页渲染由组件测试覆盖，守卫测试不等待整页依赖编译。
vi.mock('@/views/account/login.vue', () => ({ default: { template: '<main />' } }))
vi.mock('@/api/user', () => ({ login: vi.fn(), checkCode: vi.fn(), info: vi.fn() }))
let store
beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  store = { user: null, routes: [], routesAdded: false, getUserInfo: vi.fn(), loadMenusAndRoutes: vi.fn().mockResolvedValue([]) }
  vi.mocked(useUserStore, { partial: true }).mockReturnValue(store)
})
describe('管理端真实路由守卫', () => {
  it('保留登录和布局基础路由', () => {
    expect(router.hasRoute('login')).toBe(true)
    expect(router.hasRoute('layout')).toBe(true)
    expect(router.resolve('/login').name).toBe('login')
  })
  it('匿名访问控制台跳转登录且不加载管理菜单', async () => {
    await router.push('/home')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(store.getUserInfo).not.toHaveBeenCalled()
    expect(store.loadMenusAndRoutes).not.toHaveBeenCalled()
  })
  it('会话失效时清除本地凭据并跳转登录', async () => {
    localStorage.setItem('chen_stack_jwt', 'synthetic-expired-session')
    store.getUserInfo.mockRejectedValue(new Error('synthetic expired'))
    await router.push('/')
    expect(store.getUserInfo).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('chen_stack_jwt')).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
  })
})
