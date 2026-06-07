<template>
  <div class="home-container">
    <!-- Hero 区域 -->
    <section class="hero">
      <!-- 简洁渐变背景 -->
      <div class="hero-bg"></div>

      <div class="hero-content">
        <div class="hero-badge">
          <span class="dot"></span>
          <span>zcx-chen-stack</span>
        </div>

        <h1 class="hero-title">辰栈</h1>

        <div class="hero-subtitle">
          <span class="hero-typewriter-text">{{ typedSubtitle }}</span>
          <span class="hero-typewriter-cursor"></span>
        </div>

        <div class="hero-actions">
          <button class="explore-btn" @click="navigateTo('/article')">
            <span>开始探索</span>
            <svg
              class="explore-btn-arrow"
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path d="M5 12h14M12 5l7 7-7 7" />
            </svg>
          </button>
        </div>

        <div class="hero-nav">
          <div
            class="hero-nav-item"
            v-for="(item, index) in quickNavItems"
            :key="index"
            @click="navigateTo(item.path)"
          >
            <span class="icon">{{ item.icon }}</span>
            <span>{{ item.title }}</span>
          </div>
        </div>
      </div>

      <!-- 右侧装饰 -->
      <HeroCosmicRing />
    </section>

    <!-- 主内容区 -->
    <main class="main-content" ref="contentSection">
      <div class="section-header">
        <h2 class="section-title">最新文章</h2>
        <router-link to="/article" class="section-link">
          查看全部
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
          >
            <path d="M5 12h14M12 5l7 7-7 7" />
          </svg>
        </router-link>
      </div>

      <div class="content-wrapper">
        <!-- 加载状态 -->
        <SkeletonLoader v-if="articleLoading" :loading="true" :count="9" />

        <!-- 空状态 -->
        <div v-else-if="articles.length === 0" class="empty-state">
          <EmptyState type="article" />
        </div>

        <!-- 文章列表 -->
        <div v-else class="article-grid" ref="articlesSectionRef">
          <ArticleCard
            v-for="(article, index) in articles"
            :key="article.id"
            :article="article"
            mode="grid"
            @click="goToArticle(article)"
          />
        </div>

        <!-- 右侧边栏 -->
        <aside class="sidebar">
          <!-- 项目链接 -->
          <SidebarCard title="项目链接" icon="📦">
            <div class="project-links">
              <a
                href="https://github.com/zcxzzz188/zcx-chen-stack"
                target="_blank"
                rel="noopener noreferrer"
                class="project-link project-link-placeholder"
              >
                <div class="project-link-info">
                  <div class="project-link-name">GitHub 源码仓库</div>
                  <div class="project-link-desc">点击查看项目源码</div>
                </div>
              </a>
            </div>
          </SidebarCard>

          <!-- 热门标签 -->
          <SidebarCard title="热门标签" icon="🏷️">
            <TagCloud :tags="hotTags" />
          </SidebarCard>

          <!-- 社区统计 -->
          <SidebarCard title="社区统计" icon="📊">
            <StatsCard :stats="statsList" :animated="true" :columns="2" />
          </SidebarCard>
        </aside>
      </div>
    </main>

    <!-- 页脚 -->
    <footer class="footer">
      <!-- 装饰线 -->
      <div class="footer-glow-line"></div>

      <div class="footer-content">
        <!-- 品牌区域 -->
        <div class="footer-brand">
          <div class="footer-logo">
            <img src="/icons/favicon.ico" alt="logo" class="logo-img" />
            <span class="logo-text">辰栈</span>
          </div>
          <p class="footer-desc">
            分享技术、经验和见解，用代码改变世界。探索无限可能，与开发者共同成长。
          </p>
          <!-- 社交链接 -->
          <div class="footer-social">
            <button
              type="button"
              class="social-link social-link-qq"
              title="QQ 联系"
              aria-label="QQ 联系"
              @click="openContactQr('qq')"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                class="social-link-icon"
                viewBox="0 0 24 24"
                width="18"
                height="18"
                fill="none"
                aria-hidden="true"
              >
                <path d="M0 0h24v24H0z" fill="none" />
                <path
                  fill="currentColor"
                  fill-rule="evenodd"
                  d="M12.01 22.597c-.737.076-2.183.15-3.5.153a20 20 0 0 1-1.893-.07a5 5 0 0 1-.728-.12a1.7 1.7 0 0 1-.34-.123a1 1 0 0 1-.41-.37a1.1 1.1 0 0 1-.152-.684c.026-.235.123-.426.226-.569c.192-.268.476-.462.707-.597q.154-.088.319-.167a6.5 6.5 0 0 1-1.051-1.818a6 6 0 0 1-.379.431a2.4 2.4 0 0 1-.373.317c-.108.071-.369.229-.702.188a.87.87 0 0 1-.611-.39a1.3 1.3 0 0 1-.171-.39a3.3 3.3 0 0 1-.091-.908c.013-.72.174-1.76.613-3.173c.34-1.092.719-2.032 1.229-3.296l.007-.017l.296-.734c-.039-2.318.42-4.526 1.529-6.19c1.156-1.734 2.98-2.815 5.466-2.82h.018c2.486.005 4.31 1.086 5.466 2.82c1.108 1.664 1.568 3.872 1.53 6.19q.154.387.295.734l.007.018c.51 1.263.89 2.203 1.229 3.295c.439 1.412.6 2.452.613 3.173c.007.356-.023.663-.09.908c-.034.12-.085.26-.172.39a.87.87 0 0 1-.611.39c-.333.04-.594-.117-.702-.188a2.4 2.4 0 0 1-.373-.317a6 6 0 0 1-.379-.431a6.5 6.5 0 0 1-1.05 1.818q.163.078.317.167c.232.135.515.33.708.598c.103.142.2.333.226.567c.027.246-.032.481-.152.685a1 1 0 0 1-.41.37a1.7 1.7 0 0 1-.34.124a5 5 0 0 1-.728.119c-.531.052-1.206.071-1.893.07c-1.317-.002-2.763-.077-3.5-.153m-5.502-12.22c-.057-2.19.372-4.118 1.275-5.475c.877-1.316 2.235-2.15 4.227-2.152c1.992.003 3.35.836 4.227 2.152c.903 1.357 1.332 3.285 1.275 5.476a.8.8 0 0 0 .053.298l.354.88c.518 1.281.875 2.168 1.194 3.196c.256.822.401 1.48.477 1.987c-.274-.389-.49-.733-.51-.765a.75.75 0 0 0-.738-.347h-.002a.75.75 0 0 0-.647.742c-.002.996-.531 2.384-1.727 3.398a.75.75 0 0 0-.068 1.08v.001a.76.76 0 0 0 .332.21c.141.043.311.1.486.167c-.36.018-.77.026-1.204.025c-1.325-.002-2.86-.087-3.502-.157c-.643.07-2.177.155-3.502.157a25 25 0 0 1-1.205-.025a8 8 0 0 1 .487-.167a.75.75 0 0 0 .331-.21h.001a.75.75 0 0 0 .186-.636v-.001a.75.75 0 0 0-.254-.444c-1.196-1.014-1.725-2.402-1.727-3.398a.75.75 0 0 0-.647-.742h-.002a.75.75 0 0 0-.739.347c-.02.032-.235.376-.509.765a13.5 13.5 0 0 1 .477-1.987c.319-1.028.676-1.914 1.194-3.196l.354-.88a.8.8 0 0 0 .053-.298"
                  clip-rule="evenodd"
                />
              </svg>
            </button>
            <button
              type="button"
              class="social-link social-link-wechat"
              title="微信联系"
              aria-label="微信联系"
              @click="openContactQr('wechat')"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                class="social-link-icon"
                viewBox="0 0 24 24"
                width="18"
                height="18"
                fill="none"
                aria-hidden="true"
              >
                <path d="M0 0h24v24H0z" fill="none" />
                <path
                  fill="currentColor"
                  d="M15.85 8.14c.39 0 .77.03 1.14.08C16.31 5.25 13.19 3 9.44 3c-4.25 0-7.7 2.88-7.7 6.43c0 2.05 1.15 3.86 2.94 5.04L3.67 16.5l2.76-1.19c.59.21 1.21.38 1.87.47c-.09-.39-.14-.79-.14-1.21c-.01-3.54 3.44-6.43 7.69-6.43M12 5.89a.96.96 0 1 1 0 1.92a.96.96 0 0 1 0-1.92M6.87 7.82a.96.96 0 1 1 0-1.92a.96.96 0 0 1 0 1.92"
                />
                <path
                  fill="currentColor"
                  d="M22.26 14.57c0-2.84-2.87-5.14-6.41-5.14s-6.41 2.3-6.41 5.14s2.87 5.14 6.41 5.14c.58 0 1.14-.08 1.67-.2L20.98 21l-1.2-2.4c1.5-.94 2.48-2.38 2.48-4.03m-8.34-.32a.96.96 0 1 1 .96-.96c.01.53-.43.96-.96.96m3.85 0a.96.96 0 1 1 0-1.92a.96.96 0 0 1 0 1.92"
                />
              </svg>
            </button>
            <button
              type="button"
              class="social-link social-link-email"
              title="复制邮箱"
              aria-label="复制邮箱"
              @click="copyEmail"
            >
              <svg
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <rect x="2" y="4" width="20" height="16" rx="2" />
                <path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7" />
              </svg>
            </button>
          </div>
        </div>

        <!-- 导航区域 -->
        <div class="footer-nav-group">
          <div class="footer-column">
            <h4>快速导航</h4>
            <ul>
              <li><router-link to="/">首页</router-link></li>
              <li><router-link to="/article">技术文章</router-link></li>
            </ul>
          </div>

          <div class="footer-column">
            <h4>资源链接</h4>
            <ul>
              <li>
                <a
                  href="https://github.com/zcxzzz188/zcx-chen-stack"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  项目源码仓库
                </a>
              </li>
              <li><router-link to="/about">关于我们</router-link></li>
              <li><router-link to="/contact">联系方式</router-link></li>
            </ul>
          </div>
        </div>
      </div>

      <!-- 底部栏 -->
      <div class="footer-bottom">
        <div class="footer-bottom-right">
          <span class="copyright">© {{ currentYear }} 辰栈. All rights reserved.</span>
        </div>
      </div>
    </footer>

    <Teleport to="body">
      <div
        v-if="contactQrVisible"
        class="contact-qr-overlay"
        @wheel.prevent
        @touchmove.prevent
      >
        <div class="contact-qr-panel">
          <button
            type="button"
            class="contact-qr-close"
            aria-label="关闭二维码预览"
            title="关闭"
            @click="closeContactQr"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
          <img
            :src="contactQrImage"
            alt="二维码预览"
            class="contact-qr-image"
          />
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, onMounted, watch, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { Loading, Picture } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getAllArticleList, getHotTags } from '@/api/article'
import { formatCompactNumber } from '@/utils/formatNumber'
import { useUserStore } from '@/stores/userStore'
import { getCommunityStats } from '@/api/user'
import Header from '@/components/Layout/Header.vue'
import ArticleCard from '@/components/Article/ArticleCard.vue'
import SidebarCard from '@/components/Layout/SidebarCard.vue'
import TagCloud from '@/components/Article/TagCloud.vue'
import StatsCard from '@/components/User/StatsCard.vue'
import EmptyState from '@/components/Loading/EmptyState.vue'
import SkeletonLoader from '@/components/Loading/SkeletonLoader.vue'
import { useSeoMeta } from '@/plugins/seo'
import HeroCosmicRing from '@/components/hero/HeroCosmicRing.vue'

