<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { BookOpen, Zap, BarChart3, ArrowRight, Plus, Monitor } from 'lucide-vue-next'
import heroImage from '@/assets/image_0_yi19x4.jpg'
import SafeImage from '@/components/common/SafeImage.vue'
import ApiErrorState from '@/components/common/ApiErrorState.vue'
import { getDashboardOverview } from '@/api/dashboard'

interface StatItem {
  label: string
  value: string
}

const loading = ref(true)
const stats = ref<StatItem[]>([])
const loadError = ref<unknown>(null)

const reload = async () => {
  loading.value = true
  loadError.value = null
  try {
    const data = await getDashboardOverview()
    stats.value = [
      { label: '智能问答', value: (data.totalQueries ?? 0).toLocaleString('en-US') },
      { label: '工单总数', value: (data.totalTickets ?? 0).toLocaleString('en-US') },
      { label: '缓存命中率', value: (data.cacheHitRate ?? 0).toFixed(1) + '%' },
      { label: '平均成本', value: (data.avgCostRmb ?? 0).toFixed(4) + ' 元' },
    ]
  } catch (e) {
    loadError.value = e
    stats.value = []
  } finally {
    loading.value = false
  }
}

onMounted(reload)

const features = [
  {
    icon: BookOpen,
    title: '智能知识库',
    description: '自动归类运维文档，AI 驱动语义检索，秒级定位排障方案'
  },
  {
    icon: Zap,
    title: '工单自动化',
    description: '智能工单路由与分级，自动匹配处理人，SLA 实时监控预警'
  },
  {
    icon: BarChart3,
    title: '数据洞察',
    description: '多维度运维数据分析，趋势预测与异常检测，驱动持续优化'
  }
]
</script>

<template>
  <div class="home">
    <!-- Hero Section -->
    <section class="hero-section">
      <div class="hero-container">
        <div class="hero-content">
          <h1 class="hero-title">企业级智能运维平台</h1>
          <p class="hero-subtitle">
            基于 LangChain4j 大模型，整合知识库管理与工单自动化，让运维排障效率提升 10 倍
          </p>
          <div class="hero-actions">
            <RouterLink to="/knowledge" class="btn-primary">
              开始使用
              <ArrowRight :size="16" />
            </RouterLink>
            <RouterLink to="/tickets" class="btn-secondary">
              创建工单
              <Plus :size="16" />
            </RouterLink>
          </div>
        </div>

        <div class="hero-image">
          <SafeImage
            :src="heroImage"
            alt="运维仪表盘预览"
            class="hero-image-img"
            eager
            fallback-text="运维仪表盘预览"
          />
        </div>
      </div>
    </section>

    <!-- Stats Bar -->
    <section v-if="loading" class="stats-section">
      <div class="stats-container">
        <div v-for="n in 4" :key="n" class="stat-item">
          <div class="stat-loading" />
        </div>
      </div>
    </section>
    <section v-else-if="loadError" class="stats-section">
      <div class="stats-container">
        <ApiErrorState :error="loadError" compact retry-label="重新加载" @retry="reload" />
      </div>
    </section>
    <section v-else-if="stats.length > 0" class="stats-section">
      <div class="stats-container">
        <div v-for="stat in stats" :key="stat.label" class="stat-item">
          <div class="stat-value">{{ stat.value }}</div>
          <div class="stat-label">{{ stat.label }}</div>
        </div>
      </div>
    </section>

    <!-- Features Grid -->
    <section class="features-section">
      <div class="features-container">
        <div class="section-header">
          <h2 class="section-title">核心能力</h2>
          <p class="section-desc">端到端覆盖运维全链路，让每一次故障处理更智能、更高效</p>
        </div>

        <div class="features-grid">
          <div v-for="feature in features" :key="feature.title" class="feature-card">
            <div class="feature-icon">
              <component :is="feature.icon" :size="22" />
            </div>
            <h3 class="feature-title">{{ feature.title }}</h3>
            <p class="feature-desc">{{ feature.description }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- CTA Section -->
    <section class="cta-section">
      <div class="cta-container">
        <h2 class="cta-title">准备好提升运维效率了吗？</h2>
        <p class="cta-desc">加入数百家企业的选择，从智能知识库到工单自动化，一站式解决运维难题</p>
        <RouterLink to="/dashboard" class="cta-btn">
          免费试用
          <ArrowRight :size="16" />
        </RouterLink>
      </div>
    </section>

    <!-- Footer -->
    <footer class="footer">
      <div class="footer-container">
        <div class="footer-logo">
          <div class="footer-logo-icon">
            <Monitor :size="16" />
          </div>
          <span class="footer-logo-text">DevOps智能运维</span>
        </div>

        <div class="footer-links">
          <RouterLink to="/knowledge" class="footer-link">知识库</RouterLink>
          <RouterLink to="/tickets" class="footer-link">智能工单</RouterLink>
          <RouterLink to="/dashboard" class="footer-link">数据概览</RouterLink>
          <RouterLink to="/help" class="footer-link">帮助中心</RouterLink>
        </div>

        <div class="footer-copyright">
          © 2026 DevOps智能运维. All rights reserved.
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.home {
  min-height: 100vh;
  background: var(--color-surface);
}

/* Hero Section */
.hero-section {
  background: linear-gradient(135deg, var(--color-primary-dark) 0%, var(--color-primary) 100%);
  position: relative;
  overflow: hidden;
}

.hero-container {
  max-width: 1280px;
  margin: 0 auto;
  padding: 80px 24px;
  display: flex;
  align-items: center;
  gap: 64px;

  @media (max-width: 1024px) {
    flex-direction: column;
    padding: 60px 24px;
    gap: 48px;
    text-align: center;
  }
}

.hero-content {
  flex: 1;
}

.hero-title {
  font-family: var(--font-display);
  font-size: 3rem;
  font-weight: var(--weight-bold);
  color: var(--color-text-inverse);
  margin: 0 0 24px 0;
  line-height: var(--leading-tight);
  letter-spacing: -0.025em;
}

.hero-subtitle {
  font-size: var(--text-lg);
  line-height: var(--leading-relaxed);
  color: rgba(255, 255, 255, 0.8);
  margin: 0 0 40px 0;
  max-width: 560px;

  @media (max-width: 1024px) {
    margin-left: auto;
    margin-right: auto;
  }
}

.hero-actions {
  display: flex;
  gap: 16px;

  @media (max-width: 1024px) {
    justify-content: center;
  }

  @media (max-width: 640px) {
    flex-direction: column;
    align-items: center;
  }
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 12px 32px;
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  font-family: var(--font-body);
  background: white;
  color: var(--color-primary-dark);
  cursor: pointer;
  text-decoration: none;
  transition: all 0.15s ease;

  &:hover {
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  }
}

.btn-secondary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 12px 32px;
  border: 2px solid white;
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  font-family: var(--font-body);
  background: transparent;
  color: white;
  cursor: pointer;
  text-decoration: none;
  transition: all 0.15s ease;

  &:hover {
    background: rgba(255, 255, 255, 0.1);
  }
}

