<template>
  <ManagementCard title="菜单管理" :showTimeFilter="false" :showPagination="true" v-model:model-current-page="currentPage" v-model:model-page-size="pageSize" :total="total" @search="fetchMenus">
    <!-- 筛选器 -->
    <template #filters>
      <KeywordSearch v-model="searchQuery" placeholder="搜索菜单名称" :debounce="500" @search="handleSearch" />
      <SearchButtons @search="handleSearch" @reset="handleReset" />
    </template>

    <!-- 桌面端表格视图 -->
    <template #table-view>
      <div v-loading="loading" class="menu-table-wrapper">
        <el-table :data="paginatedMenuList" row-key="id" default-expand-all class="menu-table" style="height: 100%">
          <el-table-column prop="id" label="菜单id" width="120" />
          <el-table-column prop="parentId" label="父菜单id" />
          <el-table-column prop="name" label="菜单名称" />
          <el-table-column prop="path" label="路由路径" />
          <el-table-column prop="component" label="组件路径" />
          <el-table-column prop="icon" label="图标">
            <template #default="{ row }">
              <el-icon v-if="row.icon"><component :is="row.icon" /></el-icon>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="sort" sortable label="排序" />
          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <span :title="getStatusDisabledReason(row) || ''">
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
                  :disabled="isSystemManagementMenu(row)"
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
                <span v-if="row.children || row.parentId == 0" :title="getAddDisabledReason(row) || ''">
                  <el-button size="small" type="success" @click="handleAddMenu(row)" :icon="Plus" class="add-button" :disabled="isSystemManagementMenu(row)"> 新增 </el-button>
                </span>
                <el-button size="small" type="primary" @click="handleEditMenu(row)" :icon="Edit" class="edit-button"> 编辑 </el-button>
                <span :title="getDeleteDisabledReason(row) || ''">
                  <el-button size="small" type="danger" @click="handleDeleteMenu(row)" :icon="Delete" class="delete-button" :disabled="isSystemManagementMenu(row)"> 删除 </el-button>
                </span>
                <el-button size="small" type="warning" @click="handleAuthorizeRole(row)" :icon="Avatar" class="role-button"> 分配角色 </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- 移动端卡片视图 -->
    <template #card-view>
      <div class="menu-cards" v-loading="loading">
        <div v-for="menu in flatMenuList" :key="menu.id" class="menu-card" :style="{ marginLeft: menu.level * 16 + 'px' }">
          <div class="menu-card-header">
            <div v-if="menu.hasChildren" class="expand-button" @click="toggleMenuExpand(menu.id)">
              <el-icon :class="['expand-icon', { expanded: isMenuExpanded(menu.id) }]">
                <ArrowRight />
              </el-icon>
            </div>
            <div class="menu-icon-wrapper">
              <el-icon v-if="menu.icon" class="menu-icon"><component :is="menu.icon" /></el-icon>
              <el-icon v-else class="menu-icon placeholder"><Document /></el-icon>
            </div>
            <div class="menu-main-info">
              <div class="menu-id-badge">ID: {{ menu.id }}</div>
              <div class="menu-name">{{ menu.name }}</div>
              <div v-if="menu.path" class="menu-path">{{ menu.path }}</div>
            </div>
          </div>
          <div class="menu-details">
            <div v-if="menu.parentId !== undefined" class="detail-item">
              <span class="label">父菜单ID:</span>
              <span class="value">{{ menu.parentId === 0 ? '无' : menu.parentId }}</span>
            </div>
            <div v-if="menu.component" class="detail-item">
              <span class="label">组件路径:</span>
              <span class="value">{{ menu.component }}</span>
            </div>
            <div class="detail-item">
              <span class="label">排序:</span>
              <span class="value">{{ menu.sort }}</span>
            </div>
            <div class="detail-item">
              <span class="label">创建时间:</span>
              <span class="value">{{ menu.createTime }}</span>
            </div>
            <div class="detail-item">
              <span class="label">更新时间:</span>
              <span class="value">{{ menu.updateTime }}</span>
            </div>
          </div>
          <div class="menu-status-section">
            <span :title="getStatusDisabledReason(menu) || ''">
              <el-switch
                v-model="menu.status"
                size="large"
                active-color="var(--admin-primary)"
                inactive-color="#cccccc"
                active-text="正常"
                inactive-text="禁用"
                :active-value="0"
                :inactive-value="1"
                inline-prompt
                :loading="switchLoading"
                :disabled="isSystemManagementMenu(menu)"
                :before-change="() => handleStatusChange(menu, menu.status === 0 ? 1 : 0)"
              />
            </span>
          </div>
          <div class="menu-actions">
            <span v-if="menu.hasChildren || menu.parentId == 0" :title="getAddDisabledReason(menu) || ''">
              <el-button text bg type="success" size="small" :icon="Plus" @click="handleAddMenu(menu)" :disabled="isSystemManagementMenu(menu)">新增</el-button>
            </span>
            <el-button text bg type="primary" size="small" :icon="Edit" @click="handleEditMenu(menu)">编辑</el-button>
            <span :title="getDeleteDisabledReason(menu) || ''">
              <el-button text bg type="danger" size="small" :icon="Delete" @click="handleDeleteMenu(menu)" :disabled="isSystemManagementMenu(menu)">删除</el-button>
            </span>
            <el-button text bg type="warning" size="small" :icon="Avatar" @click="handleAuthorizeRole(menu)">角色</el-button>
          </div>
        </div>
      </div>
    </template>
  </ManagementCard>

  <!-- 新增/编辑菜单对话框 -->
  <el-dialog v-model="dialogVisible" :title="dialogTitle" :before-close="handleDialogClose">
    <el-form ref="menuFormRef" :model="menuForm" :rules="rules" class="menu-form">
      <el-form-item prop="name" label="菜单名称">
        <el-input v-model="menuForm.name" placeholder="请输入菜单名称" />
      </el-form-item>
      <el-form-item prop="path" label="路由路径">
        <el-input v-model="menuForm.path" placeholder="请输入路由路径" :disabled="editingSystemMenu" />
      </el-form-item>
      <el-form-item prop="component" label="组件路径">
        <el-input v-model="menuForm.component" placeholder="请输入组件路径" :disabled="editingSystemMenu" />
      </el-form-item>
      <el-form-item prop="icon" label="图标">
        <el-select v-model="menuForm.icon" placeholder="请选择图标" filterable clearable>
          <el-option v-for="icon in icons" :key="icon" :label="icon" :value="icon">
            <div style="display: flex; align-items: center">
              <el-icon style="margin-right: 10px"><component :is="icon" /></el-icon>
              <span>{{ icon }}</span>
            </div>
          </el-option>
          <template #label>
            <div style="display: flex; align-items: center">
              <el-icon style="margin-right: 10px"><component :is="menuForm.icon" /></el-icon>
              <span>{{ menuForm.icon }}</span>
            </div>
          </template>
        </el-select>
      </el-form-item>
      <el-form-item prop="parentId" label="父菜单">
        <CommonSelect v-model="menuForm.parentId" :options="[{ id: 0, name: '无父菜单' }, ...availableParentMenus]" option-label="name" option-value="id" placeholder="请选择父菜单" width="100%" :disabled="editingSystemMenu" />
      </el-form-item>
      <el-form-item prop="sort"> 排序号 &nbsp<el-input-number v-model="menuForm.sort" :min="0" :max="999" placeholder="请输入排序号" /> </el-form-item>
      <p v-if="editingSystemMenu" class="menu-form-hint">系统管理菜单的路由、组件和层级受保护</p>
      <p v-else class="menu-form-hint">`/system` 路径保留给系统管理菜单使用</p>
    </el-form>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleDialogClose">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确认</el-button>
      </span>
    </template>
  </el-dialog>

  <!-- 分配角色对话框 -->
  <el-dialog v-model="authorizeDialogVisible" title="菜单分配角色" :before-close="handleAuthorizeDialogClose" class="authorize-dialog">
    <div v-loading="authorizeLoading" class="authorize-dialog-content">
      <p class="menu-name">当前菜单: {{ currentMenu?.name }}</p>
      <template v-if="!authorizeLoading">
        <el-form ref="authorizeFormRef" class="authorize-form">
          <el-form-item label="选择角色">
            <p v-if="isAuthorizingSystemMenu" class="authorize-hint">系统管理菜单只能分配给超级管理员</p>
            <p v-if="isAuthorizingSystemMenu && !systemMenuAdminRole" class="authorize-hint is-error">未找到超级管理员角色</p>
            <el-checkbox-group v-model="selectedRoles" class="role-checkbox-group">
              <el-checkbox v-for="role in allRoles" :key="role.id" :label="role.id" :disabled="isAuthorizeRoleDisabled(role)">{{ role.name }}</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
        </el-form>
      </template>
    </div>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleAuthorizeDialogClose">取消</el-button>
        <el-button type="primary" @click="handleAuthorizeSubmit" :disabled="authorizeSubmitDisabled">确认分配</el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, computed } from 'vue'
