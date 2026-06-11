<template>
  <ManagementCard title="角色管理" :showTimeFilter="false" :showPagination="true" v-model:model-current-page="currentPage" v-model:model-page-size="pageSize" :total="total" @search="fetchRoles">
    <!-- 筛选器 -->
    <template #filters>
      <KeywordSearch v-model="searchQuery" placeholder="搜索角色名称" :debounce="500" @search="handleSearch" />
      <SearchButtons @search="handleSearch" @reset="handleReset" />
    </template>

    <!-- 桌面端表格视图 -->
    <template #table-view>
      <div v-loading="loading" class="role-table-wrapper">
        <div class="role-page-tip">系统固定使用超级管理员、内容管理员和普通用户三种角色。</div>
        <el-table :data="paginatedRoleList" class="table" style="height: 100%">
          <el-table-column prop="id" label="角色id" width="70" />
          <el-table-column prop="role" label="角色标识" />
          <el-table-column prop="name" label="角色名称" />
          <el-table-column prop="description" label="角色描述" />
          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <span :title="getRoleStatusDisabledReason(row) || ''">
                <el-switch
                  v-model="row.status"
                  size="large"
                  active-color="var(--admin-primary)"
                  inactive-color="#cccccc"
                  active-text="正常"
                  inactive-text="禁用"
                  :active-value="0"
                  :inactive-value="1"
                  inline-prompt
                  :loading="switchLoading"
                  :disabled="isAdminRole(row)"
                  :before-change="() => handleStatusChange(row, row.status === 0 ? 1 : 0)"
                />
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" sortable width="120" />
          <el-table-column prop="updateTime" label="更新时间" sortable width="120" />
          <el-table-column label="操作" width="440" fixed="right" header-align="center">
            <template #default="{ row }">
              <div class="table-actions">
                <el-button type="primary" size="small" @click="handleEditRole(row)" :icon="Edit" class="edit-button"> 编辑 </el-button>
                <span :title="getRoleDeleteDisabledReason(row) || ''">
                  <el-button type="danger" size="small" @click="handleDeleteRole(row)" :icon="Delete" class="delete-button" :disabled="isBuiltInRole(row)"> 删除 </el-button>
                </span>
                <el-button v-if="shouldShowAuthorizeMenu(row)" size="small" type="success" @click="handleAuthorizeMenu(row)" :icon="Key" class="menu-button"> 分配菜单 </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- 移动端卡片视图 -->
    <template #card-view>
      <div v-loading="loading" class="role-cards">
        <div class="role-page-tip">系统固定使用超级管理员、内容管理员和普通用户三种角色。</div>
        <el-card v-for="role in paginatedRoleList" :key="role.id" class="role-card">
          <div class="role-card-content">
            <div class="role-header">
              <div class="header-left">
                <div class="role-id">#{{ role.id }}</div>
                <el-tag :type="role.status === 0 ? 'success' : 'danger'">
                  {{ role.status === 0 ? '正常' : '禁用' }}
                </el-tag>
              </div>
              <span :title="getRoleStatusDisabledReason(role) || ''">
                <el-switch
                  v-model="role.status"
                  size="small"
                  active-color="var(--admin-primary)"
                  inactive-color="#cccccc"
                  :active-value="0"
                  :inactive-value="1"
                  :loading="switchLoading"
                  :disabled="isAdminRole(role)"
                  :before-change="() => handleStatusChange(role, role.status === 0 ? 1 : 0)"
                />
              </span>
            </div>
            <div class="role-info">
              <div class="info-row">
                <span class="label">角色标识:</span>
                <span class="value role-text">{{ role.role }}</span>
              </div>
              <div class="info-row">
                <span class="label">角色名称:</span>
                <span class="value">{{ role.name }}</span>
              </div>
              <div class="info-row">
                <span class="label">角色描述:</span>
                <span class="value">{{ role.description }}</span>
              </div>
              <div class="info-row">
                <span class="label">创建时间:</span>
                <span class="value time-text">{{ role.createTime }}</span>
              </div>
              <div class="info-row">
                <span class="label">更新时间:</span>
                <span class="value time-text">{{ role.updateTime }}</span>
              </div>
            </div>
            <div class="role-actions">
              <el-button type="primary" size="small" @click="handleEditRole(role)" :icon="Edit" class="edit-button">编辑</el-button>
              <span :title="getRoleDeleteDisabledReason(role) || ''">
                <el-button type="danger" size="small" @click="handleDeleteRole(role)" :icon="Delete" class="delete-button" :disabled="isBuiltInRole(role)">删除</el-button>
              </span>
              <el-button v-if="shouldShowAuthorizeMenu(role)" size="small" type="success" @click="handleAuthorizeMenu(role)" :icon="Key" class="menu-button">分配菜单</el-button>
            </div>
          </div>
        </el-card>
      </div>
    </template>
  </ManagementCard>

  <!-- 新增/编辑角色对话框 -->
  <el-dialog v-model="dialogVisible" :title="dialogTitle" :before-close="handleDialogClose">
    <el-form ref="roleFormRef" :model="roleForm" :rules="rules" class="editForm">
      <el-form-item prop="role" label="角色标识">
        <el-input v-model="roleForm.role" placeholder="请输入角色标识" :disabled="editingBuiltInRole" />
      </el-form-item>
      <el-form-item prop="name" label="角色名称">
        <el-input v-model="roleForm.name" placeholder="请输入角色名称" />
      </el-form-item>
      <el-form-item prop="description" label="角色描述">
        <el-input v-model="roleForm.description" placeholder="请输入角色描述" />
      </el-form-item>
    </el-form>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确认</el-button>
      </span>
    </template>
  </el-dialog>

  <!-- 分配菜单弹窗对话框 -->
  <el-dialog v-model="authorizeMenuDialogVisible" :title="'为 ' + currentMenuRole?.name + ' 分配菜单'" :before-close="handleAuthorizeMenuDialogClose" width="600px">
    <div class="authorize-menu-dialog-content">
      <div class="menu-toolbar">
        <el-button size="small" @click="handleExpandAll(false)">全部收起</el-button>
        <el-button size="small" type="primary" @click="handleExpandAll(true)">全部展开</el-button>
      </div>
      <el-tree
        ref="menuTreeRef"
        :data="menuTreeData"
        :props="menuTreeProps"
        node-key="id"
        :default-expand-all="false"
        :expand-on-click-node="false"
        :check-strictly="false"
        show-checkbox
        :default-checked-keys="selectedMenuIds"
        :default-expanded-keys="defaultExpandedKeys"
        @node-click="handleNodeClick"
        class="menu-tree"
      >
        <template #default="{ node, data }">
          <span class="tree-node">
            <span class="node-icon">
              <el-icon v-if="data.icon"><component :is="data.icon" /></el-icon>
            </span>
            <span class="node-label">{{ node.label }}</span>
          </span>
        </template>
      </el-tree>
    </div>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleAuthorizeMenuDialogClose">取消</el-button>
        <el-button type="primary" @click="handleAuthorizeMenuSubmit">提交</el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup>
