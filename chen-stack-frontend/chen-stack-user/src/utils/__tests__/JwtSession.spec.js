import { it, expect } from 'vite-plus/test'
import { sameJwtAccount } from '../JwtSession'

const token = (id, exp) => `header.${btoa(JSON.stringify({ id, exp }))}.signature`
it('同账号正常刷新不丢弃响应，不同账号和损坏凭据不能复用响应', () => {
  expect(sameJwtAccount(token(7, 1), token(7, 2))).toBe(true)
  expect(sameJwtAccount(token(7, 1), token(8, 2))).toBe(false)
  expect(sameJwtAccount(token(7, 1), null)).toBe(false)
  expect(sameJwtAccount('bad', 'other-bad')).toBe(false)
})
