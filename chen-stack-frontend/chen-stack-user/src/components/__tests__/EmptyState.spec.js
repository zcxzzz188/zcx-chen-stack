import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import EmptyState from '../Loading/EmptyState.vue'

describe('EmptyState', () => {
  it('应渲染组件', () => {
    const wrapper = mount(EmptyState)
    expect(wrapper.exists()).toBe(true)
    expect(wrapper.find('.empty-state').exists()).toBe(true)
  })

  it('应使用默认 type 为 custom', () => {
    const wrapper = mount(EmptyState)
    expect(wrapper.props('type')).toBe('custom')
  })

  it('应接受 article type', () => {
    const wrapper = mount(EmptyState, {
      props: { type: 'article' },
    })
    expect(wrapper.props('type')).toBe('article')
  })

  it('应接受 comment type', () => {
    const wrapper = mount(EmptyState, {
      props: { type: 'comment' },
    })
    expect(wrapper.props('type')).toBe('comment')
  })

  it('应接受 message type', () => {
    const wrapper = mount(EmptyState, {
      props: { type: 'message' },
    })
    expect(wrapper.props('type')).toBe('message')
  })

  it('应接受 search type', () => {
    const wrapper = mount(EmptyState, {
      props: { type: 'search' },
    })
    expect(wrapper.props('type')).toBe('search')
  })

  it('应接受自定义描述文字', () => {
    const wrapper = mount(EmptyState, {
      props: { description: '自定义描述文字' },
    })
    expect(wrapper.props('description')).toBe('自定义描述文字')
  })

  it('应显示文章类型的默认描述', () => {
    const wrapper = mount(EmptyState, {
      props: { type: 'article' },
    })
    expect(wrapper.text()).toContain('暂无文章')
  })

  it('默认自定义类型不显示描述', () => {
    const wrapper = mount(EmptyState)
    expect(wrapper.find('.empty-description').text()).toBe('')
  })

  it('应使用默认描述为空字符串', () => {
    const wrapper = mount(EmptyState)
    expect(wrapper.props('description')).toBe('')
  })

  it('应渲染 slot 内容', () => {
    const wrapper = mount(EmptyState, {
      slots: {
        default: '<button>点击重试</button>',
      },
    })
    expect(wrapper.find('button').exists()).toBe(true)
    expect(wrapper.find('button').text()).toBe('点击重试')
  })

  it('自定义描述应覆盖默认类型描述', () => {
    const wrapper = mount(EmptyState, {
      props: {
        type: 'article',
        description: '自定义文章描述',
      },
    })
    expect(wrapper.props('description')).toBe('自定义文章描述')
  })
})