.hero-image {
  flex: 1;
  max-width: 480px;
  width: 100%;
}

.hero-image-img {
  width: 100%;
  height: auto;
  display: block;
  border-radius: var(--radius-lg);
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.25);
}

/* Stats Section - No cards, plain layout */
.stats-section {
  background: white;
  border-top: 1px solid var(--color-border-light);
  border-bottom: 1px solid var(--color-border-light);
}

.stats-container {
  max-width: 1280px;
  margin: 0 auto;
  padding: 40px 24px;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 32px;

  @media (max-width: 768px) {
    grid-template-columns: repeat(2, 1fr);
  }
}

.stat-item {
  text-align: center;
}

.stat-value {
  font-family: var(--font-display);
  font-size: var(--text-3xl);
  font-weight: var(--weight-bold);
  color: var(--color-primary);
  margin-bottom: 4px;
  line-height: 1.2;
}

.stat-label {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
}

.stat-loading {
  width: 80px;
  height: 36px;
  margin: 0 auto 4px;
  background: linear-gradient(90deg, var(--color-bg-sunken, #f1f5f9) 25%, var(--color-border-light, #e2e8f0) 50%, var(--color-bg-sunken, #f1f5f9) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.4s linear infinite;
  border-radius: 6px;
}

@keyframes shimmer {
  0% { background-position: 200% 0 }
  100% { background-position: -200% 0 }
}

/* Features Section */
.features-section {
  padding: 80px 24px;
  background: var(--color-bg);
}

.features-container {
  max-width: 1280px;
  margin: 0 auto;
}

.section-header {
  text-align: center;
  margin-bottom: 56px;
}

.section-title {
  font-family: var(--font-display);
  font-size: var(--text-3xl);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  margin: 0 0 16px 0;
  letter-spacing: -0.02em;
}

.section-desc {
  font-size: var(--text-base);
  color: var(--color-text-secondary);
  margin: 0;
}

.features-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 32px;

  @media (max-width: 768px) {
    grid-template-columns: 1fr;
  }
}

.feature-card {
  background: white;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: 24px;
  box-shadow: var(--shadow-sm);
  transition: all 0.2s ease;

  &:hover {
    box-shadow: var(--shadow-lg);
    transform: translateY(-2px);
  }
}

.feature-icon {
  width: 48px;
  height: 48px;
  margin-bottom: 20px;
  border-radius: 50%;
  background: var(--color-primary-lighter);
  color: var(--color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.feature-title {
  font-family: var(--font-display);
  font-size: var(--text-xl);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  margin: 0 0 12px 0;
}

.feature-desc {
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
  color: var(--color-text-secondary);
  margin: 0;
}

/* CTA Section */
.cta-section {
  padding: 80px 24px;
  background: white;
  text-align: center;
}

.cta-container {
  max-width: 768px;
  margin: 0 auto;
}

.cta-title {
  font-family: var(--font-display);
  font-size: var(--text-3xl);
  font-weight: var(--weight-semibold);
  color: var(--color-text-primary);
  margin: 0 0 16px 0;
  letter-spacing: -0.02em;
}

.cta-desc {
  font-size: var(--text-base);
  color: var(--color-text-secondary);
  margin: 0 0 40px 0;
  max-width: 560px;
  margin-left: auto;
  margin-right: auto;
}

.cta-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 12px 32px;
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  background: var(--color-primary);
  color: white;
  text-decoration: none;
  transition: background 0.15s ease;

  &:hover {
    background: var(--color-primary-light);
  }
}

/* Footer - Dark Theme */
.footer {
  padding: 48px 24px;
  background: var(--color-primary-dark);
}

.footer-container {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;

  @media (max-width: 768px) {
    flex-direction: column;
    align-items: flex-start;
  }
}

.footer-logo {
  display: flex;
  align-items: center;
  gap: 8px;
}

.footer-logo-icon {
  width: 24px;
  height: 24px;
  background: rgba(255, 255, 255, 0.15);
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}

.footer-logo-text {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: white;
}

.footer-links {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
}

.footer-link {
  font-size: var(--text-sm);
  color: rgba(255, 255, 255, 0.6);
  text-decoration: none;
  transition: color 0.15s ease;

  &:hover {
    color: white;
  }
}

.footer-copyright {
  font-size: var(--text-xs);
  color: rgba(255, 255, 255, 0.4);
}
</style>