// SEO - 首页
useSeoMeta({
  title: '首页',
  description:
    '辰栈是一个面向技术创作者的 AI 辅助技术博客管理系统，支持文章创作、专栏管理、评论互动与内容审核。',
  keywords: '辰栈, zcx-chen-stack, 技术博客, 程序员, 前端, 后端, 全栈, 编程, 开发者社区',
})

// 路由和用户状态
const router = useRouter()
const userStore = useUserStore()

// 响应式数据
const articles = ref([])
const articleLoading = ref(false)
const currentYear = ref(new Date().getFullYear())
const contentSection = ref(null)
const articlesSectionRef = ref(null)
const hotTags = ref([]) // 热门标签
const contactQrVisible = ref(false)
const contactQrImage = ref('/contact-qr/qq-qr.png')

const contactQrMap = {
  qq: {
    image: '/contact-qr/qq-qr.png',
  },
  wechat: {
    image: '/contact-qr/wechat-qr.png',
  },
}
let bodyStyleSnapshot = null
let htmlStyleSnapshot = null
let lockedScrollY = 0
let bodyScrollLocked = false

// 社区统计数据
const stats = ref({
  articleCount: 0,
  userCount: 0,
  viewCount: 0,
  authorCount: 0,
})

// 用于 StatsCard 组件的统计数据格式
const statsList = ref([
  { value: 0, label: '文章总数' },
  { value: 0, label: '注册用户' },
  { value: 0, label: '总阅读量' },
  { value: 0, label: '活跃作者' },
])

