import { describe, it, expect, vi, beforeEach, afterEach } from 'vite-plus/test'
import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import App from '@/App.vue'
import Login from '@/views/account/login.vue'
import Home from '@/views/home/index.vue'
import { login, checkCode } from '@/api/user'
import { getDashboardAll } from '@/api/dashboard'

vi.mock('@/router', () => ({ default: { isReady: () => Promise.resolve() } }))
vi.mock('@/api/user', () => ({ login: vi.fn(), checkCode: vi.fn(), info: vi.fn() }))
vi.mock('@/api/menu', () => ({ getMenuList: vi.fn() }))
vi.mock('@/api/dashboard', () => ({ getDashboardAll: vi.fn(), getVisitorTrend: vi.fn() }))

let wrappers = []
beforeEach(() => {
  vi.useFakeTimers()
  setActivePinia(createPinia())
  sessionStorage.clear()
  localStorage.clear()
  vi.mocked(checkCode, { partial: true }).mockResolvedValue({ data: { checkCodeKey: 'synthetic', checkCodeBase64: 'data:image/png;base64,c3ludGhldGlj' } })
  vi.mocked(getDashboardAll, { partial: true }).mockResolvedValue({ data: { statistics: { userTotalCount: 42, articleStatistics: { totalCount: 7 } } } })
})
afterEach(() => {
  wrappers.forEach(w => w.unmount()); wrappers = []
  vi.clearAllTimers(); vi.useRealTimers(); vi.clearAllMocks()
})
function keep(wrapper) { wrappers.push(wrapper); return wrapper }

describe('管理端最小组件验证（合成数据）', () => {
  it('应用根组件提供路由出口', () => {
    const wrapper = keep(mount(App, { global: { stubs: { RouterView: { template: '<main data-testid="route-outlet" />' } } } }))
    expect(wrapper.find('[data-testid="route-outlet"]').exists()).toBe(true)
  })
  it('登录页加载验证码并保留用户名、密码与验证码输入', async () => {
    const wrapper = keep(mount(Login, { global: { plugins: [createPinia(), ElementPlus] } }))
    await flushPromises()
    expect(wrapper.text()).toContain('管理员登录')
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请输入验证码"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="请输入用户名"]').element.value).toBe('')
    expect(wrapper.find('input[type="password"]').element.value).toBe('')
    expect(wrapper.text()).not.toContain('test / 123456')
    expect(checkCode).toHaveBeenCalledTimes(1)
  })
  it('验证码加载失败时显示错误并允许重试', async () => {
    vi.mocked(checkCode).mockRejectedValueOnce(new Error('HTTP404'))
    const wrapper = keep(mount(Login, { global: { plugins: [createPinia(), ElementPlus] } }))
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('验证码加载失败')
    expect(wrapper.find('.check-code-image').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(3000)
    await wrapper.findAll('button').find(button => button.text() === '重新获取').trigger('click')
    await flushPromises()
    expect(checkCode).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('.check-code-image').attributes('src')).toContain('data:image/')
  })
  it('验证码为空时禁止发送登录请求', async () => {
    const wrapper = keep(mount(Login, { global: { plugins: [createPinia(), ElementPlus] } }))
    await flushPromises()
    await wrapper.find('.login-button').trigger('click')
    await vi.advanceTimersByTimeAsync(100)
    await flushPromises()
    expect(login).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入验证码')
  })
  it('控制台将合成统计传给统计卡并结束加载', async () => {
    const wrapper = keep(shallowMount(Home))
    await flushPromises()
    expect(getDashboardAll).toHaveBeenCalledWith(7)
    const cards = wrapper.findAllComponents({ name: 'StatCard' })
    expect(cards).toHaveLength(4)
    expect(cards[0].props('value')).toBe(42)
    expect(cards[1].props('value')).toBe(7)
    expect(cards[0].props('loading')).toBe(false)
  })
})