import { Plus, Edit, Delete, Avatar, Document, ArrowRight } from '@element-plus/icons-vue'
import { getAllMenuList, getMenuPage, addMenu, updateMenu, deleteMenu, queryMenuPage } from '@/api/menu'
import { addRoleMenu, getRolesByMenu } from '@/api/role-menu'
import { getRoleList } from '@/api/role'
import { icons } from '@/utils/Icon'
import { formatMenu } from '@/utils/Menu'

// 组件
import ManagementCard from '@/components/management/ManagementCard.vue'
import KeywordSearch from '@/components/search/KeywordSearch.vue'
import SearchButtons from '@/components/search/SearchButtons.vue'
import CommonSelect from '@/components/search/CommonSelect.vue'

// 搜索
const searchQuery = ref('')
const editingSystemMenu = ref(false)

const isSystemManagementMenu = (menu) => {
  const path = menu?.path
  return path === '/system' || (typeof path === 'string' && path.startsWith('/system/'))
}

const getStatusDisabledReason = (menu) => (isSystemManagementMenu(menu) ? '系统管理菜单不能停用' : '')

const getAddDisabledReason = (menu) => (isSystemManagementMenu(menu) ? '系统管理菜单结构受保护' : '')

const getDeleteDisabledReason = (menu) => (isSystemManagementMenu(menu) ? '系统管理菜单不能删除' : '')

