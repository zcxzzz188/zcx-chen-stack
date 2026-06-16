<template>
  <el-menu
    :default-active="activeIndex"
    router
    class="pc-menu"
    mode="horizontal"
    @select="handleSelect"
    :ellipsis="false"
    :class="{ hidden: !isVisible }"
  >
    <a class="logo" href="/"><el-text size="large" class="logo-text">辰栈</el-text></a>
    <a href="/creation"><el-text class="creation-center">创作中心</el-text></a>
    <div class="right">
      <Dark />
      <div v-if="user" class="user-info">
        <el-text size="large" class="nickname">{{ user.nickname }}</el-text>
        <el-dropdown
          :popper-options="{
            modifiers: [
              {
                name: 'offset',
                options: {
                  offset: [-20, 8],
                },
              },
            ],
          }"
        >
          <el-avatar v-if="user.avatar" style="cursor: pointer" :size="40" :src="user.avatar" />
          <el-avatar v-else style="cursor: pointer" :size="40" :icon="UserFilled" />
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="goToProfile">个人主页</el-dropdown-item>
              <el-dropdown-item @click="goToUserSettings">个人设置</el-dropdown-item>
              <el-dropdown-item @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <div class="login" v-else @click="handleLoginClick">登录</div>
    </div>
  </el-menu>

</template>

<script setup>
import Dark from '@/components/Common/Dark.vue'
import { useUserStore } from '@/stores/userStore.js'
import { ref, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { info } from '@/api/user'
import { UserFilled } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'

const LAST_LOGOUT_USERNAME_KEY = 'chen_stack_user_last_logout_username'

const userStore = useUserStore()
const { user } = storeToRefs(userStore)
const router = useRouter()
// 控制header显示状态，默认为true确保header可见
const isVisible = ref(true)

// 当前激活的菜单索引
const activeIndex = ref('/creation')

// 监听路由变化，更新激活的菜单
router.afterEach((to) => {
  activeIndex.value = to.path
})

// 处理菜单选择事件
const handleSelect = (index) => {
  router.push(index)
}

const handleLoginClick = () => {
  // 根据路由名称跳转
  router.push({ name: 'Account' })
}

const getUserInfo = async () => {
  const res = await info()
  user.value = res.data
}

const saveLastLogoutUsername = () => {
  const username = user.value?.username?.trim()
  if (username) {
    window.sessionStorage.setItem(LAST_LOGOUT_USERNAME_KEY, username)
  } else {
    window.sessionStorage.removeItem(LAST_LOGOUT_USERNAME_KEY)
  }
}

const logout = async () => {
  try {
    await ElMessageBox.confirm('确认退出登录吗？', '提示', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'warning',
    })
    saveLastLogoutUsername()
    userStore.clearUser()
    router.replace('/login')
  } catch {
    // 用户取消退出，不做处理
  }
}

// 跳转到个人主页
const goToProfile = () => {
  location.href = `/user/${user.value.id}`
}

const goToUserSettings = () => {
  window.location.href = '/setting'
}

onMounted(() => {
  if (user.value) {
    getUserInfo()
  }
})
</script>

<style lang="scss" scoped>
.pc-menu {
  height: 48px;
  width: 100%;
  padding: 0 10px 0 10px;
  display: flex;
  justify-content: center;
  align-items: center;
  position: fixed;
  top: 0;
  left: 0;
  z-index: 1000;
  transition: transform 0.5s ease;
  background-color: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);

  &.hidden {
    transform: translateY(-100%);
  }

  .logo {
    margin-right: 10px;
    font-weight: bold;
    white-space: nowrap;
    /* Logo文字 */
    .logo-text {
      margin-right: 4px;
      font-size: 26px;
      color: var(--accent);
      text-shadow: 2px 2px 4px var(--shadow);
      letter-spacing: 1px;
      position: relative;
      transition: all 0.3s ease;
      &::after {
        content: '';
        position: absolute;
        bottom: -5px;
        left: 0;
        width: 100%;
        height: 3px;
        background: linear-gradient(90deg, var(--accent), var(--accent-hover));
        border-radius: 3px;
        transform: scaleX(0);
        transform-origin: right;
        transition: transform 0.3s ease;
      }
      &:hover {
        color: var(--accent-hover);
        text-shadow: 2px 2px 8px var(--shadow);

        &::after {
          transform: scaleX(1);
          transform-origin: left;
        }
      }
    }
  }
  .creation-center {
    font-size: 20px;
  }

  .menu-item {
    width: 100% !important;
    .menu-text {
      font-size: 18px;
      margin-left: 5px;
    }
  }

  /* 头部右侧内容 */
  .right {
    display: flex;
    margin-left: auto;
    justify-content: center;
    align-items: center;
    .user-info {
      display: flex;
      align-items: center;
      .nickname {
        font-size: 18px !important;
        font-weight: 600;
        color: var(--el-text-color-primary);
        margin-left: 10px;
        margin-right: 10px;
        transition: all 0.3s ease;

        &:hover {
          color: var(--el-color-primary);
          transform: translateY(-2px);
        }

        @media (max-width: 1314px) {
          margin-left: 0;
          display: none;
        }
      }
    }
    .login {
      display: flex;
      justify-content: center;
      align-items: center;
      width: 40px;
      height: 40px;
      margin-left: 5px;
      background-color: var(--el-border-color-light);
      border: 1px solid var(--el-border-color);
      font-size: 15px;
      border-radius: 50%;
      cursor: pointer;
    }
  }
}

// 响应式设计 - 移动端
@media (max-width: 870px) {
  .pc-menu {
    padding: 0 5px 0 0;
    .logo {
      margin-right: 0;
      .logo-text {
        font-size: 20px;
      }
      .creation-center {
        font-size: 20px;
      }
    }
    .right {
      .login {
        margin-left: 0;
      }
    }
  }
}
</style>
