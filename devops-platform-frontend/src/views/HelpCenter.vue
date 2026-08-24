<script setup lang="ts">
import { ref, computed } from 'vue'
import type { Component } from 'vue'
import { useRouter } from 'vue-router'
import {
  Search, ChevronDown, Rocket, BookOpen, Ticket, Settings, Monitor
} from 'lucide-vue-next'

const router = useRouter()

interface Faq {
  id: number
  question: string
  answer: string
  category: string
  expanded: boolean
}

const faqs = ref<Faq[]>([
  {
    id: 1,
    question: '如何创建第一个工单？',
    answer: '进入「智能工单」页面，点击右上角「创建工单」按钮。填写工单标题、描述、选择分类和优先级后提交即可。系统会根据填写内容自动进行分类建议和责任人分配。',
    category: '工单系统',
    expanded: false
  },
  {
    id: 2,
    question: '工单的优先级如何设置？',
    answer: '工单优先级分为四个等级：紧急（影响生产环境的严重故障）、高（影响业务但有临时方案）、中（需要处理但不紧急）、低（优化建议或次要问题）。系统也会根据 AI 分析自动建议优先级。',
    category: '工单系统',
    expanded: false
  },
  {
    id: 3,
    question: 'AI 助手如何帮助我解决问题？',
    answer: 'AI 助手会自动分析工单内容，提供根因分析、解决方案推荐、相关文档检索等功能。它会基于历史数据和知识库，给出最可能有效的解决方案。',
    category: '快速入门',
    expanded: false
  },
  {
    id: 4,
    question: '如何搜索知识库文档？',
    answer: '在知识库页面的搜索框中输入关键词，系统会智能检索标题、内容、标签等字段。您也可以通过左侧的分类导航浏览特定类别的文档。',
    category: '知识库管理',
    expanded: false
  },
  {
    id: 5,
    question: '数据概览中的指标多久更新一次？',
    answer: '工单、对话、缓存统计等数据来自后端实时计算。Prometheus 实时监控数据待 L2 阶段接入后展示。',
    category: '快速入门',
    expanded: false
  },
  {
    id: 6,
    question: '如何设置告警通知？',
    answer: '告警通知能力属于 L2 阶段的规划能力（先接入钉钉机器人，后续扩展企微、短信/电话）。当前阶段系统内的通知可在「系统设置」中开关。',
    category: '系统管理',
    expanded: false
  },
  {
    id: 7,
    question: '如何集成现有的监控系统？',
    answer: '平台已预留 Prometheus / Alertmanager Webhook 接入（L2 阶段），告警可自动生成工单。接入指南正在编写中，将在实时监控模块上线时开放。',
    category: '系统管理',
    expanded: false
  },
  {
    id: 8,
    question: '数据概览支持自定义报表吗？',
    answer: '数据概览当前提供工单、知识库等核心指标的实时统计。自定义报表与导出能力在后续阶段规划中，届时会支持按需组合指标与定时推送。',
    category: '快速入门',
    expanded: false
  }
])

interface Category {
  icon: Component
  title: string
  description: string
  routeTo?: string
}

const categories: Category[] = [
  { icon: Rocket, title: '快速入门', description: '平台安装、配置和基本使用流程' },
  { icon: BookOpen, title: '知识库管理', description: '文档创建、分类、标签和检索', routeTo: '/knowledge' },
  { icon: Ticket, title: '工单系统', description: '工单创建、流转规则和处理', routeTo: '/tickets' },
  { icon: Settings, title: '系统管理', description: '用户权限、集成配置和系统设置' }
]

const searchQuery = ref('')
const activeCategory = ref<string>('')

const filteredFaqs = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  return faqs.value.filter(faq => {
    const matchQuery = !q || faq.question.toLowerCase().includes(q) || faq.answer.toLowerCase().includes(q)
    const matchCategory = !activeCategory.value || faq.category === activeCategory.value
    return matchQuery && matchCategory
  })
})

const toggleFaq = (faq: Faq) => {
  faq.expanded = !faq.expanded
}

const selectCategory = (category: Category) => {
  if (category.routeTo) {
    router.push(category.routeTo)
    return
  }
  if (activeCategory.value === category.title) {
    activeCategory.value = ''
  } else {
    activeCategory.value = category.title
  }
}

const clearFilters = () => {
  searchQuery.value = ''
  activeCategory.value = ''
}
// 搜索为纯本地实时过滤（filteredFaqs 响应式），输入即筛，无需"回车检索"的假动作。
// FAQ 目前为静态帮助文案（非运营数据）；接入后端帮助内容管理属 L2 规划。
</script>