import { nextTick, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit, Delete, Key } from '@element-plus/icons-vue'
import { getRolePage, updateRole, deleteRole, queryRolePage } from '@/api/role'
import { getAllMenuList } from '@/api/menu'
import { getMenusByRole, assignMenus } from '@/api/role-menu'

// 组件
import ManagementCard from '@/components/management/ManagementCard.vue'
import KeywordSearch from '@/components/search/KeywordSearch.vue'
import SearchButtons from '@/components/search/SearchButtons.vue'

// 搜索
const searchQuery = ref('')
const BUILT_IN_ROLE_CODES = ['admin', 'content_admin', 'user']

const isBuiltInRole = (role) => BUILT_IN_ROLE_CODES.includes(role?.role)

const isAdminRole = (role) => role?.role === 'admin'
const isContentAdminRole = (role) => role?.role === 'content_admin'
const shouldShowAuthorizeMenu = (role) => isAdminRole(role) || isContentAdminRole(role)

const isSystemManagementMenu = (menu) => {
  const path = menu?.path
  return path === '/system' || (typeof path === 'string' && path.startsWith('/system/'))
}

const getRoleDeleteDisabledReason = (role) => (isBuiltInRole(role) ? '系统内置角色不能删除' : '')

const getRoleStatusDisabledReason = (role) => (isAdminRole(role) ? '超级管理员角色不能停用' : '')

