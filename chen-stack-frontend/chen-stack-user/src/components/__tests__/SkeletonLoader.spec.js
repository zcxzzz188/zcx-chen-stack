import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SkeletonLoader from '../Loading/SkeletonLoader.vue'

describe('SkeletonLoader', () => {
  it('默认显示三张卡片', () => {
    const wrapper = mount(SkeletonLoader)
    expect(wrapper.find('.skeleton-list').exists()).toBe(true)
    expect(wrapper.findAll('.skeleton-card')).toHaveLength(3)
    expect(wrapper.props('loading')).toBe(true)
  })

  it.each([0, -5, 1, 3])('count=%s 至少显示一张卡片', (count) => {
    const wrapper = mount(SkeletonLoader, { props: { count } })
    expect(wrapper.findAll('.skeleton-card')).toHaveLength(Math.max(1, count))
  })

  it('加载完成后隐藏骨架屏', async () => {
    const wrapper = mount(SkeletonLoader)
    await wrapper.setProps({ loading: false })
    expect(wrapper.find('.skeleton-list').exists()).toBe(false)
    expect(wrapper.findAll('.skeleton-card')).toHaveLength(0)
    await wrapper.setProps({ loading: true, count: 2 })
    expect(wrapper.findAll('.skeleton-card')).toHaveLength(2)
  })

  it('每张卡片包含图片和四行正文占位', () => {
    const wrapper = mount(SkeletonLoader, { props: { count: 1 } })
    expect(wrapper.findAll('.skeleton-image')).toHaveLength(1)
    expect(wrapper.findAll('.skeleton-line')).toHaveLength(4)
  })
})