<template>
  <div class="help-center">
    <!-- ===== Hero / Search ===== -->
    <section class="hero-section">
      <div class="hero-container">
        <h1 class="devops-h1 hero-title">帮助中心</h1>
        <p class="hero-subtitle">查找常见问题解答和使用指南</p>
        <div class="search-box">
          <Search class="search-icon" :size="20" />
          <input
            v-model="searchQuery"
            type="text"
            class="search-input"
            placeholder="搜索帮助文档、常见问题..."
          />
        </div>
      </div>
    </section>

    <main class="main-wrap">
      <!-- ===== Category Cards ===== -->
      <section class="categories-section">
        <div class="categories-grid">
          <div
            v-for="category in categories"
            :key="category.title"
            class="category-card"
            :class="{ active: !category.routeTo && activeCategory === category.title }"
            @click="selectCategory(category)"
          >
            <div class="category-icon">
              <component :is="category.icon" :size="24" />
            </div>
            <h3 class="category-title">{{ category.title }}</h3>
            <p class="category-desc">{{ category.description }}</p>
          </div>
        </div>
      </section>

      <!-- ===== FAQ ===== -->
      <section class="faq-section">
        <h2 class="section-title">常见问题</h2>
        <div v-if="searchQuery || activeCategory" class="faq-toolbar">
          <span class="faq-filter-hint">
            当前筛选：{{ searchQuery ? `「${searchQuery}」` : '' }} {{ activeCategory ? activeCategory : '' }}
          </span>
          <button class="btn-clear" @click="clearFilters">清除筛选</button>
        </div>

        <div v-if="filteredFaqs.length === 0" class="empty-state">
          未找到匹配的常见问题，试试其他关键词。
        </div>

        <div v-else class="faq-panel">
          <div
            v-for="faq in filteredFaqs"
            :key="faq.id"
            class="faq-item"
            :class="{ expanded: faq.expanded }"
          >
            <button class="faq-question" @click="toggleFaq(faq)">
              <span>{{ faq.question }}</span>
              <ChevronDown
                class="faq-icon"
                :class="{ rotated: faq.expanded }"
                :size="20"
              />
            </button>
            <div class="faq-answer-wrapper" :class="{ open: faq.expanded }">
              <div class="faq-answer">{{ faq.answer }}</div>
            </div>
          </div>
        </div>
      </section>

      <!-- ===== Contact ===== -->
      <section class="contact-section">
        <h2 class="contact-title">没有找到答案？</h2>
        <p class="contact-desc">我们的技术支持团队随时为您服务</p>
        <div class="contact-actions">
          <RouterLink to="/tickets" class="btn-primary">
            提交工单
          </RouterLink>
          <button class="btn-outline is-disabled" type="button" disabled title="在线咨询将在 L2 阶段接入">
            在线咨询（即将上线）
          </button>
        </div>
      </section>
    </main>

    <!-- ===== Footer ===== -->
    <footer class="footer">
      <div class="footer-container">
        <div class="footer-logo">
          <div class="footer-logo-icon">
            <Monitor :size="16" />
          </div>
          <span class="footer-logo-text">DevOps智能运维</span>
        </div>
        <div class="footer-links">
          <a href="javascript:void(0)" class="footer-link">使用条款</a>
          <a href="javascript:void(0)" class="footer-link">隐私政策</a>
          <a href="javascript:void(0)" class="footer-link">联系我们</a>
        </div>
        <p class="footer-copyright">© 2026 DevOps智能运维. 保留所有权利.</p>
      </div>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.help-center {
  min-height: 100vh;
  background: var(--color-bg);
}

/* ===== Hero ===== */
.hero-section {
  padding: 64px 24px 48px;
  text-align: center;
}

.hero-container {
  max-width: 720px;
  margin: 0 auto;
}

.hero-title {
  margin: 0 0 12px;
}

.hero-subtitle {
  font-size: var(--text-lg);
  color: var(--color-text-secondary);
  margin: 0 0 32px;
}

.search-box {
  position: relative;
  max-width: 576px;
  margin: 0 auto;
}

.search-icon {
  position: absolute;
  left: 20px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--color-text-tertiary);
  pointer-events: none;
}

