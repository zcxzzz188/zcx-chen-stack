<template>
  <ManagementCard title="权限概览" :showTimeFilter="false" :showPagination="true" v-model:model-current-page="currentPage" v-model:model-page-size="pageSize" :total="total" @search="fetchPermissions">
    <template #filters>
      <p class="permission-overview-hint">权限标识与后端鉴权代码绑定，本页面仅用于查看。</p>
      <KeywordSearch v-model="searchDescription" placeholder="搜索权限描述" :debounce="500" @search="handleSearch" />
      <KeywordSearch v-model="searchPermission" placeholder="搜索权限标识" :debounce="0" @search="handleSearch" />
      <CommonSelect v-model="searchMenuId" :options="menuList" option-label="name" option-value="id" label="菜单名称" placeholder="请选择菜单" width="160px" @change="handleSearch" />
      <SearchButtons @search="handleSearch" @reset="handleReset" />
      <el-button type="primary" size="small" @click="exportPermission" :icon="Download" class="export-button">导出</el-button>
    </template>

    <template #table-view>
      <div v-loading="loading" class="permission-table-wrapper">
        <el-table :data="paginatedPermissionList" class="table" style="height: 100%">
          <el-table-column prop="id" label="权限id" width="80" />
          <el-table-column prop="description" label="权限描述" min-width="180" />
          <el-table-column prop="permission" label="权限标识" min-width="220" />
          <el-table-column prop="menuName" label="菜单名称" min-width="180">
            <template #default="{ row }">
              <el-tag>
                <el-icon v-if="row.icon"><component :is="row.icon" /></el-icon>
                <span>{{ row.menuName || '未绑定菜单' }}</span>
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" sortable width="170" />
          <el-table-column prop="updateTime" label="更新时间" sortable width="170" />
        </el-table>
      </div>
    </template>

    <template #card-view>
      <div v-loading="loading" class="permission-cards">
        <el-card v-for="permission in paginatedPermissionList" :key="permission.id" class="permission-card">
          <div class="permission-card-content">
            <div class="permission-header">
              <div class="permission-id">#{{ permission.id }}</div>
              <el-tag>
                <el-icon v-if="permission.icon"><component :is="permission.icon" /></el-icon>
                <span>{{ permission.menuName || '未绑定菜单' }}</span>
              </el-tag>
            </div>

            <div class="permission-info">
              <div class="info-row">
                <span class="label">权限描述:</span>
                <span class="value">{{ permission.description }}</span>
              </div>
              <div class="info-row">
                <span class="label">权限标识:</span>
                <span class="value permission-text">{{ permission.permission }}</span>
              </div>
              <div class="info-row">
                <span class="label">创建时间:</span>
                <span class="value time-text">{{ permission.createTime }}</span>
              </div>
              <div class="info-row">
                <span class="label">更新时间:</span>
                <span class="value time-text">{{ permission.updateTime }}</span>
              </div>
            </div>
          </div>
        </el-card>
      </div>
    </template>
  </ManagementCard>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Download } from '@element-plus/icons-vue'
import { getPermissionList, getPermissionPage, queryPermissionPage } from '@/api/permission'
import { getAllMenuList } from '@/api/menu'

import ManagementCard from '@/components/management/ManagementCard.vue'
import KeywordSearch from '@/components/search/KeywordSearch.vue'
import SearchButtons from '@/components/search/SearchButtons.vue'
import CommonSelect from '@/components/search/CommonSelect.vue'

import FileSaver from 'file-saver'
import * as XLSX from 'xlsx'

const permissionList = ref([])
const paginatedPermissionList = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const menuList = ref([])

const searchDescription = ref('')
const searchPermission = ref('')
const searchMenuId = ref('')

