import { it, expect, vi } from 'vite-plus/test'
import request from '@/utils/Request'
import router from '@/router/index.js'

vi.mock('@/router/index.js', () => ({ default: { push: vi.fn() } }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), success: vi.fn() } }))

it('实际axios响应拦截器收到401后清除会话并拒绝请求', async () => {
  vi.useFakeTimers()
  try {
    localStorage.setItem('chen_stack_jwt', 'synthetic-expired-session')
    const error = Object.assign(new Error('expired'), { response: { status: 401, data: { msg: 'expired' } } })
    await expect(request({ url: '/synthetic', adapter: () => Promise.reject(error) })).rejects.toBe(error)
    expect(localStorage.getItem('chen_stack_jwt')).toBeNull()
    expect(router.push).toHaveBeenCalledWith('/login')
  } finally { vi.clearAllTimers(); vi.useRealTimers() }
})
