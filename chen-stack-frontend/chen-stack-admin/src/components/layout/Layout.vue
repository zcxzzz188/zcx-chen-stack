<template>
  <el-container class="layout-container">
    <!-- 侧边栏 -->
    <el-aside class="sidebar">
      <div class="logo-container">
        <div class="logo-icon"></div>
        <h3 class="logo-text">管理员后台</h3>
      </div>
      <div class="sidebar-divider"></div>
      <!-- pc端菜单 -->
      <el-menu :default-active="activeMenu" class="el-menu-pc" :background-color="sidebarBg" :text-color="sidebarText" :active-text-color="sidebarActiveText" :router="true">
        <template v-for="menu in menus" :key="menu.id">
          <el-sub-menu v-if="menu.children && menu.children.length > 0" :index="menu.path">
            <template #title>
              <el-icon :size="18"><component :is="menu.icon || 'Menu'" /></el-icon>
              <span>{{ menu.name }}</span>
            </template>
            <template v-for="child in menu.children" :key="child.id">
              <el-sub-menu v-if="child.children && child.children.length > 0" :index="child.path">
                <template #title>
                  <el-icon :size="18"><component :is="child.icon || 'Menu'" /></el-icon>
                  <span>{{ child.name }}</span>
                </template>
                <template v-for="grandchild in child.children" :key="grandchild.id">
                  <el-menu-item :index="grandchild.path">
                    <el-icon :size="16"><component :is="grandchild.icon || 'Menu'" /></el-icon>
                    <span>{{ grandchild.name }}</span>
                  </el-menu-item>
                </template>
              </el-sub-menu>
              <el-menu-item v-else :index="child.path">
                <el-icon :size="18"><component :is="child.icon || 'Menu'" /></el-icon>
                <span>{{ child.name }}</span>
              </el-menu-item>
            </template>
          </el-sub-menu>
          <el-menu-item v-else :index="menu.path">
            <el-icon :size="18"><component :is="menu.icon || 'Menu'" /></el-icon>
            <span>{{ menu.name }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <!-- 主内容区域 -->
    <el-container class="main-content">
      <!-- 顶部导航栏 -->
      <el-header class="header">
        <div class="header-right">
          <Dark />
          <!-- 消息通知 -->
          <div class="message-container" @click.stop="toggleMessageDropdown">
            <el-badge :value="unreadCount" :hidden="unreadCount === 0" class="message-badge-container">
              <el-icon class="message-icon"><Bell /></el-icon>
            </el-badge>
            <!-- 自定义消息下拉框 -->
            <div v-if="isMessageDropdownVisible" class="custom-message-dropdown" ref="messageDropdownRef" @click.stop>
              <div class="message-header">
                <span class="message-title">消息通知</span>
                <el-button v-if="hasUnreadMessages" size="small" plain type="success" @click="markAllAsRead">全部标为已读</el-button>
              </div>
              <div class="message-list" ref="messageListRef">
                <div v-if="messages.length === 0" class="no-message">
                  <el-icon><Message /></el-icon>
                  <span>暂无消息</span>
                </div>
                <div
                  v-for="message in messages"
                  :key="message.id"
                  :data-id="message.id"
                  class="message-item"
                  :class="{ unread: !message.isRead }"
                  @click="handleMessageClick(message)"
                >
                  <div class="message-content">
                    <h4 class="message-title">{{ message.title }}</h4>
                    <p class="message-desc">{{ message.content }}</p>
                    <p class="message-time">{{ formatTime(message.createTime) }}</p>
                  </div>
                  <div class="message-actions">
                    <el-tag :type="message.isRead ? 'success' : 'warning'" size="small">
                      {{ message.isRead ? '已读' : '未读' }}
                    </el-tag>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <el-dropdown trigger="hover">
            <span class="user-info">
              <el-icon><User /></el-icon>
              <span>{{ user.username }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <!-- 内容区域 -->
      <el-main class="content">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>

  <el-dialog v-model="detailVisible" title="通知详情" width="640px">
    <el-descriptions :column="1" border>
      <el-descriptions-item label="通知 ID">{{ currentMessage ? currentMessage.id : '-' }}</el-descriptions-item>
      <el-descriptions-item label="发送者 ID">{{ currentMessage ? currentMessage.senderId : '-' }}</el-descriptions-item>
      <el-descriptions-item label="接收者 ID">{{ currentMessage ? currentMessage.receiverId : '-' }}</el-descriptions-item>
      <el-descriptions-item label="消息标题">{{ currentMessage ? currentMessage.title : '-' }}</el-descriptions-item>
      <el-descriptions-item label="消息内容">
        <div class="detail-message-content">{{ currentMessage ? currentMessage.content : '-' }}</div>
      </el-descriptions-item>
      <el-descriptions-item label="状态">
        <el-tag :type="currentMessage && currentMessage.isRead ? 'success' : 'warning'" size="small">
          {{ currentMessage ? (currentMessage.isRead ? '已读' : '未读') : '-' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="创建时间">{{ currentMessage ? formatTime(currentMessage.createTime) : '-' }}</el-descriptions-item>
    </el-descriptions>
  </el-dialog>
</template>

<script setup>
/**
 * Layout 后台整体布局组件
 *
 * 功能说明：
 * - 后台管理系统的整体布局框架
 * - 包含左侧导航菜单、顶部导航栏
 * - 顶部导航栏包含：主题切换、消息通知、用户信息下拉
 * - 消息通知支持：未读数量显示、下拉列表、标记已读、删除
 *
 * 使用方式：
 * ```vue
 * <Layout>
 *   <RouterView />
 * </Layout>
 * ```
 */

import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { ElMessage } from 'element-plus'
import Dark from '@/components/common/Dark.vue'
import { getMessagesCount, getMessageList, readAdminMessages } from '@/api/message'
import { Bell, Message, User } from '@element-plus/icons-vue'
import { formatTime } from '@/utils/FormatTime'
import { onUnmounted } from 'vue'

const LAST_LOGOUT_USERNAME_KEY = 'chen_stack_admin_last_logout_username'

const userStore = useUserStore()
const router = useRouter()

// 消息相关状态
const unreadCount = ref(0)
const messages = ref([])
const hasUnreadMessages = computed(() => messages.value.some((msg) => !msg.isRead))
const messageListRef = ref(null)
const isMessageDropdownVisible = ref(false)
const messageDropdownRef = ref(null)
const detailVisible = ref(false)
const currentMessage = ref(null)

const parseMessageData = (msg) => {
  let contentObj = {}
  try {
    contentObj = JSON.parse(msg.content)
  } catch (error) {
    contentObj = {}
  }

  return {
    ...msg,
    isRead: msg.isRead === 1 || msg.isRead === true,
    title: contentObj.title || msg.title || '系统通知',
    content: contentObj.text || msg.content,
  }
}

const markMessageAsReadLocally = (messageId) => {
  const targetMessage = messages.value.find((msg) => msg.id === messageId)
  if (targetMessage) {
    targetMessage.isRead = true
  }
  if (currentMessage.value?.id === messageId) {
    currentMessage.value = {
      ...currentMessage.value,
      isRead: true,
    }
  }
}

// 获取未读消息数量
const fetchUnreadCount = async () => {
  try {
    const res = await getMessagesCount()
    unreadCount.value = Number(res.data)
  } catch (error) {
    ElMessage.error('获取消息数量失败')
    console.error('获取消息数量失败:', error)
  }
}

// 获取消息列表
const fetchMessages = async () => {
  try {
    const res = await getMessageList()
    messages.value = res.data.map((msg) => parseMessageData(msg))
  } catch (error) {
    ElMessage.error('获取消息列表失败')
    console.error('获取消息列表失败:', error)
    messages.value = []
  }
}

// 切换消息下拉框显示状态
const toggleMessageDropdown = async () => {
  isMessageDropdownVisible.value = !isMessageDropdownVisible.value
  if (isMessageDropdownVisible.value) {
    await fetchMessages()
    // 监听点击事件，点击外部关闭下拉框
    setTimeout(() => {
      document.addEventListener('click', closeMessageDropdown)
    }, 0)
  } else {
    document.removeEventListener('click', closeMessageDropdown)
  }
}

// 关闭消息下拉框
const closeMessageDropdown = (e) => {
  if (messageDropdownRef.value && !messageDropdownRef.value.contains(e.target) && !e.target.closest('.message-badge-container')) {
    isMessageDropdownVisible.value = false
    document.removeEventListener('click', closeMessageDropdown)
  }
}

// 清理事件监听器
onUnmounted(() => {
  document.removeEventListener('click', closeMessageDropdown)
})

// 标记所有消息为已读
const markAllAsRead = async () => {
  const unreadMessageIds = messages.value.filter((msg) => !msg.isRead).map(({ id }) => id)
  if (unreadMessageIds.length === 0) {
    ElMessage.success('所有消息均已读')
    return
  }
  try {
    await readAdminMessages(unreadMessageIds)
    // 刷新消息列表
    await fetchMessages()
    // 更新未读数量
    await fetchUnreadCount()
    ElMessage.success('已标记所有消息为已读')
  } catch (error) {
    ElMessage.error('标记消息为已读失败')
    console.error('标记消息为已读失败:', error)
  }
}

// 点击通知查看详情
const handleMessageClick = async (message) => {
  currentMessage.value = { ...message }
  detailVisible.value = true

  if (message.isRead) {
    return
  }

  try {
    await readAdminMessages([message.id])
    markMessageAsReadLocally(message.id)
    await fetchUnreadCount()
  } catch (error) {
    ElMessage.error('标记已读失败')
    console.error('标记已读失败:', error)
  }
}

// 页面加载时获取消息数量
onMounted(() => {
  fetchUnreadCount()
  // 可以添加一个定时器，定期刷新消息数量
  const intervalId = setInterval(fetchUnreadCount, 60000) // 每分钟刷新一次
  // 清理定时器
  onUnmounted(() => clearInterval(intervalId))
})

const menus = computed(() => {
  return userStore.menus
})

// 当前激活的菜单
const activeMenu = computed(() => {
  return router.currentRoute.value.path
})

// 侧边栏菜单颜色（支持亮暗模式）
const sidebarBg = computed(() => getComputedStyle(document.documentElement).getPropertyValue('--sidebar-bg').trim() || '#1e293b')
const sidebarText = computed(() => getComputedStyle(document.documentElement).getPropertyValue('--sidebar-text').trim() || '#cbd5e1')
const sidebarActiveText = computed(() => getComputedStyle(document.documentElement).getPropertyValue('--sidebar-active-text').trim() || '#4ade80')

// 用户信息
const user = computed(() => {
  return userStore.user
})

const saveLastLogoutUsername = () => {
  const username = user.value?.username?.trim()
  if (username) {
    window.sessionStorage.setItem(LAST_LOGOUT_USERNAME_KEY, username)
  } else {
    window.sessionStorage.removeItem(LAST_LOGOUT_USERNAME_KEY)
  }
}

// 退出登录
const handleLogout = () => {
  saveLastLogoutUsername()
  userStore.clearUser()
  router.push('/login')
  ElMessage.success('退出登录成功')
}
</script>

<style lang="scss" scoped>
.layout-container {
  height: 100vh;
  display: flex;
  overflow: hidden;

  // 侧边栏样式
  .sidebar {
    width: 240px !important;
    height: 100%;
    background-color: var(--sidebar-bg);
    color: var(--sidebar-text);
    transition: width 0.3s ease;
    box-shadow: 2px 0 10px var(--shadow-dark);
    z-index: 999;
    flex-shrink: 0;

    .logo-container {
      display: flex;
      align-items: center;
      padding: 16px;
      height: 48px;
      background-color: var(--sidebar-bg);

      .logo-icon {
        width: 40px;
        height: 40px;
        background: linear-gradient(135deg, var(--admin-primary) 0%, var(--admin-primary-dark) 100%);
        border-radius: 50%;
        margin-right: 12px;
        position: relative;
        box-shadow: 0 5px 15px var(--admin-primary-light);

        // 白色圆环
        &::after {
          content: '';
          position: absolute;
          top: 50%;
          left: 50%;
          transform: translate(-50%, -50%);
          width: 20px;
          height: 20px;
          border: 2px solid var(--sidebar-logo-ring);
          border-radius: 50%;
        }
      }

      .logo-text {
        font-size: 18px;
        font-weight: 600;
        color: var(--sidebar-logo-text);
      }
    }

    // logo 区域与菜单的分隔线
    .sidebar-divider {
      height: 1px;
      background-color: var(--sidebar-divider);
      margin: 0 12px;
    }

    // 菜单样式
    .el-menu-pc {
      border-right: none;
      height: calc(100% - 49px); // 48px logo + 1px divider
      overflow-y: auto;
      overflow-x: hidden;
      scrollbar-gutter: stable; // 始终为滚动条预留空间，避免布局抖动

      // 自定义滚动条样式
      &::-webkit-scrollbar {
        width: 8px;
      }

      &::-webkit-scrollbar-track {
        background: transparent;
        margin: 4px 0;
      }

      &::-webkit-scrollbar-thumb {
        background-color: var(--sidebar-scrollbar);
        border-radius: 4px;
        border: 2px solid transparent;
        background-clip: content-box;

        &:hover {
          background-color: var(--sidebar-scrollbar-hover);
        }
      }

      .el-menu-item {
        padding: 12px 20px;
        margin: 4px 0;
        border-radius: 8px;
        transition: all 0.2s ease;

        &:hover {
          background-color: var(--sidebar-menu-hover);
        }

        &.is-active {
          background-color: var(--sidebar-active-bg);
        }
      }

      // 子菜单样式
      .el-sub-menu {
        .el-sub-menu__title {
          padding: 12px 20px;
          border-radius: 8px;
          transition: all 0.2s ease;

          &:hover {
            background-color: var(--sidebar-menu-hover);
          }
        }

        // 子菜单下的菜单项缩进
        .el-menu-item {
          padding-left: 40px !important;
        }

        // 孙子菜单样式
        .el-sub-menu {
          // 孙子菜单下的菜单项额外缩进
          .el-menu-item {
            padding-left: 60px !important;
          }
        }
      }
    }
  }

  // 主内容区域
  .main-content {
    flex: 1;
    display: flex;
    flex-direction: column;
    height: 100%;
    overflow: hidden;

    // 顶部导航栏
    .header {
      height: 48px;
      background-color: var(--el-bg-color);
      border-bottom: 1px solid var(--el-border-color-light);
      box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05);
      display: flex;
      justify-content: flex-end;
      align-items: center;
      padding: 0 24px;
      position: relative;
      z-index: 1000;

      .header-right {
        display: flex;
        align-items: center;

        // 消息容器样式
        .message-container {
          position: relative;
          display: inline-block;
          // 消息徽章容器
          :deep(.message-badge-container) {
            margin-right: 20px;
            cursor: pointer;
            padding: 7px;
            border-radius: 50%;
            transition: all 0.3s ease;
            display: inline-flex;
            &:hover {
              background-color: var(--message-badge-hover);
              transform: scale(1.05);
            }
            .message-icon {
              font-size: 25px;
              color: var(--admin-primary-dark);
            }
            // 徽章
            .el-badge__content {
              top: 10px;
              right: 20px;
              color: var(--badge-text-inverse);
              background: var(--admin-primary-dark);
            }
          }

          // 自定义消息下拉框样式
          .custom-message-dropdown {
            width: 420px;
            max-height: calc(100vh - 150px);
            overflow: hidden;
            border-radius: 12px;
            box-shadow: 0 8px 30px rgba(0, 0, 0, 0.12);
            border: 1px solid var(--el-border-color);
            position: absolute;
            right: 0;
            top: 100%;
            margin-top: 8px;
            z-index: 1000;
            transform-origin: top right;
            animation: fade-in 0.2s ease-out;
            transition: all 0.2s ease-out;

            // 下拉框动画
            @keyframes fade-in {
              from {
                opacity: 0;
                transform: scale(0.95);
              }
              to {
                opacity: 1;
                transform: scale(1);
              }
            }

            @media screen and (max-width: 768px) {
              width: 300px;
              left: -150px;
              // transform: translateX(30%);
            }

            // 消息头部样式
            .message-header {
              display: flex;
              justify-content: space-between;
              align-items: center;
              padding: 14px 20px;
              background-color: var(--el-bg-color);
              border-bottom: 1px solid var(--el-border-color);

              .message-title {
                flex: 1;
                font-size: 16px;
                font-weight: 600;
                color: var(--el-text-color);
              }
            }

            // 消息列表样式
            .message-list {
              max-height: 400px;
              overflow-y: auto;
              overflow-x: hidden;
              padding: 0;
              scrollbar-width: thin;
              scrollbar-color: var(--el-scrollbar-thumb-color) var(--el-scrollbar-track-color);

              .no-message {
                display: flex;
                flex-direction: column;
                align-items: center;
                justify-content: center;
                padding: 40px 0;
                color: var(--text-muted);
                .el-icon {
                  font-size: 48px;
                  margin-bottom: 16px;
                  color: var(--text-regular);
                }
                span {
                  font-size: 14px;
                }
              }
              // 消息列表
              .message-item {
                display: flex;
                justify-content: space-between;
                align-items: center;
                padding: 16px 20px;
                cursor: pointer;
                transition: all 0.25s ease;
                position: relative;
                background-color: var(--el-bg-color-overlay);
                border-bottom: 1px solid var(--el-border-color);
                &:hover {
                  background-color: var(--el-border-color-lighter); // 悬停状态使用浅色填充
                }
                &.unread {
                  background-color: var(--el-border-color-light); // 未读状态使用浅色填充
                }
                // 未读消息指示点
                &.unread::before {
                  content: '';
                  position: absolute;
                  left: 10px;
                  top: 50%;
                  transform: translateY(-50%);
                  width: 6px;
                  height: 6px;
                  border-radius: 50%;
                  background-color: var(--admin-primary-dark);
                }
                .message-content {
                  flex: 1;
                  padding-right: 10px;
                  min-width: 0;
                  .message-title {
                    font-size: 14px;
                    font-weight: 600;
                    color: var(--message-title-color);
                    margin-bottom: 4px;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                  }
                  // content内容
                  .message-desc {
                    font-size: 13px;
                    color: var(--message-desc-color);
                    margin-bottom: 6px;
                    display: -webkit-box;
                    -webkit-line-clamp: 2; // 显示行数
                    -webkit-box-orient: vertical;
                    overflow: hidden;
                  }
                  .message-time {
                    font-size: 12px;
                    color: var(--text-muted);
                  }
                }
                // 消息操作按钮
                .message-actions {
                  display: flex;
                  align-items: center;
                  margin-left: 8px;
                }
              }
            }
          }
        }
        .user-info {
          display: flex;
          align-items: center;
          cursor: pointer;
          padding: 8px 12px;
          border-radius: 8px;
          transition: background-color 0.2s ease;
          &:hover {
            background-color: var(--user-info-hover);
          }
          .el-icon {
            margin-right: 8px;
            color: var(--user-info-icon-color);
          }
        }
      }
    }

    // 内容区域
    .content {
      flex: 1;
      padding: 10px;
      background-color: var(--el-bg-color);
      overflow-y: auto;
      :deep(.menu-management-container) {
        padding: 0px;
      }
    }
  }

}

.detail-message-content {
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-regular);
}
</style>