// 打字机效果
const typedSubtitle = ref('')
let subtitleIndex = 0
let charIndex = 0
let isDeleting = false

const subtitles = ['分享技术、经验和见解', '探索代码的无限可能', '与开发者共同成长']

const TYPE_SPEED = 80
const DELETE_SPEED = 40
const PAUSE_AFTER_TYPE = 2000
const PAUSE_AFTER_DELETE = 500

const typeSubtitle = () => {
  const current = subtitles[subtitleIndex]
  if (!isDeleting) {
    if (charIndex < current.length) {
      typedSubtitle.value = current.substring(0, charIndex + 1)
      charIndex++
      setTimeout(typeSubtitle, TYPE_SPEED)
    } else {
      isDeleting = true
      setTimeout(typeSubtitle, PAUSE_AFTER_TYPE)
    }
  } else {
    if (charIndex > 0) {
      typedSubtitle.value = current.substring(0, charIndex - 1)
      charIndex--
      setTimeout(typeSubtitle, DELETE_SPEED)
    } else {
      isDeleting = false
      subtitleIndex = (subtitleIndex + 1) % subtitles.length
      charIndex = 0
      setTimeout(typeSubtitle, PAUSE_AFTER_DELETE)
    }
  }
}

// 快速导航项
const quickNavItems = ref([
  { title: '技术文章', path: '/article', icon: '📚' },
])

// 滚动入场动画
const observeElements = () => {
  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible')
          observer.unobserve(entry.target)
        }
      })
    },
    { threshold: 0.1 },
  )

  // 观察每个文章卡片
  const articleCards = document.querySelectorAll('.article-card:not(.skeleton)')
  articleCards.forEach((card, index) => {
    card.style.transitionDelay = `${index * 80}ms`
    observer.observe(card)
  })
}

// 获取文章列表
const fetchArticleList = async () => {
  try {
    articleLoading.value = true
    const res = await getAllArticleList(1, 9)
    articles.value = res.data?.data || []
  } catch (error) {
    ElMessage.error('获取文章列表失败')
  } finally {
    articleLoading.value = false
  }
}

