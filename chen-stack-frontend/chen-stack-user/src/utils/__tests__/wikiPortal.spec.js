import { afterEach, describe, expect, it, vi } from 'vitest'
import { isWikiPortalEnabled } from '../wikiPortal'

afterEach(() => vi.unstubAllGlobals())

describe('Wiki public portal configuration', () => {
  it('opens only for an explicit successful true response', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ code: 200, data: true }) })
    vi.stubGlobal('fetch', fetch)
    expect(await isWikiPortalEnabled()).toBe(true)
    expect(fetch.mock.calls[0][0]).toContain('/wiki/pages?portalStatus')
  })

  it('fails closed for false, malformed, failed or unavailable status', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ code: 200, data: false }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ code: 200, data: 'true' }) })
      .mockResolvedValueOnce({ ok: false })
      .mockRejectedValueOnce(new Error('offline'))
    vi.stubGlobal('fetch', fetch)
    for (let i = 0; i < 4; i++) expect(await isWikiPortalEnabled()).toBe(false)
  })
})
