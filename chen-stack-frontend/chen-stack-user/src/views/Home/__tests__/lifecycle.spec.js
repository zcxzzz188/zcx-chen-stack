import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import Home from '../index.vue'
import { getAllArticleList } from '@/api/article'

vi.mock('@/api/article', () => ({
  getAllArticleList: vi.fn(async () => ({ data: { data: [] } })),
  getHotTags: vi.fn(async () => ({ data: [] })),
}))
vi.mock('@/api/user', () => ({ getCommunityStats: vi.fn(async () => ({ data: {} })) }))
vi.mock('@/stores/userStore', () => ({ useUserStore: () => ({ user: null }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/plugins/seo', () => ({ useSeoMeta: vi.fn() }))
vi.mock('@/utils/Request', () => ({ default: vi.fn() }))
vi.mock('@/components/Layout/Header.vue', () => ({ default: { template: '<header />' } }))
vi.mock('/icons/favicon.ico', () => ({ default: '/icons/favicon.ico' }))

let wrapper
let disconnect
beforeEach(() => {
  vi.useFakeTimers()
  disconnect = vi.fn()
  vi.stubGlobal('IntersectionObserver', class {
    observe() {}
    unobserve() {}
    disconnect = disconnect
  })
})
afterEach(() => {
  wrapper?.unmount()
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('首页资源释放', () => {
  it('页面离开后停止循环定时器并断开观察器', async () => {
    wrapper = shallowMount(Home)
    await flushPromises()
    vi.advanceTimersByTime(1000)
    expect(vi.getTimerCount()).toBe(1)
    wrapper.unmount()
    wrapper = null
    expect(vi.getTimerCount()).toBe(0)
    expect(disconnect).toHaveBeenCalledOnce()
  })

  it('接口在卸载后返回，不再创建入场动画观察器或定时器', async () => {
    let resolve
    getAllArticleList.mockImplementationOnce(() => new Promise((done) => { resolve = done }))
    wrapper = shallowMount(Home)
    wrapper.unmount()
    wrapper = null
    resolve({ data: { data: [] } })
    await flushPromises()
    expect(vi.getTimerCount()).toBe(0)
    expect(disconnect).not.toHaveBeenCalled()
  })
})