// 格式化日期
const formatDate = (dateString) => {
  if (!dateString) return ''
  return new Date(dateString).toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

// 格式化数字
const formatNumber = (num) => formatCompactNumber(num)

// 判断新文章（3 天内）
const isNewArticle = (article) => {
  const diffDays = (Date.now() - new Date(article.createTime)) / (1000 * 60 * 60 * 24)
  return diffDays <= 3
}

// 路由跳转
const getArticleDetailRoute = (article) => `/user/${article.userId}/article/${article.id}`
const goToArticle = (article) => router.push(getArticleDetailRoute(article))
const navigateTo = (path) => router.push(path)

const openContactQr = (type) => {
  contactQrImage.value = contactQrMap[type]?.image || contactQrMap.qq.image
  contactQrVisible.value = true
}

const copyEmailText = async (email) => {
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(email)
    return true
  }

  if (typeof document === 'undefined') return false

  const textarea = document.createElement('textarea')
  textarea.value = email
  textarea.setAttribute('readonly', 'true')
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  textarea.style.top = '0'
  document.body.appendChild(textarea)
  textarea.select()

  let success = false
  try {
    success = document.execCommand('copy')
  } catch (error) {
    success = false
  } finally {
    document.body.removeChild(textarea)
  }

  return success
}

const copyEmail = async () => {
  const email = '2236440482@qq.com'
  try {
    const success = await copyEmailText(email)
    if (success) {
      ElMessage.success('邮箱已复制')
    } else {
      ElMessage.warning(`请手动复制邮箱：${email}`)
    }
  } catch (error) {
    ElMessage.warning(`请手动复制邮箱：${email}`)
  }
}

const closeContactQr = () => {
  contactQrVisible.value = false
}

const lockBodyScroll = () => {
  if (bodyScrollLocked || typeof window === 'undefined' || typeof document === 'undefined') {
    return
  }

  const { body, documentElement: html } = document
  lockedScrollY = window.scrollY || window.pageYOffset || 0
  bodyStyleSnapshot = {
    overflow: body.style.overflow,
    position: body.style.position,
    top: body.style.top,
    left: body.style.left,
    right: body.style.right,
    width: body.style.width,
    overscrollBehavior: body.style.overscrollBehavior,
  }
  htmlStyleSnapshot = {
    overflow: html.style.overflow,
    overscrollBehavior: html.style.overscrollBehavior,
  }

  html.style.overflow = 'hidden'
  html.style.overscrollBehavior = 'none'
  body.style.overflow = 'hidden'
  body.style.position = 'fixed'
  body.style.top = `-${lockedScrollY}px`
  body.style.left = '0'
  body.style.right = '0'
  body.style.width = '100%'
  body.style.overscrollBehavior = 'none'
  bodyScrollLocked = true
}

const restoreBodyScroll = () => {
  if (!bodyScrollLocked || typeof window === 'undefined' || typeof document === 'undefined') {
    return
  }

  const { body, documentElement: html } = document

  body.style.overflow = bodyStyleSnapshot?.overflow || ''
  body.style.position = bodyStyleSnapshot?.position || ''
  body.style.top = bodyStyleSnapshot?.top || ''
  body.style.left = bodyStyleSnapshot?.left || ''
  body.style.right = bodyStyleSnapshot?.right || ''
  body.style.width = bodyStyleSnapshot?.width || ''
  body.style.overscrollBehavior = bodyStyleSnapshot?.overscrollBehavior || ''

  html.style.overflow = htmlStyleSnapshot?.overflow || ''
  html.style.overscrollBehavior = htmlStyleSnapshot?.overscrollBehavior || ''

  bodyStyleSnapshot = null
  htmlStyleSnapshot = null
  bodyScrollLocked = false

  window.scrollTo(0, lockedScrollY)
  lockedScrollY = 0
}

watch(contactQrVisible, (visible) => {
  if (visible) {
    lockBodyScroll()
  } else {
    restoreBodyScroll()
  }
})

// 跳转到标签搜索
const navigateToTag = (tagName) => {
  router.push({
    path: '/search',
    query: {
      keyword: tagName,
      type: 'tag',
    },
  })
}

// 登录注册处理
const handleLogin = () => {
  if (userStore.isLoggedIn) {
    router.push('/account')
  } else {
    router.push('/login')
  }
}

const handleRegister = () => {
  if (userStore.isLoggedIn) {
    router.push('/account')
  } else {
    router.push('/register')
  }
}

// 加载热门标签
const loadHotTags = async () => {
  try {
    const res = await getHotTags(10)
    hotTags.value = res.data || []
  } catch (error) {
    // 静默处理
  }
}

// 获取社区统计数据
const loadCommunityStats = async () => {
  try {
    const res = await getCommunityStats()
    const data = res.data || {}
    stats.value = {
      articleCount: data.articleCount || 0,
      userCount: data.userCount || 0,
      viewCount: data.viewCount || 0,
      authorCount: data.authorCount || 0,
    }
    // 更新 statsList（用于 StatsCard 组件）
    statsList.value = [
      { value: data.articleCount || 0, label: '文章总数' },
      { value: data.userCount || 0, label: '注册用户' },
      { value: data.viewCount || 0, label: '总阅读量' },
      { value: data.authorCount || 0, label: '活跃作者' },
    ]
    // 数据加载完成后，开始数字动画
    setTimeout(() => {
      animateNumber('articleCount', stats.value.articleCount)
      animateNumber('userCount', stats.value.userCount)
      animateNumber('viewCount', stats.value.viewCount)
      animateNumber('authorCount', stats.value.authorCount)
    }, 500)
  } catch (error) {
    // 静默处理
  }
}

