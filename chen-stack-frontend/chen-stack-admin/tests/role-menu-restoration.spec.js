import { afterEach, beforeEach, describe, expect, it, vi } from 'vite-plus/test'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElTree } from 'element-plus'
import Role from '@/views/system/role/index.vue'
import { getRolePage } from '@/api/role'
import { getAllMenuList } from '@/api/menu'
import { assignMenus, getMenusByRole } from '@/api/role-menu'

vi.mock('@/api/role', () => ({ getRolePage: vi.fn(), updateRole: vi.fn(), deleteRole: vi.fn(), queryRolePage: vi.fn() }))
vi.mock('@/api/menu', () => ({ getAllMenuList: vi.fn() }))
vi.mock('@/api/role-menu', () => ({ assignMenus: vi.fn(), getMenusByRole: vi.fn() }))

const menus = [
  { id: 1, parentId: 0, name: '首页', path: '/home' },
  { id: 2, parentId: 0, name: '系统管理', path: '/system' },
  { id: 4, parentId: 2, name: '角色管理', path: '/system/role' },
  { id: 20, parentId: 0, name: '原有内容', path: '/content' },
  { id: 21, parentId: 20, name: '原有文章', path: '/content/articles' },
  { id: 22, parentId: 20, name: '原有标签', path: '/content/tags' },
  { id: 9300, parentId: 0, name: 'AI知识管理', path: '/ai' },
  { id: 9301, parentId: 9300, name: '原始知识', path: '/ai/rag' },
  { id: 9302, parentId: 9300, name: '整理知识', path: '/ai/wiki' },
  { id: 9303, parentId: 9300, name: '知识来源', path: '/ai/sources' },
  { id: 9304, parentId: 9300, name: '智能整理', path: '/ai/tasks' },
  { id: 9305, parentId: 9300, name: '检索验证', path: '/ai/search' },
]
const contentRole = { id: 3, role: 'content_admin', name: '内容管理员' }
let wrapper

beforeEach(() => {
  vi.clearAllMocks()
  getRolePage.mockResolvedValue({ data: { data: [], total: 0 } })
  getAllMenuList.mockResolvedValue({ data: menus })
  assignMenus.mockResolvedValue({ data: null })
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

async function open(menuIds, role = contentRole) {
  getMenusByRole.mockResolvedValue({ data: menuIds })
  wrapper = mount(Role, { attachTo: document.body, global: { plugins: [ElementPlus], stubs: { ManagementCard: { template: '<div />' } } } })
  await flushPromises()
  await wrapper.vm.handleAuthorizeMenu(role)
  await flushPromises()
  return wrapper.findComponent(ElTree)
}
async function submit() {
  await wrapper.vm.handleAuthorizeMenuSubmit()
  await flushPromises()
  return assignMenus.mock.calls.at(-1)[0].menuIds.toSorted((a, b) => a - b)
}
async function clickCheckbox(label) {
  const row = [...document.body.querySelectorAll('.el-tree-node__content')]
    .find(node => node.querySelector('.node-label')?.textContent === label)
  row.querySelector('.el-checkbox').click()
  await flushPromises()
}

describe('角色菜单按精确授权回填，人工选择仍级联', () => {
  it.each([
    ['AI父及部分子菜单', [1, 9300, 9301]],
    ['仅AI父菜单', [1, 9300]],
    ['原有非AI父及部分子菜单', [1, 20, 21]],
    ['仅原有非AI父菜单', [20]],
    ['既有仅子菜单', [9301]],
  ])('%s重新打开且未编辑时精确保留原payload', async (_name, granted) => {
    const tree = await open(granted)
    expect(tree.props('checkStrictly')).toBe(false)
    expect(tree.vm.getCheckedKeys().toSorted((a, b) => a - b)).toEqual(granted.toSorted((a, b) => a - b))
    expect(await submit()).toEqual(granted.toSorted((a, b) => a - b))
    await wrapper.vm.handleAuthorizeMenu(contentRole)
    await flushPromises()
    expect(await submit()).toEqual(granted.toSorted((a, b) => a - b))
  })

  it('重新打开后撤销RAG不夹带任何兄弟菜单', async () => {
    await open([1, 9300, 9301])
    await clickCheckbox('原始知识')
    expect(await submit()).toEqual([1])
  })

  it('人工勾选父菜单仍全选其子菜单', async () => {
    await open([])
    await clickCheckbox('AI知识管理')
    expect(await submit()).toEqual([9300, 9301, 9302, 9303, 9304, 9305])
  })

  it('人工勾选子菜单仍包含必要父菜单', async () => {
    await open([])
    await clickCheckbox('原始知识')
    expect(await submit()).toEqual([9300, 9301])
  })

  it('管理员既有系统菜单保护不扩大AI兄弟菜单', async () => {
    await open([1, 9300, 9301], { id: 1, role: 'admin', name: '管理员' })
    expect(await submit()).toEqual([1, 2, 4, 9300, 9301])
  })
})