// 角色列表数据
const roleList = ref([])
const paginatedRoleList = ref([])
const loading = ref(false)
const total = ref(0)

// 对话框
const dialogVisible = ref(false)
const dialogTitle = ref('编辑角色')
const editingBuiltInRole = ref(false)

const roleFormRef = ref(null)
const roleForm = ref({
  id: null,
  name: '',
  role: '',
  description: '',
  status: 0,
})

const rules = {
  role: [{ required: true, message: '请输入角色标识', trigger: 'blur' }],
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  description: [{ required: true, message: '请输入角色描述', trigger: 'blur' }],
}

// 当前页码
const currentPage = ref(1)
const pageSize = ref(10)

const hasSearchConditions = () => !!searchQuery.value.trim()

const applyPageData = (pageData) => {
  roleList.value = pageData?.data || []
  paginatedRoleList.value = roleList.value
  total.value = Number(pageData?.total || 0)
}

const fetchRoles = async () => {
  loading.value = true
  try {
    let pageData = null
    if (hasSearchConditions()) {
      const res = await queryRolePage({
        name: searchQuery.value,
        pageNum: currentPage.value,
        pageSize: pageSize.value,
      })
      pageData = res.data
    } else {
      const res = await getRolePage({
        pageNum: currentPage.value,
        pageSize: pageSize.value,
      })
      pageData = res.data
    }
    applyPageData(pageData)
  } catch (error) {
    ElMessage.error(hasSearchConditions() ? '搜索角色失败' : '获取角色列表失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = async () => {
  currentPage.value = 1
  await fetchRoles()
}

const handleReset = () => {
  searchQuery.value = ''
  handleSearch()
}

const handleEditRole = (row) => {
  dialogTitle.value = '编辑角色'
  editingBuiltInRole.value = isBuiltInRole(row)
  roleForm.value = { ...row }
  dialogVisible.value = true
}

const handleDeleteRole = (role) => {
  if (isBuiltInRole(role)) {
    ElMessage.warning(getRoleDeleteDisabledReason(role))
    return
  }

  ElMessageBox.confirm('确定要删除该角色吗？', '警告', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning',
  })
    .then(async () => {
      loading.value = true
      try {
        await deleteRole(role.id)
        ElMessage.success('删除成功')
        fetchRoles()
      } catch (error) {
        ElMessage.error('删除失败')
      } finally {
        loading.value = false
      }
    })
    .catch(() => {
      ElMessage.info('删除已取消')
    })
}

const switchLoading = ref(false)
const handleStatusChange = async (role, status) => {
  if (isAdminRole(role)) {
    ElMessage.warning(getRoleStatusDisabledReason(role))
    return false
  }

  const actionText = status === 0 ? '启用' : '停用'

  try {
    await ElMessageBox.confirm(`确定要${actionText}角色 ${role.name} 吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch (error) {
    ElMessage.info('状态更新已取消')
    return false
  }

  switchLoading.value = true
  try {
    await updateRole({ id: role.id, status })
    ElMessage.success(`${actionText}成功`)
    return true
  } catch (error) {
    ElMessage.error(`${actionText}失败`)
    return false
  } finally {
    switchLoading.value = false
  }
}

const handleSubmit = () => {
  roleFormRef.value.validate(async (valid) => {
    if (!valid) return
    if (!roleForm.value.id) {
      ElMessage.warning('系统角色已固定，不能新增角色')
      return
    }
    try {
      await updateRole(roleForm.value)
      ElMessage.success('编辑角色成功')
      dialogVisible.value = false
      fetchRoles()
    } catch (error) {
      ElMessage.error('编辑角色失败')
      handleDialogClose()
    }
  })
}

const handleDialogClose = () => {
  roleFormRef.value.resetFields()
  editingBuiltInRole.value = false
  dialogVisible.value = false
}

// 分配菜单弹窗
const authorizeMenuDialogVisible = ref(false)
const menuTreeRef = ref(null)
const currentMenuRole = ref(null)
const menuTreeData = ref([])
const menuTreeProps = {
  children: 'children',
  label: 'name',
  disabled: 'disabled',
}
const selectedMenuIds = ref([])
const defaultExpandedKeys = ref([])
const allSystemMenuIds = ref([])
const lockedSystemMenuIds = ref([])

const handleAuthorizeMenu = async (row) => {
  if (row?.role === 'user') {
    ElMessage.warning('普通用户角色不能分配后台菜单')
    return
  }
  if (!shouldShowAuthorizeMenu(row)) {
    ElMessage.warning('后台菜单只能分配给后台管理角色')
    return
  }

  currentMenuRole.value = row
  selectedMenuIds.value = []
  defaultExpandedKeys.value = []
  allSystemMenuIds.value = []
  lockedSystemMenuIds.value = []

  const res = await getAllMenuList()
  const fullMenuTree = buildMenuTree(res.data)
  allSystemMenuIds.value = collectSystemMenuIds(fullMenuTree)

  try {
    const menuRes = await getMenusByRole(row.id)
    const currentMenuIds = menuRes.data || []
    if (isAdminRole(row)) {
      lockedSystemMenuIds.value = [...allSystemMenuIds.value]
      menuTreeData.value = lockSystemMenuTree(fullMenuTree)
      selectedMenuIds.value = Array.from(new Set([...currentMenuIds, ...lockedSystemMenuIds.value]))
    } else {
      menuTreeData.value = filterSystemMenuTree(fullMenuTree)
      const systemMenuIdSet = new Set(allSystemMenuIds.value)
      selectedMenuIds.value = currentMenuIds.filter((menuId) => !systemMenuIdSet.has(menuId))
    }
  } catch (error) {
    console.error('获取角色菜单权限失败:', error)
    if (isAdminRole(row)) {
      lockedSystemMenuIds.value = [...allSystemMenuIds.value]
      menuTreeData.value = lockSystemMenuTree(fullMenuTree)
      selectedMenuIds.value = [...lockedSystemMenuIds.value]
    } else {
      menuTreeData.value = filterSystemMenuTree(fullMenuTree)
    }
  }

  authorizeMenuDialogVisible.value = true
  defaultExpandedKeys.value = collectMenuIds(menuTreeData.value)

  await nextTick()
  menuTreeRef.value?.setCheckedKeys(selectedMenuIds.value)
}

const buildMenuTree = (menus) => {
  const menuMap = new Map()
  const result = []

  menus.forEach((menu) => {
    menuMap.set(menu.id, { ...menu, children: [] })
  })

  menus.forEach((menu) => {
    const menuNode = menuMap.get(menu.id)
    if (menu.parentId === 0) {
      result.push(menuNode)
    } else {
      const parentNode = menuMap.get(menu.parentId)
      if (parentNode) {
        parentNode.children.push(menuNode)
      }
    }
  })

  return result
}

const collectSystemMenuIds = (menus) => {
  const ids = []

  const traverse = (nodes) => {
    nodes.forEach((node) => {
      if (isSystemManagementMenu(node) && node.id != null) {
        ids.push(node.id)
      }
      if (Array.isArray(node.children) && node.children.length > 0) {
        traverse(node.children)
      }
    })
  }

  traverse(menus)
  return ids
}

const collectMenuIds = (menus) => {
  const ids = []

  const traverse = (nodes) => {
    nodes.forEach((node) => {
      if (node.id != null) {
        ids.push(node.id)
      }
      if (Array.isArray(node.children) && node.children.length > 0) {
        traverse(node.children)
      }
    })
  }

  traverse(menus)
  return ids
}

const lockSystemMenuTree = (menus) => {
  return menus.map((menu) => ({
    ...menu,
    disabled: isSystemManagementMenu(menu),
    children: lockSystemMenuTree(menu.children || []),
  }))
}

const filterSystemMenuTree = (menus) => {
  return menus
    .filter((menu) => !isSystemManagementMenu(menu))
    .map((menu) => ({
      ...menu,
      children: filterSystemMenuTree(menu.children || []),
    }))
}

const handleExpandAll = (expand) => {
  const tree = menuTreeRef.value
  if (tree) {
    const nodes = tree.store.nodesMap
    Object.values(nodes).forEach((node) => {
      node.expanded = expand
    })
  }
}

const handleNodeClick = (data) => {}

const handleAuthorizeMenuSubmit = async () => {
  try {
    const checkedKeys = menuTreeRef.value?.getCheckedKeys() || []
    const halfCheckedKeys = menuTreeRef.value?.getHalfCheckedKeys() || []
    let allSelectedKeys = Array.from(new Set([...checkedKeys, ...halfCheckedKeys]))

    if (isAdminRole(currentMenuRole.value)) {
      allSelectedKeys = Array.from(new Set([...allSelectedKeys, ...lockedSystemMenuIds.value]))
    } else {
      const systemMenuIdSet = new Set(allSystemMenuIds.value)
      allSelectedKeys = allSelectedKeys.filter((menuId) => !systemMenuIdSet.has(menuId))
    }

    await assignMenus({
      roleId: currentMenuRole.value.id,
      menuIds: allSelectedKeys,
    })
    ElMessage.success(`已为角色 ${currentMenuRole.value.name} 分配菜单`)
  } catch (error) {
    ElMessage.error(`为角色 ${currentMenuRole.value.name} 分配菜单失败`)
  } finally {
    authorizeMenuDialogVisible.value = false
  }
}

const handleAuthorizeMenuDialogClose = () => {
  authorizeMenuDialogVisible.value = false
  selectedMenuIds.value = []
  menuTreeData.value = []
  defaultExpandedKeys.value = []
  allSystemMenuIds.value = []
  lockedSystemMenuIds.value = []
}

onMounted(() => {
  fetchRoles()
})
</script>

<style lang="scss" scoped>
// 表格包装器
.role-table-wrapper {
  flex: 1;
  overflow: auto;
}

// 表格
.table {
  flex: 1;
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 220px);

  :deep(.el-table__header-wrapper) th {
    font-weight: 600;
    color: var(--text-regular);
  }

  :deep(.el-table__body-wrapper) tr td {
    color: var(--text-muted);
    padding: 12px 0;
  }

  :deep(.el-table__fixed-right) {
    box-shadow: -3px 0 10px var(--shadow-card);
  }

  .table-actions {
    min-height: 30px;
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-wrap: wrap;
    gap: 8px;

    .edit-button {
      background-color: var(--action-audit-bg);
      color: var(--action-audit-color);
      border-color: var(--action-audit-border);
      border-radius: 6px;

      &:hover {
        background-color: var(--action-audit-hover-bg);
        border-color: var(--action-audit-hover-border);
      }
    }

    .delete-button {
      background-color: var(--action-delete-bg);
      color: var(--action-delete-color);
      border-color: var(--action-delete-border);
      border-radius: 6px;

      &:hover {
        background-color: var(--action-delete-hover-bg);
        border-color: var(--action-delete-hover-border);
      }
    }

    .menu-button {
      background-color: var(--action-edit-bg);
      color: var(--action-edit-color);
      border-color: var(--action-edit-border);
      border-radius: 6px;

      &:hover {
        background-color: var(--action-edit-hover-bg);
        border-color: var(--action-edit-hover-border);
      }
    }
  }
}

// 移动端卡片视图
.role-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 10px;

  .role-card {
    transition: all 0.3s ease;
    border-radius: 8px;

    &:hover {
      transform: translateY(-2px);
      box-shadow: 0 4px 12px var(--shadow-hover);
    }

    .role-card-content {
      display: flex;
      flex-direction: column;
      gap: 12px;

      .role-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding-bottom: 12px;
        border-bottom: 1px solid var(--el-border-color-lighter);

        .header-left {
          display: flex;
          align-items: center;
          gap: 8px;

          .role-id {
            font-size: 12px;
            color: var(--text-muted);
            background-color: var(--bg-input);
            padding: 2px 6px;
            border-radius: 4px;
          }
        }
      }

      .role-info {
        display: flex;
        flex-direction: column;
        gap: 8px;

        .info-row {
          display: flex;
          font-size: 14px;

          .label {
            font-weight: 500;
            color: var(--text-muted);
            margin-right: 8px;
            flex-shrink: 0;
          }

          .value {
            color: var(--text-regular);
            flex: 1;
            word-break: break-all;
          }

          .role-text {
            color: var(--admin-primary);
            font-family: 'Courier New', monospace;
          }

          .time-text {
            font-size: 12px;
            color: var(--text-muted);
          }
        }
      }

      .role-actions {
        display: flex;
        gap: 8px;
        justify-content: center;
        padding-top: 12px;
        border-top: 1px solid var(--el-border-color-lighter);
        flex-wrap: wrap;

        .el-button {
          margin-left: 0;
          flex: 1;
          min-width: 70px;
        }

        .edit-button {
          background-color: var(--action-audit-bg);
          color: var(--action-audit-color);
          border-color: var(--action-audit-border);

          &:hover {
            background-color: var(--action-audit-hover-bg);
            border-color: var(--action-audit-hover-border);
          }
        }

        .delete-button {
          background-color: var(--action-delete-bg);
          color: var(--action-delete-color);
          border-color: var(--action-delete-border);

          &:hover {
            background-color: var(--action-delete-hover-bg);
            border-color: var(--action-delete-hover-border);
          }
        }

        .menu-button {
          background-color: var(--action-edit-bg);
          color: var(--action-edit-color);
          border-color: var(--action-edit-border);

          &:hover {
            background-color: var(--action-edit-hover-bg);
            border-color: var(--action-edit-hover-border);
          }
        }
      }
    }
  }
}

// 对话框样式
:deep(.el-dialog) {
  border-radius: 16px;
  width: fit-content;
  max-width: 90%;
}

.editForm {
  :deep(.el-form-item) {
    margin-bottom: 20px;
  }

  :deep(.el-input__wrapper),
  :deep(.el-select__wrapper) {
    border-radius: 16px;

    &:focus-within {
      box-shadow: 0 0 0 3px var(--admin-primary-light);
      border-color: var(--admin-primary);
    }
  }
}

.authorize-menu-dialog-content {
  padding: 10px;

  .menu-toolbar {
    display: flex;
    gap: 12px;
    margin-bottom: 16px;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  .menu-tree {
    max-height: 400px;
    overflow-y: auto;

    :deep(.el-tree-node__content) {
      height: 36px;
    }

    .tree-node {
      display: flex;
      align-items: center;
      gap: 8px;

      .node-icon {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 24px;
        height: 24px;
        border-radius: 4px;
        background: linear-gradient(135deg, var(--admin-primary) 0%, var(--admin-primary-dark) 100%);
        color: var(--badge-text-inverse);
        font-size: 14px;
      }

      .node-label {
        font-size: 14px;
        color: var(--el-text-color-regular);
      }
    }
  }
}

.role-page-tip {
  margin-bottom: 12px;
  padding: 10px 14px;
  border-radius: 8px;
  background: var(--bg-input);
  color: var(--text-muted);
  font-size: 14px;
}

// 响应式
@media screen and (max-width: 768px) {
  .role-table-wrapper {
    max-height: calc(100vh - 180px);
  }
}
</style>