// 数字动画 - 从 0 逐渐增加到目标值，显示整数
const animateNumber = (key, target) => {
  if (target <= 0) {
    animatedStats.value[key] = 0
    return
  }

  const duration = 2000 // 动画持续时间 2 秒
  const frameRate = 16 // 约 60fps
  const totalFrames = duration / frameRate
  const increment = target / totalFrames
  let current = 0
  let frame = 0

  const timer = setInterval(() => {
    frame++
    current += increment

    if (frame >= totalFrames) {
      current = target
      clearInterval(timer)
    }

    // 使用 Math.floor 确保显示整数，不显示小数点
    animatedStats.value[key] = Math.floor(current)
  }, frameRate)
}

onMounted(async () => {
  setTimeout(typeSubtitle, 500)
  await Promise.all([fetchArticleList(), loadHotTags(), loadCommunityStats()])
  // 数据加载完成后，等待 DOM 更新再观察元素
  setTimeout(() => {
    observeElements()
  }, 100)
})

onBeforeUnmount(() => {
  restoreBodyScroll()
})
</script>

<style lang="scss" scoped>
// ===== CSS 变量 =====
.home-container {
  --bg-page: #ffffff;
  --bg-card: #fafafa;
  --bg-subtle: #f5f5f5;
  --text-primary: #1a1a1a;
  --text-secondary: #666666;
  --text-muted: #999999;
  --border: #e5e5e5;
  --accent: #0066ff;
  --accent-gradient: linear-gradient(135deg, #0066ff 0%, #00c2ff 100%);
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.06);
  --shadow-md: 0 8px 24px rgba(0, 0, 0, 0.08);
  --shadow-lg: 0 12px 48px rgba(0, 0, 0, 0.12);

  min-height: 100vh;
  background: var(--bg-page);
  color: var(--text-primary);
}

// ===== 黑夜模式 =====
html.dark {
  .home-container {
    --bg-page: #0a0a0a;
    --bg-card: #111111;
    --bg-subtle: #1a1a1a;
    --text-primary: #ededed;
    --text-secondary: #a0a0a0;
    --text-muted: #666666;
    --border: #333333;
    --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.3);
    --shadow-md: 0 8px 24px rgba(0, 0, 0, 0.4);
    --shadow-lg: 0 12px 48px rgba(0, 0, 0, 0.5);
  }
}

// ===== 探索按钮 =====
.explore-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 24px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--text-primary);
  border: 1px solid rgba(255, 255, 255, 0.4);
  border-radius: 12px;
  font-size: 0.9rem;
  font-weight: 500;
  cursor: pointer;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow:
    0 4px 16px rgba(0, 0, 0, 0.06),
    0 1px 2px rgba(0, 0, 0, 0.04),
    inset 0 1px 0 rgba(255, 255, 255, 0.8);
  transition: all 0.2s ease;

  html.dark & {
    background: rgba(30, 30, 30, 0.75);
    border-color: rgba(255, 255, 255, 0.1);
    box-shadow:
      0 4px 16px rgba(0, 0, 0, 0.3),
      0 1px 2px rgba(0, 0, 0, 0.2),
      inset 0 1px 0 rgba(255, 255, 255, 0.06);
  }

  &:hover {
    background: rgba(255, 255, 255, 0.88);
    box-shadow:
      0 6px 24px rgba(0, 0, 0, 0.08),
      0 2px 4px rgba(0, 0, 0, 0.04),
      inset 0 1px 0 rgba(255, 255, 255, 0.9);
    transform: translateY(-1px);

    html.dark & {
      background: rgba(40, 40, 40, 0.85);
      box-shadow:
        0 8px 32px rgba(0, 0, 0, 0.4),
        0 2px 4px rgba(0, 0, 0, 0.2),
        inset 0 1px 0 rgba(255, 255, 255, 0.08);
    }

    .explore-btn-arrow {
      transform: translateX(3px);
    }
  }

  &:active {
    transform: translateY(0) scale(0.98);
    box-shadow:
      0 2px 8px rgba(0, 0, 0, 0.06),
      0 1px 2px rgba(0, 0, 0, 0.04),
      inset 0 1px 0 rgba(255, 255, 255, 0.6);

    html.dark & {
      box-shadow:
        0 2px 8px rgba(0, 0, 0, 0.3),
        0 1px 2px rgba(0, 0, 0, 0.2),
        inset 0 1px 0 rgba(255, 255, 255, 0.04);
    }
  }

  .explore-btn-arrow {
    display: flex;
    flex-shrink: 0;
    transition: transform 0.2s ease;
  }
}

