import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElIcon, ElImage } from 'element-plus'
import ArticleCover from '../ArticleCover.vue'

let wrapper
const render = async (src) => {
  wrapper = mount(ArticleCover, {
    props: { src },
    global: { components: { ElImage, ElIcon } },
  })
  await flushPromises()
  return wrapper
}
afterEach(() => wrapper?.unmount())

describe('文章封面', () => {
  it.each([null, undefined, '', '  \t\n'])('空地址 %s 只显示静态占位', async (src) => {
    await render(src)
    expect(wrapper.findComponent(ElImage).exists()).toBe(false)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('.cover-fallback').attributes('aria-label')).toBe('暂无封面')
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.find('.is-loading').exists()).toBe(false)
  })

  it('去除地址两侧空白，加载成功后隐藏加载占位', async () => {
    await render('  /cover.png  ')
    expect(wrapper.find('img').attributes('src')).toBe('/cover.png')
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
    await wrapper.find('img').trigger('load')
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.find('.cover-fallback').exists()).toBe(false)
  })

  it('失败后静态回退，同一地址刷新不重试，新地址重新加载', async () => {
    await render('/broken.png')
    await wrapper.find('img').trigger('error')
    expect(wrapper.find('.cover-fallback').exists()).toBe(true)
    expect(wrapper.find('img').exists()).toBe(false)
    await wrapper.setProps({ src: ' /broken.png ' })
    expect(wrapper.find('img').exists()).toBe(false)
    await wrapper.setProps({ src: '/valid.png' })
    await flushPromises()
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
    await wrapper.find('img').trigger('load')
    expect(wrapper.find('img').attributes('src')).toBe('/valid.png')
    expect(wrapper.find('.cover-fallback').exists()).toBe(false)
  })

  it('列表复用时空地址与有效地址互换，旧请求事件不能覆盖新图', async () => {
    await render('/slow.png')
    const oldImage = wrapper.find('img')
    await wrapper.setProps({ src: '/next.png' })
    await flushPromises()
    await oldImage.trigger('error')
    expect(wrapper.find('img').attributes('src')).toBe('/next.png')
    await wrapper.find('img').trigger('load')
    await wrapper.setProps({ src: null })
    expect(wrapper.find('img').exists()).toBe(false)
    await wrapper.setProps({ src: '/again.png', alt: '封面说明', fit: 'cover' })
    await flushPromises()
    expect(wrapper.find('img').attributes('alt')).toBe('封面说明')
    expect(wrapper.findComponent(ElImage).props('fit')).toBe('cover')
  })
})
