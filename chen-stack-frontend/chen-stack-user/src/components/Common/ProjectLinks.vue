<template>
  <div class="project-links-section">
    <div class="project-card">
      <!-- 卡片头部 -->
      <div class="project-header">
        <div class="project-title">
          <el-icon class="title-icon"><Star /></el-icon>
          <span class="title-text">开源项目</span>
        </div>
        <p class="project-description">如果这个项目对你有帮助，欢迎给个 Star 支持</p>
      </div>

      <!-- 链接列表 -->
      <div class="project-links">
        <div class="project-link project-link-placeholder">
          <div class="link-content">
            <span class="link-name">项目源码整理中</span>
            <span class="link-subtitle">后续统一发布</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { Star } from '@element-plus/icons-vue'
</script>

<style lang="scss" scoped>
// ===== 开源项目链接卡片 - CSS 变量支持黑夜模式 =====
.project-links-section {
  --bg-card: #ffffff;
  --bg-link: #f8fafc;
  --bg-link-hover: #f1f5f9;
  --text-primary: #1e293b;
  --text-muted: #94a3b8;
  --border: #e2e8f0;
  --accent: #3b82f6;
  --star-color: #f59e0b;

  // 卡片主体：顶部品牌色线条，无渐变背景
  .project-card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-top: 3px solid var(--accent);
    border-radius: 12px;
    padding: 20px;

    // 卡片头部
    .project-header {
      margin-bottom: 16px;

      .project-title {
        display: flex;
        align-items: center;
        gap: 7px;
        margin-bottom: 6px;

        // 星星图标：固定颜色，无脉冲动画
        .title-icon {
          font-size: 15px;
          color: var(--star-color);
        }

        .title-text {
          font-size: 0.9rem;
          font-weight: 600;
          color: var(--text-primary);
        }
      }

      .project-description {
        font-size: 0.775rem;
        color: var(--text-muted);
        margin: 0;
        line-height: 1.5;
      }
    }

    // 链接列表
    .project-links {
      display: flex;
      flex-direction: column;
      gap: 8px;

      // 单个链接行：hover 只变背景色
      .project-link {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 11px 12px;
        background: var(--bg-link);
        border: 1px solid var(--border);
        border-radius: 8px;
        text-decoration: none;
        transition: background 0.18s ease;

        &:hover {
          background: var(--bg-link-hover);
        }

        // 图标容器：各平台用对应浅色背景
        .link-icon {
          width: 34px;
          height: 34px;
          border-radius: 7px;
          display: flex;
          align-items: center;
          justify-content: center;
          flex-shrink: 0;

          &.gitee-icon {
            background: rgba(199, 29, 35, 0.07);
          }

          &.github-icon {
            background: rgba(36, 41, 46, 0.06);
          }
        }

        // 链接文字
        .link-content {
          flex: 1;
          display: flex;
          flex-direction: column;
          gap: 2px;

          .link-name {
            font-size: 0.875rem;
            font-weight: 600;
            color: var(--text-primary);
          }

          .link-subtitle {
            font-size: 0.72rem;
            color: var(--text-muted);
          }
        }

        // 箭头图标
        .link-arrow {
          font-size: 13px;
          color: var(--text-muted);
          flex-shrink: 0;
        }
      }
    }
  }
}

// 黑夜模式覆盖
html.dark {
  .project-links-section {
    --bg-card: #1e293b;
    --bg-link: #0f172a;
    --bg-link-hover: #1a2540;
    --text-primary: #f1f5f9;
    --text-muted: #64748b;
    --border: #334155;

    // GitHub 图标在深色背景下需要调亮
    .github-icon svg path {
      fill: #94a3b8;
    }
  }
}
</style>