// 移动端检测
const isMobileView = ref(false)
const expandedMenus = ref(new Set())

const toggleMenuExpand = (menuId) => {
  if (expandedMenus.value.has(menuId)) {
    expandedMenus.value.delete(menuId)
  } else {
    expandedMenus.value.add(menuId)
  }
  expandedMenus.value = new Set(expandedMenus.value)
}

const isMenuExpanded = (menuId) => {
  return expandedMenus.value.has(menuId)
}

// 菜单列表数据
const menuList = ref([])
const paginatedMenuList = ref([])
const parentMenuOptions = ref([])
const loading = ref(false)
const total = ref(0)

// 对话框
const dialogVisible = ref(false)
const dialogTitle = ref('新增菜单')

const menuFormRef = ref(null)
const authorizeFormRef = ref(null)
const menuForm = ref({
  id: null,
  name: '',
  path: '',
  component: '',
  icon: '',
  parentId: 0,
  sort: 0,
  status: 0,
})

const rules = {
  parentId: [{ required: true, message: '请选择父菜单', trigger: 'change' }],
  name: [{ required: true, message: '请输入菜单名称', trigger: 'blur' }],
  path: [{ required: true, message: '请输入路由路径', trigger: 'blur' }],
  component: [{ required: true, message: '请输入组件路径', trigger: 'blur' }],
  sort: [
    { required: true, message: '请输入排序号', trigger: 'blur' },
    { type: 'number', message: '排序号必须是数字', trigger: 'blur' },
  ],
}

// 当前页码
const currentPage = ref(1)
const pageSize = ref(10)

const hasSearchConditions = () => !!searchQuery.value.trim()

const applyPageData = (pageData) => {
  menuList.value = formatMenu(pageData?.data || [])
  paginatedMenuList.value = menuList.value
  total.value = Number(pageData?.total || 0)
}