// ===== Hero 区域 =====
.hero {
  position: relative;
  min-height: 85vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-page);
  overflow: hidden;

  // 简洁渐变背景
  .hero-bg {
    position: absolute;
    inset: 0;
    background: linear-gradient(
      135deg,
      rgba(0, 102, 255, 0.04) 0%,
      transparent 40%,
      rgba(0, 194, 255, 0.03) 100%
    );
    pointer-events: none;

    html.dark & {
      background: linear-gradient(
        135deg,
        rgba(100, 180, 255, 0.06) 0%,
        transparent 40%,
        rgba(0, 102, 255, 0.04) 100%
      );
    }
  }

  .hero-content {
    position: relative;
    z-index: 2;
    text-align: left;
    max-width: 1200px;
    width: 100%;
    padding: 0 24px;
  }

  // 徽章
  .hero-badge {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 8px 16px;
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 9999px;
    font-size: 0.875rem;
    color: var(--text-secondary);
    margin-bottom: 24px;

    .dot {
      width: 6px;
      height: 6px;
      background: #10b981;
      border-radius: 50%;
      animation: pulse 2s ease-in-out infinite;
    }
  }

  // 标题
  .hero-title {
    font-size: 3.5rem;
    font-weight: 700;
    letter-spacing: -3px;
    line-height: 1.1;
    margin-bottom: 24px;
    color: var(--text-primary);

    @media (max-width: 768px) {
      font-size: 2.5rem;
      letter-spacing: -2px;
    }
  }

  // 副标题（打字机）
  .hero-subtitle {
    font-size: 1.25rem;
    color: var(--text-secondary);
    margin-bottom: 48px;
    min-height: 2em;
    display: flex;
    align-items: center;
    gap: 2px;

    .hero-typewriter-text {
      font-weight: 500;
    }

    .hero-typewriter-cursor {
      width: 2px;
      height: 1.4em;
      background: var(--accent);
      animation: cursorBlink 1s step-end infinite;
    }
  }

  // CTA 按钮
  .hero-actions {
    margin-bottom: 48px;
  }

  // 快速导航
  .hero-nav {
    display: flex;
    gap: 16px;
    flex-wrap: wrap;

    @media (max-width: 768px) {
      gap: 10px;
    }

    &-item {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 12px 24px;
      background: var(--bg-card);
      border: 1px solid var(--border);
      border-radius: 9999px;
      font-size: 0.9rem;
      color: var(--text-secondary);
      cursor: pointer;
      transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);

      .icon {
        font-size: 1rem;
      }

      &:hover {
        background: var(--bg-subtle);
        border-color: var(--accent);
        color: var(--accent);
        transform: translateY(-2px);
        box-shadow: 0 4px 12px rgba(0, 102, 255, 0.1);
      }

      @media (max-width: 768px) {
        padding: 12px 18px;
        font-size: 0.85rem;
        gap: 6px;

        .icon {
          font-size: 1rem;
        }
      }
    }
  }
}

// ===== 主内容区 =====
.main-content {
  padding: 80px 24px;
  max-width: 1200px;
  margin: 0 auto;

  .section-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 32px;
    padding-bottom: 20px;
    border-bottom: 1px solid var(--border);
  }

  .section-title {
    font-size: 1.5rem;
    font-weight: 600;
    letter-spacing: -0.01em;
    display: flex;
    align-items: center;
    gap: 12px;
    color: var(--text-primary);

    &::before {
      content: '';
      width: 4px;
      height: 20px;
      background: var(--accent-gradient);
      border-radius: 2px;
    }
  }

  .section-link {
    color: var(--text-secondary);
    text-decoration: none;
    font-size: 0.875rem;
    font-weight: 500;
    display: flex;
    align-items: center;
    gap: 6px;
    transition: color 0.2s ease;

    &:hover {
      color: var(--accent);
    }
  }
}

// ===== 内容网格 =====
.content-wrapper {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 40px;
  margin-top: 60px;

  @media (max-width: 992px) {
    grid-template-columns: 1fr;
  }
}

// ===== 文章网格 =====
.article-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;

  @media (max-width: 992px) {
    grid-template-columns: repeat(2, 1fr);
  }

  @media (max-width: 768px) {
    grid-template-columns: 1fr;
  }
}