const exportPermission = async () => {
  const res = await getPermissionList()
  const fullPermissionList = res.data || []
  const data = fullPermissionList.map((item) => ({
    权限id: item.id,
    权限描述: item.description,
    权限标识: item.permission,
    菜单名称: item.menuName,
    创建时间: item.createTime,
    更新时间: item.updateTime,
  }))

  const ws = XLSX.utils.json_to_sheet(data)
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '权限列表')

  const wbout = XLSX.write(wb, { bookType: 'xlsx', type: 'array' })
  FileSaver.saveAs(new Blob([wbout], { type: 'application/octet-stream' }), '权限列表.xlsx')
}

const getMenuList = async () => {
  try {
    const res = await getAllMenuList()
    menuList.value = res.data.data
  } catch (error) {
    ElMessage.error('获取菜单列表失败')
    console.error('获取菜单列表失败:', error)
  }
}

const hasSearchConditions = () => !!(searchDescription.value || searchPermission.value || searchMenuId.value)

const buildSearchPayload = () => ({
  pageNum: currentPage.value,
  pageSize: pageSize.value,
  description: searchDescription.value || undefined,
  permission: searchPermission.value || undefined,
  menuId: searchMenuId.value || undefined,
})

const applyPageData = (pageData) => {
  permissionList.value = pageData?.data || []
  paginatedPermissionList.value = permissionList.value
  total.value = Number(pageData?.total || 0)
}

const fetchPermissions = async () => {
  loading.value = true
  try {
    let pageData = null
    if (hasSearchConditions()) {
      const res = await queryPermissionPage(buildSearchPayload())
      pageData = res.data
    } else {
      const res = await getPermissionPage({
        pageNum: currentPage.value,
        pageSize: pageSize.value,
      })
      pageData = res.data
    }
    applyPageData(pageData)
  } catch (error) {
    ElMessage.error(hasSearchConditions() ? '搜索权限失败' : '获取权限列表失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = async () => {
  currentPage.value = 1
  await fetchPermissions()
}

const handleReset = async () => {
  searchDescription.value = ''
  searchPermission.value = ''
  searchMenuId.value = ''
  await handleSearch()
}

onMounted(() => {
  fetchPermissions()
  getMenuList()
})
</script>

<style lang="scss" scoped>
.permission-table-wrapper {
  flex: 1;
  overflow: auto;
}

.permission-overview-hint {
  width: 100%;
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--el-color-warning);
}

.table {
  flex: 1;
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 220px);

  :deep(.el-tag__content) {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  :deep(.el-table__header-wrapper) th {
    font-weight: 600;
    color: var(--text-regular);
  }

  :deep(.el-table__body-wrapper) tr td {
    color: var(--text-muted);
    padding: 12px 0;
  }
}

.permission-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 10px;

  .permission-card {
    transition: all 0.3s ease;
    border-radius: 8px;

    &:hover {
      transform: translateY(-2px);
      box-shadow: 0 4px 12px var(--shadow-hover);
    }

    .permission-card-content {
      display: flex;
      flex-direction: column;
      gap: 12px;

      .permission-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 8px;
        padding-bottom: 12px;
        border-bottom: 1px solid var(--el-border-color-lighter);

        .permission-id {
          font-size: 12px;
          color: var(--text-muted);
          background-color: var(--bg-input);
          padding: 2px 6px;
          border-radius: 4px;
        }

        :deep(.el-tag) {
          display: flex;
          align-items: center;
          gap: 4px;
        }
      }

      .permission-info {
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

          .permission-text {
            color: var(--admin-primary);
            font-family: 'Courier New', monospace;
          }

          .time-text {
            font-size: 12px;
            color: var(--text-muted);
          }
        }
      }
    }
  }
}

.export-button {
  background-color: var(--action-audit-bg);
  color: var(--action-audit-color);
  border-color: var(--action-audit-border);
  border-radius: 6px;

  &:hover {
    background-color: var(--action-audit-hover-bg);
    border-color: var(--action-audit-hover-border);
  }
}

@media screen and (max-width: 768px) {
  .permission-table-wrapper {
    max-height: calc(100vh - 180px);
  }
}
</style>
