import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { RemoveJwt } from '@/utils/Auth'
import { GetJwt } from '@/utils/Auth'

// 创建路由实例
const router = createRouter({
  // history: createWebHashHistory(import.meta.env.BASE_URL),
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/account/login.vue'),
    },
    {
      path: '/',
      name: 'layout',
      redirect: '/home',
      component: () => import('@/components/layout/Layout.vue'),
      children: [], // 动态路由将在这里添加
    },
  ],
})

// 导入404组件
const NotFound = () => import('@/components/common/404.vue')

let dynamicRouteRemovers = []
let dynamicRouteNameSet = new Set()
let dynamicRoutePathSet = new Set()

const addDynamicRouteRecord = (route, routeNames, routePaths) => {
  if (!route) {
    return
  }

  if (route.name) {
    routeNames.add(String(route.name))
  }

  if (route.path) {
    routePaths.add(route.path)
  }

  if (Array.isArray(route.children) && route.children.length > 0) {
    route.children.forEach((child) => addDynamicRouteRecord(child, routeNames, routePaths))
  }
}

const clearDynamicRouteRecords = () => {
  dynamicRouteRemovers.forEach((removeRoute) => {
    if (typeof removeRoute === 'function') {
      removeRoute()
    }
  })
  dynamicRouteRemovers = []
  dynamicRouteNameSet = new Set()
  dynamicRoutePathSet = new Set()
}

const ensureNotFoundRoute = () => {
  if (!router.hasRoute('notFound')) {
    router.addRoute({
      path: '/:pathMatch(.*)*',
      name: 'notFound',
      component: NotFound,
    })
  }
}

const addAndTrackDynamicRoutes = (dynamicRoutes) => {
  const layoutRoute = router.getRoutes().find((route) => route.path === '/')
  if (!layoutRoute?.name) {
    throw new Error('未找到 Layout 路由')
  }

  const nextRouteNames = new Set()
  const nextRoutePaths = new Set()
  const nextRouteRemovers = []

  dynamicRoutes.forEach((route) => {
    const removeRoute = router.addRoute(layoutRoute.name, route)
    nextRouteRemovers.push(removeRoute)
    addDynamicRouteRecord(route, nextRouteNames, nextRoutePaths)
  })

  dynamicRouteRemovers = nextRouteRemovers
  dynamicRouteNameSet = nextRouteNames
  dynamicRoutePathSet = nextRoutePaths
}

const isCurrentRouteDynamic = (route, routeNames, routePaths) => {
  if (!route) {
    return false
  }

  const currentRouteName = route.name ? String(route.name) : ''
  return routeNames.has(currentRouteName) || routePaths.has(route.path)
}

const isCurrentRouteStillRegistered = (route, routePaths) => {
  if (!route) {
    return false
  }

  return routePaths.has(route.path)
}

const installDynamicRoutes = async (force = false) => {
  const userStore = useUserStore()
  const dynamicRoutes = await userStore.loadMenusAndRoutes(force)
  addAndTrackDynamicRoutes(dynamicRoutes)
  ensureNotFoundRoute()
  userStore.routesAdded = true
  return dynamicRoutes
}

const rebuildDynamicRoutes = async ({ force = false, checkCurrentRoute = false } = {}) => {
  const userStore = useUserStore()
  const currentRoute = router.currentRoute.value
  const previousDynamicRoutes = [...userStore.routes]
  const previousRouteNameSet = new Set(dynamicRouteNameSet)
  const previousRoutePathSet = new Set(dynamicRoutePathSet)
  const wasCurrentDynamicRoute = checkCurrentRoute && isCurrentRouteDynamic(currentRoute, previousRouteNameSet, previousRoutePathSet)

  clearDynamicRouteRecords()
  userStore.routesAdded = false

  try {
    await installDynamicRoutes(force)

    if (wasCurrentDynamicRoute && !isCurrentRouteStillRegistered(currentRoute, dynamicRoutePathSet)) {
      await router.replace('/home')
    }
  } catch (error) {
    if (previousDynamicRoutes.length > 0) {
      addAndTrackDynamicRoutes(previousDynamicRoutes)
      userStore.routesAdded = true
    }
    throw error
  }
}

export async function refreshDynamicRoutes() {
  await rebuildDynamicRoutes({ force: true, checkCurrentRoute: true })
}

// 路由守卫
router.beforeEach(async (to, from, next) => {
  const userStore = useUserStore()

  // 如果没有登录
  if (!GetJwt()) {
    // 如果访问的不是登录,则跳转到登录页
    if (to.path !== '/login') {
      next({ path: '/login' })
      return
    }
    next()
    return
  }

  // 用户已登录但信息未加载
  if (!userStore.user) {
    try {
      // 获取用户信息
      await userStore.getUserInfo()
    } catch (error) {
      console.error('获取用户信息失败:', error)
      RemoveJwt()
      next({ path: '/login' })
      return
    }
  }

  // 用户信息已加载，但路由未添加
  if (!userStore.routesAdded) {
    try {
      await rebuildDynamicRoutes({ force: false, checkCurrentRoute: false })

      // 如果是刷新页面，需要重新导航到目标路由
      if (to.path !== '/login') {
        next({ ...to, replace: true })
        return
      }
    } catch (error) {
      console.error('加载菜单和路由失败:', error)
      RemoveJwt()
      next({ path: '/login' })
      return
    }
  }

  // 如果用户已登录且访问登录页，则跳转到主页
  if (to.path === '/login') {
    next({ path: '/home' })
    return
  }

  next()
})

// 导出路由实例
export default router
