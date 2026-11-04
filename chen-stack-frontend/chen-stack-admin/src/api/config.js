import request from '@/utils/Request'

const base = '/admin/ai-config'
export const aiConfigApi = {
  status: () => request({ url: base, method: 'get', timeout: 30000 }),
  setMode: (mode) => request({ url: `${base}/knowledge-mode`, method: 'put', data: { mode }, timeout: 30000 }),
  setDailyLimit: (dailyLimit) => request({ url: `${base}/daily-limit`, method: 'put', data: { dailyLimit }, timeout: 30000 }),
}
