<template>
  <div class="about-page">
    <div class="container">
      <section class="hero-card">
        <div class="hero-copy">
          <div class="hero-kicker">About Chen Stack</div>
          <h1>关于辰栈</h1>
          <p>
            辰栈是一个 AI 辅助技术博客管理系统，面向技术内容创作、社区交流和项目展示，
            让内容生产、互动协作与后台管理能够更顺畅地串联起来。
          </p>
        </div>
      </section>

      <section class="content-grid">
        <article class="info-card">
          <div class="card-head">
            <span class="card-tag">Capabilities</span>
            <h2>项目能力</h2>
          </div>
          <div class="feature-grid">
            <div
              v-for="feature in featureList"
              :key="feature.title"
              class="feature-item"
            >
              <div class="feature-icon">{{ feature.icon }}</div>
              <div class="feature-copy">
                <h3>{{ feature.title }}</h3>
                <p>{{ feature.description }}</p>
              </div>
            </div>
          </div>
        </article>

        <article class="info-card stack-card">
          <div class="card-head">
            <span class="card-tag">Stack</span>
            <h2>技术栈</h2>
          </div>
          <div class="stack-list">
            <span v-for="stack in stackList" :key="stack" class="stack-pill">
              {{ stack }}
            </span>
          </div>

          <div class="repo-panel">
            <div>
              <h3>项目源码</h3>
              <p>查看完整工程结构、模块拆分与实现细节。</p>
            </div>
            <a
              href="https://github.com/zcxzzz188/zcx-chen-stack"
              target="_blank"
              rel="noopener noreferrer"
              class="repo-link"
            >
              前往 GitHub 源码仓库
            </a>
          </div>
        </article>
      </section>
    </div>
  </div>
</template>

<script setup>
import { useSeoMeta } from '@/plugins/seo'

useSeoMeta({
  title: '关于辰栈',
  description:
    '了解辰栈的产品定位、能力边界与技术栈，查看 AI 辅助技术博客管理系统的核心模块。',
  keywords: '关于辰栈, AI博客系统, 技术社区, Vue3, Spring Boot',
})

const featureList = [
  {
    icon: '✍️',
    title: '文章创作',
    description: '支持技术文章撰写、编辑与内容沉淀，提升创作效率。',
  },
  {
    icon: '🗂️',
    title: '专栏管理',
    description: '围绕主题组织内容，帮助系列化输出和长期维护。',
  },
  {
    icon: '💬',
    title: '评论互动',
    description: '围绕文章展开讨论，让读者反馈与作者回应形成闭环。',
  },
  {
    icon: '📨',
    title: '私信交流',
    description: '提供用户之间的点对点沟通能力，方便进一步协作。',
  },
  {
    icon: '🤖',
    title: 'AI 标题/摘要辅助',
    description: '利用 AI 生成标题和摘要建议，帮助快速整理表达重点。',
  },
  {
    icon: '🖼️',
    title: '图片上传与审核',
    description: '支持内容配图上传、存储与审核流程，保障展示体验。',
  },
  {
    icon: '🛠️',
    title: '后台内容管理',
    description: '面向管理侧提供内容治理、审核与运营支撑能力。',
  },
]

const stackList = [
  'Spring Boot',
  'Vue 3',
  'MySQL',
  'Redis',
  'RabbitMQ',
  'MinIO',
  'Docker',
]
</script>

<style lang="scss" scoped>
.about-page {
  min-height: 100vh;
  padding: 56px 24px 88px;
  background:
    radial-gradient(circle at top left, rgba(14, 165, 233, 0.14), transparent 32%),
    radial-gradient(circle at top right, rgba(16, 185, 129, 0.12), transparent 28%),
    linear-gradient(180deg, #f8fbff 0%, #f4f7fb 48%, #eef3f8 100%);
}

.container {
  max-width: 1200px;
  margin: 0 auto;
}

.hero-card,
.info-card {
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(148, 163, 184, 0.16);
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(12px);
}

.hero-card {
  padding: 40px;
  border-radius: 28px;
  margin-bottom: 28px;
}

.hero-copy {
  max-width: 760px;

  .hero-kicker {
    display: inline-flex;
    align-items: center;
    padding: 6px 12px;
    border-radius: 999px;
    background: rgba(14, 165, 233, 0.1);
    color: #0369a1;
    font-size: 0.78rem;
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  h1 {
    margin: 18px 0 14px;
    font-size: clamp(2rem, 3vw, 3rem);
    color: #0f172a;
    letter-spacing: -0.03em;
  }

  p {
    margin: 0;
    font-size: 1rem;
    line-height: 1.85;
    color: #475569;
  }
}

.content-grid {
  display: grid;
  grid-template-columns: 1.3fr 0.9fr;
  gap: 24px;
}

.info-card {
  border-radius: 24px;
  padding: 28px;
}

.card-head {
  margin-bottom: 20px;

  .card-tag {
    display: inline-block;
    margin-bottom: 10px;
    font-size: 0.76rem;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: #64748b;
  }

  h2 {
    margin: 0;
    font-size: 1.5rem;
    color: #0f172a;
  }
}

.feature-grid {
  display: grid;
  gap: 14px;
}

.feature-item {
  display: grid;
  grid-template-columns: 52px 1fr;
  gap: 14px;
  padding: 16px;
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.96), rgba(241, 245, 249, 0.92));
  border: 1px solid rgba(226, 232, 240, 0.9);
}

.feature-icon {
  width: 52px;
  height: 52px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.35rem;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: inset 0 0 0 1px rgba(226, 232, 240, 0.9);
}

.feature-copy {
  h3 {
    margin: 0 0 6px;
    font-size: 1rem;
    color: #0f172a;
  }

  p {
    margin: 0;
    font-size: 0.92rem;
    line-height: 1.7;
    color: #64748b;
  }
}

.stack-card {
  display: flex;
  flex-direction: column;
}

.stack-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.stack-pill {
  padding: 9px 14px;
  border-radius: 999px;
  font-size: 0.92rem;
  color: #0f172a;
  background: rgba(226, 232, 240, 0.55);
  border: 1px solid rgba(203, 213, 225, 0.8);
}

.repo-panel {
  margin-top: auto;
  padding-top: 28px;

  h3 {
    margin: 0 0 8px;
    font-size: 1.1rem;
    color: #0f172a;
  }

  p {
    margin: 0 0 16px;
    line-height: 1.7;
    color: #64748b;
  }
}

.repo-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 0 18px;
  border-radius: 14px;
  text-decoration: none;
  font-weight: 600;
  color: #ffffff;
  background: linear-gradient(135deg, #0f172a, #1e293b);
  transition: transform 0.2s ease, box-shadow 0.2s ease;

  &:hover {
    transform: translateY(-1px);
    box-shadow: 0 14px 28px rgba(15, 23, 42, 0.18);
  }
}

@media (max-width: 960px) {
  .content-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .about-page {
    padding: 40px 16px 72px;
  }

  .hero-card,
  .info-card {
    padding: 22px;
    border-radius: 22px;
  }

  .feature-item {
    grid-template-columns: 1fr;
  }
}
</style>