// ===== 文章卡片 =====
.article-card {
  display: flex;
  flex-direction: column;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  transition: all 0.3s ease;
  opacity: 0;
  transform: translateY(20px);

  &.visible {
    opacity: 1;
    transform: translateY(0);
  }

  &:hover {
    transform: translateY(-4px);
    box-shadow: var(--shadow-md);
    border-color: rgba(0, 102, 255, 0.2);
  }

  // 骨架屏
  &.skeleton {
    .article-cover-skeleton {
      width: 100%;
      height: 140px;
      background: var(--bg-subtle);
      animation: skeleton-pulse 1.5s ease-in-out infinite;
    }

    .article-content {
      padding: 12px 16px;
    }

    .article-tags-skeleton {
      width: 60px;
      height: 18px;
      background: var(--bg-subtle);
      border-radius: 4px;
      margin-bottom: 8px;
      animation: skeleton-pulse 1.5s ease-in-out infinite;
    }

    .article-title-skeleton {
      width: 100%;
      height: 20px;
      background: var(--bg-subtle);
      border-radius: 4px;
      margin-bottom: 8px;
      animation: skeleton-pulse 1.5s ease-in-out infinite;
    }

    .article-excerpt-skeleton {
      width: 100%;
      height: 32px;
      background: var(--bg-subtle);
      border-radius: 4px;
      margin-bottom: 10px;
      animation: skeleton-pulse 1.5s ease-in-out infinite;
    }

    .article-meta-skeleton {
      width: 100%;
      height: 14px;
      background: var(--bg-subtle);
      border-radius: 4px;
      animation: skeleton-pulse 1.5s ease-in-out infinite;
    }
  }

  .article-image {
    width: 100%;
    height: 140px;
    overflow: hidden;
    background: var(--bg-subtle);

    .article-cover {
      width: 100%;
      height: 100%;
      transition: transform 0.4s ease;
    }

    &:hover .article-cover {
      transform: scale(1.05);
    }

    .image-placeholder,
    .image-error {
      width: 100%;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--text-muted);
      font-size: 24px;
    }
  }

  .article-content {
    padding: 4px 12px 12px;
    display: flex;
    flex-direction: column;
    margin-top: auto; // 将内容推到底部
  }

  .article-tags {
    display: flex;
    gap: 6px;
    margin-bottom: 8px;

    .tag {
      padding: 3px 8px;
      border-radius: 6px;
      font-size: 0.7rem;
      font-weight: 500;

      &.tag-hot {
        background: rgba(239, 68, 68, 0.1);
        color: #dc2626;
      }

      &.tag-new {
        background: rgba(0, 102, 255, 0.1);
        color: var(--accent);
      }
    }
  }

  .article-title {
    font-size: 0.95rem;
    font-weight: 600;
    line-height: 1.4;
    margin-top: 0;
    margin-bottom: 8px;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    color: var(--text-primary);
  }

  .article-excerpt {
    font-size: 0.8rem;
    color: var(--text-secondary);
    line-height: 1.5;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    margin-bottom: 10px;
  }

  .article-meta {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-top: 10px;
    border-top: 1px solid var(--border);
  }

  .article-date {
    font-size: 0.7rem;
    color: var(--text-muted);
  }

  .article-stats {
    display: flex;
    gap: 8px;

    .stat {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 0.75rem;
      color: var(--text-muted);
    }

    .heart-icon {
      font-size: 1rem;
    }
  }
}

// ===== 空状态 =====
.empty-state {
  grid-column: 1 / -1;
  padding: 80px 0;
  text-align: center;
}

// ===== 侧边栏 =====
.sidebar {
  display: flex;
  flex-direction: column;
  gap: 24px;

  @media (max-width: 992px) {
    display: none;
  }
}

// ===== 侧边栏卡片 =====
.sidebar-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px;
}

.sidebar-title {
  font-size: 0.875rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-muted);
  margin-bottom: 10px;
}

// ===== 项目链接 =====
.project-links {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.project-link {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background: var(--bg-page);
  border: 1px solid var(--border);
  border-radius: 8px;
  text-decoration: none;
  color: var(--text-primary);
  transition: all 0.2s ease;
  cursor: pointer;

  &:hover {
    border-color: var(--accent);
    background: rgba(0, 102, 255, 0.04);
  }

  .project-link-icon {
    flex-shrink: 0;

    &.github-icon {
      color: #24292e;

      html.dark & {
        color: #94a3b8;
      }
    }

    &.gitee-icon {
      color: #c71d23;

      html.dark & {
        color: #c71d23;
      }
    }
  }

  .project-link-info {
    flex: 1;

    .project-link-name {
      font-size: 0.875rem;
      font-weight: 500;
      color: var(--text-primary);
    }

    .project-link-desc {
      font-size: 0.75rem;
      color: var(--text-muted);
    }
  }
}

// ===== 标签云 =====
.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-cloud-item {
  padding: 6px 14px;
  background: var(--bg-page);
  border: 1px solid var(--border);
  border-radius: 9999px;
  font-size: 0.8rem;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover {
    border-color: var(--accent);
    color: var(--accent);
  }
}

// ===== 社区统计 =====
.stats-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.stat-item {
  text-align: center;
  padding: 16px;
  background: var(--bg-page);
  border-radius: 8px;
}

.stat-value {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--accent);
  margin-bottom: 4px;
}

.stat-label {
  font-size: 0.75rem;
  color: var(--text-muted);
}