const fetchMenus = async () => {
  loading.value = true
  try {
    let pageData = null
    if (hasSearchConditions()) {
      const res = await queryMenuPage({
        name: searchQuery.value,
        pageNum: currentPage.value,
        pageSize: pageSize.value,
      })
      pageData = res.data
    } else {
      const res = await getMenuPage({
        pageNum: currentPage.value,
        pageSize: pageSize.value,
      })
      pageData = res.data
    }
    applyPageData(pageData)
  } catch (error) {
    ElMessage.error(hasSearchConditions() ? '搜索菜单失败' : '获取菜单列表失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = async () => {
  currentPage.value = 1
  await fetchMenus()
}

const handleReset = () => {
  searchQuery.value = ''
  handleSearch()
}

const allMenus = ref([])

const availableParentMenus = computed(() => allMenus.value.filter((menu) => !isSystemManagementMenu(menu)))

const getAllMenus = async () => {
  try {
    const res = await getAllMenuList()
    allMenus.value = res.data
  } catch (error) {
    ElMessage.error('获取所有菜单失败')
  }
}

const handleAddMenu = async (row) => {
  if (row && isSystemManagementMenu(row)) {
    ElMessage.warning('系统管理菜单结构只能通过代码或初始化脚本调整')
    return
  }

  await getAllMenus()
  editingSystemMenu.value = false

  if (row && row.id) {
    dialogTitle.value = '新增菜单'
    menuForm.value = {
      id: null,
      name: '',
      path: '',
      component: '',
      icon: '',
      parentId: row.id,
      sort: 0,
      status: 0,
    }
    dialogVisible.value = true
  } else {
    const topLevelMenus = allMenus.value.filter((menu) => menu.parentId === 0)
    const maxSort = topLevelMenus.length > 0 ? topLevelMenus.reduce((max, menu) => (menu.sort > max ? menu.sort : max), 0) : 0

    dialogTitle.value = '新增菜单'
    menuForm.value = {
      id: null,
      name: '',
      path: '',
      component: '',
      icon: '',
      parentId: 0,
      sort: maxSort + 1,
      status: 0,
    }
    dialogVisible.value = true
  }
}

const handleEditMenu = (row) => {
  getAllMenus()
  dialogTitle.value = '编辑菜单'
  editingSystemMenu.value = isSystemManagementMenu(row)
  menuForm.value = { ...row }
  dialogVisible.value = true
}

const handleDeleteMenu = (menu) => {
  if (isSystemManagementMenu(menu)) {
    ElMessage.warning('系统管理菜单不能删除')
    return
  }

  ElMessageBox.confirm('确定要删除该菜单吗？', '警告', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning',
  })
    .then(async () => {
      loading.value = true
      try {
        await deleteMenu(menu.id)
        ElMessage.success('删除成功')
        fetchMenus()
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
const handleStatusChange = async (menu, status) => {
  if (isSystemManagementMenu(menu)) {
    ElMessage.warning('系统管理菜单不能停用')
    return false
  }

  const actionText = status === 0 ? '启用' : '停用'

  try {
    await ElMessageBox.confirm(`确定要${actionText}菜单 ${menu.name} 吗？`, '提示', {
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
    await updateMenu({ id: menu.id, status })
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
  menuFormRef.value.validate(async (valid) => {
    if (!valid) return

    if (!menuForm.value.id && isSystemManagementMenu(menuForm.value)) {
      ElMessage.warning('普通菜单不能使用系统管理路径')
      return
    }

    try {
      if (menuForm.value.id) {
        await updateMenu(menuForm.value)
        ElMessage.success('编辑菜单成功')
      } else {
        await addMenu(menuForm.value)
        ElMessage.success('新增菜单成功')
      }
      dialogVisible.value = false
      fetchMenus()
    } catch (error) {
      ElMessage.error(menuForm.value.id ? '编辑菜单失败' : '新增菜单失败')
      handleDialogClose()
    }
  })
}

const handleDialogClose = () => {
  menuFormRef.value.resetFields()
  editingSystemMenu.value = false
  dialogVisible.value = false
}

// 授权角色弹窗
const authorizeDialogVisible = ref(false)
const currentMenu = ref(null)
const selectedRoles = ref([])
const allRoles = ref([])
const authorizeLoading = ref(false)
const BACKEND_MENU_ROLE_CODES = ['admin', 'content_admin']
const isAuthorizingSystemMenu = computed(() => isSystemManagementMenu(currentMenu.value))
const systemMenuAdminRole = computed(() => allRoles.value.find((role) => role?.role === 'admin') || null)
const authorizeSubmitDisabled = computed(() => isAuthorizingSystemMenu.value && !systemMenuAdminRole.value)

const filterBackendMenuRoles = (roles = []) => roles.filter((role) => BACKEND_MENU_ROLE_CODES.includes(role?.role))

const getSystemMenuRoleIds = (roles = allRoles.value) => {
  const adminRole = roles.find((role) => role?.role === 'admin')
  return adminRole ? [adminRole.id] : []
}

const getInitialSelectedRoles = (menu, menuRoles, roles = allRoles.value) => {
  const visibleRoleIds = new Set(roles.map((role) => role.id))
  if (isSystemManagementMenu(menu)) {
    return getSystemMenuRoleIds(roles)
  }
  return menuRoles.filter((item) => visibleRoleIds.has(item.id)).map((item) => item.id)
}

const isAuthorizeRoleDisabled = (role) => {
  if (!isAuthorizingSystemMenu.value) {
    return false
  }
  return true
}

watch(
  [authorizeDialogVisible, isAuthorizingSystemMenu, systemMenuAdminRole],
  ([visible, isSystemMenu]) => {
    if (!visible || !isSystemMenu) {
      return
    }
    selectedRoles.value = getSystemMenuRoleIds()
  },
  { immediate: false }
)

const handleAuthorizeRole = async (row) => {
  currentMenu.value = row
  selectedRoles.value = []
  allRoles.value = []

  authorizeDialogVisible.value = true
  authorizeLoading.value = true

  try {
    const [roleRes, menuRolesRes] = await Promise.all([getRoleList(), getRolesByMenu(row.id)])
    allRoles.value = filterBackendMenuRoles(roleRes.data || [])
    selectedRoles.value = getInitialSelectedRoles(row, menuRolesRes.data || [], allRoles.value)

    if (isSystemManagementMenu(row) && !systemMenuAdminRole.value) {
      ElMessage.warning('未找到超级管理员角色')
    }
  } catch (error) {
    ElMessage.error('获取角色列表失败')
  } finally {
    authorizeLoading.value = false
  }
}

const handleAuthorizeSubmit = async () => {
  const allowedRoleIds = new Set(allRoles.value.map((role) => role.id))
  const roleIds = isAuthorizingSystemMenu.value
    ? getSystemMenuRoleIds()
    : selectedRoles.value.filter((roleId) => allowedRoleIds.has(roleId))

  if (isAuthorizingSystemMenu.value && roleIds.length === 0) {
    ElMessage.warning('未找到超级管理员角色')
    return
  }

  try {
    await addRoleMenu({
      menuId: currentMenu.value.id,
      roleIds,
    })
    ElMessage.success(`已为菜单 ${currentMenu.value.name} 授权角色`)
  } catch (error) {
    ElMessage.error(`为菜单 ${currentMenu.value.name} 授权角色失败`)
  } finally {
    authorizeDialogVisible.value = false
    selectedRoles.value = []
    allRoles.value = []
  }
}

const handleAuthorizeDialogClose = () => {
  authorizeDialogVisible.value = false
  selectedRoles.value = []
  allRoles.value = []
  currentMenu.value = null
}

// 扁平化的菜单列表（用于移动端显示）
const flatMenuList = computed(() => {
  const flattenMenus = (menus, level = 0, result = []) => {
    menus.forEach((menu) => {
      const flatMenu = { ...menu, level, hasChildren: menu.children && menu.children.length > 0 }
      result.push(flatMenu)
      if (flatMenu.hasChildren && isMenuExpanded(menu.id)) {
        flattenMenus(menu.children, level + 1, result)
      }
    })
    return result
  }
  return flattenMenus(paginatedMenuList.value)
})

const handleResize = () => {
  isMobileView.value = window.innerWidth <= 768
}

onMounted(() => {
  fetchMenus()
  handleResize()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
})
</script>

<style lang="scss" scoped>
// 菜单表格包装器
.menu-table-wrapper {
  flex: 1;
  overflow: auto;
}

// 菜单表格
.menu-table {
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
    box-shadow: var(--shadow-card);
  }

  .table-actions {
    min-height: 30px;
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-wrap: wrap;
    gap: 8px;

    .add-button {
      background-color: var(--action-edit-bg);
      color: var(--action-edit-color);
      border-color: var(--action-edit-border);
      border-radius: 6px;

      &:hover {
        background-color: var(--action-edit-hover-bg);
        border-color: var(--action-edit-hover-border);
      }
    }

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

    .role-button {
      background-color: var(--action-user-bg);
      color: var(--action-user-color);
      border-color: var(--action-user-border);
      border-radius: 6px;

      &:hover {
        background-color: var(--action-user-hover-bg);
        border-color: var(--action-user-hover-border);
      }
    }
  }
}

// 移动端卡片视图
.menu-cards {
  display: flex;
  flex-direction: column;
  gap: 0;
  padding: 10px;

  .menu-card {
    background: var(--el-bg-color);
    border-radius: 8px;
    border: 1px solid var(--el-border-color-lighter);
    overflow: hidden;
    box-shadow: var(--shadow-card);
    transition: all 0.3s ease;
    margin-bottom: 8px;

    &:hover {
      transform: translateY(-2px);
      box-shadow: var(--shadow-hover);
    }

    .menu-card-header {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 16px;
      background-color: var(--el-bg-color-page);
      border-bottom: 1px solid var(--el-border-color-lighter);

      .expand-button {
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
        width: 32px;
        height: 32px;
        border-radius: 6px;
        background-color: var(--el-fill-color-light);
        flex-shrink: 0;

        &:hover {
          background-color: var(--admin-primary-lighter);
        }

        .expand-icon {
          font-size: 18px;
          color: var(--text-light);
          transition: transform 0.3s ease;

          &.expanded {
            transform: rotate(90deg);
          }
        }
      }

      .menu-icon-wrapper {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 48px;
        height: 48px;
        border-radius: 12px;
        background: linear-gradient(135deg, var(--admin-primary) 0%, var(--admin-primary-dark) 100%);

        .menu-icon {
          font-size: 24px;
          color: white;

          &.placeholder {
            opacity: 0.6;
          }
        }
      }

      .menu-main-info {
        flex: 1;
        min-width: 0;

        .menu-id-badge {
          display: inline-block;
          padding: 2px 8px;
          background-color: var(--bg-blue-light);
          color: var(--text-article-link);
          border-radius: 12px;
          font-size: 10px;
          font-weight: 600;
          margin-bottom: 4px;
        }

        .menu-name {
          font-size: 16px;
          font-weight: 600;
          color: var(--el-text-color-primary);
          margin-bottom: 4px;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .menu-path {
          font-size: 12px;
          color: var(--el-text-color-secondary);
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
      }
    }

    .menu-details {
      padding: 12px 16px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      background-color: var(--el-bg-color);

      .detail-item {
        display: flex;
        align-items: flex-start;
        font-size: 12px;
        line-height: 1.5;

        .label {
          flex-shrink: 0;
          width: 80px;
          color: var(--el-text-color-secondary);
          font-weight: 500;
        }

        .value {
          flex: 1;
          color: var(--el-text-color-primary);
          word-break: break-all;
        }
      }
    }

    .menu-status-section {
      display: flex;
      justify-content: center;
      border-top: 1px solid var(--el-border-color-lighter);
      border-bottom: 1px solid var(--el-border-color-lighter);
      background-color: var(--el-bg-color);
    }

    .menu-actions {
      display: flex;
      gap: 6px;
      padding: 12px 16px;
      justify-content: space-between;
      background-color: var(--el-bg-color);

      .el-button {
        font-size: 12px;
        padding: 6px 10px;
        height: auto;
        border-radius: 4px;
        flex: 1;
        min-width: 0;
      }
    }
  }
}

// 对话框样式
:deep(.el-dialog) {
  border-radius: 16px;
  width: 500px;

  @media screen and (max-width: 767px) {
    width: 90%;
  }
}

.menu-form {
  :deep(.el-form-item) {
    margin-bottom: 20px;
  }
}

.authorize-dialog-content {
  padding: 10px;

  .menu-name {
    font-size: 16px;
    font-weight: 600;
    margin-bottom: 16px;
    color: var(--text-primary);
  }

  .authorize-hint {
    margin: 0 0 12px;
    font-size: 13px;
    color: var(--el-color-warning);

    &.is-error {
      color: var(--el-color-danger);
    }
  }

  .role-checkbox-group {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;

    :deep(.el-checkbox) {
      margin-right: 16px;
      margin-bottom: 8px;
    }
  }
}

// 响应式设计
@media screen and (max-width: 768px) {
  .menu-table-wrapper {
    max-height: calc(100vh - 200px);
  }
}
</style>
