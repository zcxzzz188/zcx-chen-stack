import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { GetJwt } from '@/utils/Auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'index',
      component: () => import('@/views/Layout/index.vue'),
      meta: { title: '主页' },
      children: [
        {
          path: '/',
          name: 'Home',
          component: () => import('@/views/Home/index.vue'),
          meta: { title: '首页' },
        },
        {
          path: 'article',
          name: 'Article',
          component: () => import('@/views/Article/index.vue'),
          meta: { title: '文章' },
        },
        {
          path: 'search',
          name: 'Search',
          component: () => import('@/views/Search/index.vue'),
          meta: { title: '搜索' },
        },
        {
          path: 'user/:userId',
          name: 'UserHomepage',
          component: () => import('@/views/User/Homepage/index.vue'),
          meta: { title: '用户主页' },
        },
        {
          path: 'user/:userId/article/:articleId',
          name: 'ArticleDetail',
          component: () => import('@/views/User/Article/index.vue'),
          meta: { title: '文章详情' },
        },
        {
          path: 'user/:userId/column/:columnId',
          name: 'Column',
          component: () => import('@/views/User/Column/index.vue'),
          meta: { title: '专栏详情' },
        },
        {
          path: 'setting',
          name: 'Setting',
          component: () => import('@/views/Setting/index.vue'),
          meta: { title: '个人设置' },
        },
        {
          path: 'message',
          name: 'Message',
          component: () => import('@/views/Message/ConversationList.vue'),
          meta: { title: '私信' },
        },
        {
          path: 'message/chat/:userId',
          name: 'ChatWindow',
          component: () => import('@/views/Message/ChatWindow.vue'),
          meta: { title: '聊天窗口' },
        },
        {
          path: 'notification',
          name: 'Notification',
          component: () => import('@/views/Notification/index.vue'),
          meta: { title: '消息中心' },
        },
        {
          path: 'account',
          name: 'Account',
          component: () => import('@/views/Account/index.vue'),
          redirect: '/login',
          children: [
            {
              path: '/login',
              component: () => import('@/views/Account/Login/index.vue'),
              name: 'login',
              meta: { title: '用户登录' },
            },
            {
              path: '/register',
              component: () => import('@/views/Account/Register/index.vue'),
              name: 'register',
              meta: { title: '用户注册' },
            },
            {
              path: '/reset',
              component: () => import('@/views/Account/Reset/index.vue'),
              name: 'reset',
              meta: { title: '重置密码' },
            },
          ],
        },
      ],
    },
    {
      path: '/creation',
      name: 'Creation',
      component: () => import('@/views/Creation/index.vue'),
      meta: { title: '创作中心' },
      children: [
        {
          path: '',
          name: '创作中心首页',
          component: () => import('@/views/Creation/Home/index.vue'),
        },
        {
          path: 'articlemanage',
          name: '创作中心文章管理',
          component: () => import('@/views/Creation/ArticleManage/index.vue'),
        },
        {
          path: 'columnmanage',
          name: '创作中心专栏管理',
          component: () => import('@/views/Creation/ColumnManage/index.vue'),
        },
        {
          path: 'commentmanage',
          name: '创作中心评论管理',
          component: () => import('@/views/Creation/CommentManage/index.vue'),
        },
      ],
    },
    {
      path: '/editor',
      name: 'Editor',
      component: () => import('@/views/Editor/index.vue'),
      meta: { title: '发布文章' },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('@/components/Common/404.vue'),
      meta: { title: '页面不存在' },
    },
  ],
})

router.beforeEach((to) => {
  if (!GetJwt() && (to.path.startsWith('/creation') || to.path === '/editor')) {
    ElMessage.error('请先登录')
    return '/login'
  }
})

export default router