// ===== 页脚 =====
.footer {
  position: relative;
  background: var(--bg-card);
  border-top: 1px solid var(--border);
  padding: 80px 24px 0;
  margin-top: 80px;
  overflow: hidden;

  // 顶部装饰渐变线
  .footer-glow-line {
    position: absolute;
    top: 0;
    left: 50%;
    transform: translateX(-50%);
    width: 200px;
    height: 1px;
    background: linear-gradient(
      90deg,
      transparent,
      var(--accent) 20%,
      var(--accent) 80%,
      transparent
    );
    opacity: 0.6;

    html.dark & {
      opacity: 0.4;
    }
  }

  .footer-content {
    max-width: 1200px;
    margin: 0 auto;
    display: grid;
    grid-template-columns: 1.2fr 1.8fr;
    gap: 80px;
    padding-bottom: 60px;

    @media (max-width: 992px) {
      grid-template-columns: 1fr;
      gap: 48px;
    }
  }

  // 品牌区域
  .footer-brand {
    .footer-logo {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 20px;

      .logo-img {
        width: 32px;
        height: 32px;
        object-fit: contain;
      }

      .logo-text {
        font-size: 1.125rem;
        font-weight: 700;
        color: var(--text-primary);
        letter-spacing: -0.01em;
      }
    }

    .footer-desc {
      font-size: 0.875rem;
      color: var(--text-secondary);
      line-height: 1.7;
      margin-bottom: 28px;
      max-width: 300px;
    }

    // 社交链接
    .footer-social {
      display: flex;
      gap: 8px;
    }

  .social-link {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    border-radius: 8px;
    background: var(--bg-page);
    border: 1px solid var(--border);
    color: var(--text-secondary);
    font: inherit;
    transition: all 0.2s ease;
    cursor: pointer;
    padding: 0;
    text-decoration: none;

    &:hover {
      border-color: var(--accent);
      color: var(--accent);
      transform: translateY(-2px);
    }

    &:focus-visible {
      outline: 2px solid var(--accent);
      outline-offset: 2px;
    }
  }

  .social-link-icon {
    width: 18px;
    height: 18px;
    display: block;
  }

  .social-link-qq {
    color: #12b7f5;
  }

  .social-link-wechat {
    color: #07c160;
  }

  .social-link-email {
    color: #f97316;
  }
  }

  // 导航区域组
  .footer-nav-group {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 40px;

    .footer-column:first-child {
      grid-column: 2;
    }

    .footer-column:last-child {
      grid-column: 3;
    }

    @media (max-width: 768px) {
      grid-template-columns: repeat(2, 1fr);
      gap: 32px;

      .footer-column:first-child,
      .footer-column:last-child {
        grid-column: auto;
      }
    }

    @media (max-width: 480px) {
      grid-template-columns: 1fr;
    }
  }

  .footer-column {
    h4 {
      font-size: 0.75rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--text-muted);
      margin-bottom: 20px;
      padding-bottom: 12px;
      border-bottom: 1px solid var(--border);
    }

    ul {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 12px;

      li {
        a {
          color: var(--text-secondary);
          text-decoration: none;
          font-size: 0.875rem;
          transition: color 0.2s ease;
          cursor: pointer;
          display: inline-flex;
          align-items: center;
          gap: 6px;

          &::before {
            content: '';
            width: 4px;
            height: 4px;
            border-radius: 50%;
            background: var(--border);
            transition: background 0.2s ease;
          }

          &:hover {
            color: var(--accent);

            &::before {
              background: var(--accent);
            }
          }
        }
      }
    }
  }

  // 底部栏
  .footer-bottom {
    max-width: 1200px;
    margin: 0 auto;
    padding: 24px 0;
    border-top: 1px solid var(--border);
    display: flex;
    align-items: center;
    justify-content: flex-end;
    flex-wrap: wrap;
    gap: 12px;

    @media (max-width: 600px) {
      flex-direction: column;
      text-align: center;
    }

    .footer-bottom-right {
      .copyright {
        font-size: 0.8rem;
        color: var(--text-muted);
      }
    }

  }
}

.contact-qr-overlay {
  position: fixed;
  inset: 0;
  z-index: 4000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(15, 23, 42, 0.62);
  backdrop-filter: blur(6px);
}

.contact-qr-panel {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  max-width: min(92vw, 380px);
  padding: 14px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid rgba(226, 232, 240, 0.95);
  box-shadow: 0 24px 64px rgba(15, 23, 42, 0.28);
  overflow: hidden;
}

.contact-qr-image {
  display: block;
  width: min(320px, calc(92vw - 28px));
  max-height: calc(100vh - 120px);
  object-fit: contain;
  border-radius: 14px;
  background: #fff;
}

.contact-qr-close {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 9999px;
  background: rgba(255, 255, 255, 0.72);
  color: #475569;
  cursor: pointer;
  padding: 0;
  opacity: 0.28;
  transform: scale(0.92);
  transition: opacity 0.18s ease, transform 0.18s ease, background 0.18s ease,
    border-color 0.18s ease, color 0.18s ease;
  box-shadow: 0 10px 22px rgba(148, 163, 184, 0.18);

  &:hover {
    background: rgba(255, 255, 255, 0.92);
    color: #334155;
    border-color: rgba(148, 163, 184, 0.34);
  }

  &:focus-visible {
    outline: 2px solid rgba(148, 163, 184, 0.5);
    outline-offset: 2px;
    opacity: 1;
    transform: scale(1);
  }

  svg {
    width: 14px;
    height: 14px;
    fill: none;
    stroke: currentColor;
    stroke-width: 2.4;
    stroke-linecap: round;
  }
}

.contact-qr-panel:hover .contact-qr-close,
.contact-qr-close:focus-visible {
  opacity: 1;
  transform: scale(1);
}

// ===== 动画 =====
// 徽章呼吸灯动画
@keyframes pulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.6;
    transform: scale(1.2);
  }
}

// 打字机光标闪烁
@keyframes cursorBlink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}

// 淡入上浮动画（保留供其他组件使用）
@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes skeleton-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.5;
  }
}
</style>
