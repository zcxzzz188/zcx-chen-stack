<template>
  <div class="contact-page">
    <div class="container">
      <section class="hero-card">
        <div class="hero-copy">
          <div class="hero-kicker">Contact</div>
          <h1>联系方式</h1>
          <p>欢迎通过 QQ、微信、邮箱或 GitHub 与项目作者取得联系。</p>
        </div>
      </section>

      <section class="contact-grid">
        <article class="contact-card qr-card">
          <div class="card-head">
            <span class="card-tag">QQ</span>
            <h2>QQ 联系</h2>
          </div>
          <img src="/contact-qr/qq-qr.png" alt="QQ 联系二维码" class="qr-image" />
        </article>

        <article class="contact-card qr-card">
          <div class="card-head">
            <span class="card-tag">WeChat</span>
            <h2>微信联系</h2>
          </div>
          <img src="/contact-qr/wechat-qr.png" alt="微信联系二维码" class="qr-image" />
        </article>

        <article class="contact-card info-card">
          <div class="card-head">
            <span class="card-tag">Email</span>
            <h2>邮箱</h2>
          </div>
          <p class="info-value">2236440482@qq.com</p>
          <button type="button" class="action-button ghost" @click="copyEmail">
            点击复制邮箱
          </button>
        </article>

        <article class="contact-card info-card">
          <div class="card-head">
            <span class="card-tag">GitHub</span>
            <h2>项目源码</h2>
          </div>
          <p class="info-text">查看完整项目源码、模块结构与实现细节。</p>
          <a
            href="https://github.com/zcxzzz188/zcx-chen-stack"
            target="_blank"
            rel="noopener noreferrer"
            class="action-button"
          >
            前往 GitHub 仓库
          </a>
        </article>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { useSeoMeta } from '@/plugins/seo'

useSeoMeta({
  title: '联系方式',
  description: '查看辰栈项目的 QQ、微信、邮箱与 GitHub 联系方式。',
  keywords: '辰栈联系方式, QQ, 微信, 邮箱, GitHub',
})

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
</script>

<style lang="scss" scoped>
.contact-page {
  min-height: 100vh;
  padding: 56px 24px 88px;
  background:
    radial-gradient(circle at top left, rgba(20, 184, 166, 0.12), transparent 30%),
    radial-gradient(circle at top right, rgba(59, 130, 246, 0.12), transparent 28%),
    linear-gradient(180deg, #f9fbfd 0%, #f2f6fa 100%);
}

.container {
  max-width: 1200px;
  margin: 0 auto;
}

.hero-card,
.contact-card {
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(148, 163, 184, 0.16);
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(12px);
}

.hero-card {
  padding: 36px 40px;
  border-radius: 28px;
  margin-bottom: 28px;
}

.hero-copy {
  .hero-kicker {
    display: inline-flex;
    padding: 6px 12px;
    border-radius: 999px;
    background: rgba(16, 185, 129, 0.12);
    color: #047857;
    font-size: 0.78rem;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  h1 {
    margin: 18px 0 12px;
    font-size: clamp(2rem, 3vw, 3rem);
    color: #0f172a;
    letter-spacing: -0.03em;
  }

  p {
    margin: 0;
    color: #475569;
    line-height: 1.8;
  }
}

.contact-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
}

.contact-card {
  border-radius: 24px;
  padding: 24px;
}

.card-head {
  margin-bottom: 18px;

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
    font-size: 1.35rem;
    color: #0f172a;
  }
}

.qr-card {
  display: flex;
  flex-direction: column;
}

.qr-image {
  width: 100%;
  max-width: 320px;
  border-radius: 20px;
  border: 1px solid rgba(226, 232, 240, 0.9);
  background: #ffffff;
}

.info-card {
  display: flex;
  flex-direction: column;
}

.info-value {
  margin: 0 0 16px;
  font-size: 1.05rem;
  font-weight: 600;
  color: #0f172a;
}

.info-text {
  margin: 0 0 16px;
  line-height: 1.75;
  color: #64748b;
}

.action-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 0 18px;
  border-radius: 14px;
  border: 0;
  font-size: 0.95rem;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
  color: #ffffff;
  background: linear-gradient(135deg, #0f172a, #1e293b);
  transition: transform 0.2s ease, box-shadow 0.2s ease;

  &:hover {
    transform: translateY(-1px);
    box-shadow: 0 14px 28px rgba(15, 23, 42, 0.18);
  }
}

.action-button.ghost {
  color: #0f172a;
  background: rgba(226, 232, 240, 0.58);
  border: 1px solid rgba(203, 213, 225, 0.85);
  box-shadow: none;
}

@media (max-width: 900px) {
  .contact-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .contact-page {
    padding: 40px 16px 72px;
  }

  .hero-card,
  .contact-card {
    padding: 22px;
    border-radius: 22px;
  }
}
</style>