.search-input {
  width: 100%;
  height: 48px;
  padding: 0 20px 0 48px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  outline: none;
  transition: box-shadow 0.15s ease, border-color 0.15s ease;
  box-sizing: border-box;

  &:focus {
    border-color: var(--color-primary-light);
    box-shadow: 0 0 0 3px var(--color-primary-lighter);
  }

  &::placeholder {
    color: var(--color-text-tertiary);
  }
}

/* ===== Main ===== */
.main-wrap {
  max-width: 1280px;
  margin: 0 auto;
  padding: 0 24px 24px;
}

/* Category Cards */
.categories-section {
  padding-bottom: 64px;
}

.categories-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 24px;

  @media (max-width: 1024px) {
    grid-template-columns: repeat(2, 1fr);
  }

  @media (max-width: 640px) {
    grid-template-columns: 1fr;
  }
}

.category-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: 24px;
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary-light);
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
  }

  &.active {
    border-color: var(--color-primary);
    box-shadow: 0 0 0 2px var(--color-primary-lighter);
  }
}

.category-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--color-primary-lighter);
  color: var(--color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
}

.category-title {
  font-family: var(--font-display);
  font-size: var(--text-lg);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  margin: 0 0 6px 0;
}

.category-desc {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  margin: 0;
}

/* FAQ */
.faq-section {
  padding-bottom: 64px;
}

.section-title {
  font-family: var(--font-display);
  font-size: var(--text-3xl);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  text-align: center;
  margin: 0 0 32px;
  letter-spacing: -0.02em;
}

.faq-toolbar {
  max-width: 768px;
  margin: 0 auto 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.faq-filter-hint {
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}

.btn-clear {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  font-size: var(--text-xs);
  font-family: var(--font-body);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary);
    color: var(--color-primary);
  }
}

.empty-state {
  max-width: 768px;
  margin: 0 auto;
  padding: 40px 24px;
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--text-sm);
  background: var(--color-surface);
  border: 1px dashed var(--color-border-light);
  border-radius: var(--radius-md);
}

.faq-panel {
  max-width: 768px;
  margin: 0 auto;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.faq-item {
  border-bottom: 1px solid var(--color-border-light);

  &:last-child {
    border-bottom: none;
  }
}

.faq-question {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 16px 24px;
  border: none;
  background: transparent;
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  color: var(--color-text-primary);
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover {
    background: var(--color-surface-hover);
  }
}

.faq-icon {
  color: var(--color-text-tertiary);
  flex-shrink: 0;
  transition: transform 0.2s ease;

  &.rotated {
    transform: rotate(180deg);
    color: var(--color-primary);
  }
}

.faq-answer-wrapper {
  max-height: 0;
  overflow: hidden;
  transition: max-height 0.3s ease;

  &.open {
    max-height: 320px;
  }
}

.faq-answer {
  padding: 0 24px 16px;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  line-height: var(--leading-relaxed);
}

/* Contact */
.contact-section {
  max-width: 768px;
  margin: 0 auto 64px;
  background: var(--color-primary-lighter);
  border-radius: var(--radius-md);
  padding: 40px 24px;
  text-align: center;
}

.contact-title {
  font-family: var(--font-display);
  font-size: var(--text-2xl);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  margin: 0 0 8px;
  letter-spacing: -0.02em;
}

.contact-desc {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  margin: 0 0 32px;
}

.contact-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  flex-wrap: wrap;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 40px;
  padding: 0 24px;
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  background: var(--color-primary);
  color: #fff;
  text-decoration: none;
  transition: background 0.15s ease;

  &:hover {
    background: var(--color-primary-light);
  }
}

.btn-outline {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 40px;
  padding: 0 24px;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  background: transparent;
  color: var(--color-primary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    background: var(--color-primary);
    color: #fff;
  }

  &.is-disabled {
    border-color: var(--color-border);
    color: var(--color-text-tertiary);
    cursor: not-allowed;

    &:hover {
      background: transparent;
      color: var(--color-text-tertiary);
    }
  }
}

/* Footer */
.footer {
  border-top: 1px solid var(--color-border-light);
  background: var(--color-surface);
  padding: 40px 24px;
}

.footer-container {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
}

.footer-logo {
  display: flex;
  align-items: center;
  gap: 8px;
}

.footer-logo-icon {
  width: 24px;
  height: 24px;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.footer-logo-text {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-primary);
}

.footer-links {
  display: flex;
  gap: 24px;
}

.footer-link {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
  text-decoration: none;

  &:hover {
    color: var(--color-primary);
  }
}

.footer-copyright {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}
</style>