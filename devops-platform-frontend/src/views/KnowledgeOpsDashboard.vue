<script setup lang="ts">
/**
 * 知识运营看板（CLAUDE.md Step 3 / PRD §5.5）。
 *
 * 展示知识库的运营健康状况：文档规模、用户反馈（好/坏知识权重）、
 * 引用热度 Top——帮运营者回答「知识库在变好还是变坏、哪些文档最有用」。
 */
import { ref, onMounted } from 'vue'
import { BookOpen, ThumbsUp, ThumbsDown, RefreshCw } from 'lucide-vue-next'
import { http, unwrapBiz } from '@/utils/http'
import { API_ENDPOINTS } from '@/config/api'

defineOptions({ name: 'KnowledgeOpsDashboard' })

interface OpsStats {
  docTotal: number
  docPublished: number
  docDraft: number
  docDeprecated: number
  chunkTotal: number
  feedbackHelpful: number
  feedbackWrong: number
  helpfulRate: number
  topCitedChunks: Array<{ chunkId: number; helpfulCount: number; wrongCount: number; docTitle: string }>
}

const stats = ref<OpsStats | null>(null)
const loading = ref(false)
const loadError = ref<unknown>(null)

const load = async () => {
  loading.value = true
  loadError.value = null
  try {
    const payload = await http.get<unknown>(API_ENDPOINTS.KNOWLEDGE_OPS_STATS)
    stats.value = unwrapBiz<OpsStats>(payload, '加载知识运营统计失败')
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
}

onMounted(load)

const pct = (rate: number) => `${Math.round(rate * 100)}%`
</script>

<template>
  <div class="ops-page">
    <main class="ops-main">
      <div class="page-header">
        <BookOpen class="page-header__icon" :size="22" />
        <div>
          <h1 class="page-title">知识运营看板</h1>
          <p class="page-desc">知识库规模、用户反馈、引用热度的运营健康状况。</p>
        </div>
        <button class="refresh-btn" :disabled="loading" @click="load">
          <RefreshCw :class="['retry-icon', { spinning: loading }]" :size="14" /> 刷新
        </button>
      </div>

      <div v-if="loadError" class="error-tip">加载失败，请稍后重试</div>

      <div v-else-if="stats" class="ops-content">
        <!-- KPI 卡 -->
        <div class="kpi-grid">
          <div class="kpi-card">
            <div class="kpi-value">{{ stats.docTotal }}</div>
            <div class="kpi-label">文档总数</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-value">{{ stats.docPublished }}</div>
            <div class="kpi-label">已发布</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-value">{{ stats.docDraft }}</div>
            <div class="kpi-label">草稿</div>
          </div>
          <div class="kpi-card">
            <div class="kpi-value">{{ stats.chunkTotal }}</div>
            <div class="kpi-label">知识切片</div>
          </div>
        </div>

        <!-- 反馈统计 -->
        <div class="feedback-section">
          <h2 class="section-title">用户反馈</h2>
          <div class="feedback-cards">
            <div class="feedback-card feedback-card--good">
              <ThumbsUp :size="18" />
              <div class="feedback-num">{{ stats.feedbackHelpful }}</div>
              <div class="feedback-label">有帮助</div>
            </div>
            <div class="feedback-card feedback-card--bad">
              <ThumbsDown :size="18" />
              <div class="feedback-num">{{ stats.feedbackWrong }}</div>
              <div class="feedback-label">没帮助</div>
            </div>
            <div class="feedback-card feedback-card--rate">
              <div class="feedback-num">{{ pct(stats.helpfulRate) }}</div>
              <div class="feedback-label">好评率</div>
            </div>
          </div>
        </div>

        <!-- 引用热度 Top -->
        <div class="top-section">
          <h2 class="section-title">引用热度 Top（被用户反馈最多的知识切片）</h2>
          <div v-if="stats.topCitedChunks.length" class="top-list">
            <div v-for="c in stats.topCitedChunks" :key="c.chunkId" class="top-item">
              <span class="top-title">{{ c.docTitle || `切片 #${c.chunkId}` }}</span>
              <span class="top-meta">
                <span class="top-good">+{{ c.helpfulCount }}</span>
                <span class="top-bad">-{{ c.wrongCount }}</span>
              </span>
            </div>
          </div>
          <p v-else class="empty-tip">暂无反馈数据——用户点赞/点踩后这里会沉淀最有价值（或最有问题）的知识</p>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.ops-page { min-height: 100vh; background: var(--surface-0); }
.ops-main { max-width: 1400px; margin: 0 auto; padding: 24px; }
.page-header { display: flex; align-items: center; gap: 14px; margin-bottom: 24px; }
.page-header__icon { color: var(--brand, #2563eb); }
.page-title { margin: 0; font-size: 22px; color: var(--text-1); }
.page-desc { margin: 4px 0 0; font-size: 13px; color: var(--text-3); }
.refresh-btn {
  margin-left: auto; display: inline-flex; align-items: center; gap: 6px;
  padding: 6px 14px; border: 1px solid var(--border-1); border-radius: var(--radius);
  background: var(--surface-1); color: var(--text-2); font-size: 13px; cursor: pointer;
}
.retry-icon.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.error-tip { padding: 24px; text-align: center; color: var(--danger, #dc2626); }

.kpi-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 14px; }
.kpi-card {
  background: var(--surface-1); border: 1px solid var(--border-1);
  border-radius: var(--radius); padding: 18px; text-align: center;
}
.kpi-value { font-size: 28px; font-weight: 700; color: var(--text-1); }
.kpi-label { font-size: 12px; color: var(--text-3); margin-top: 4px; }

.section-title { font-size: 15px; font-weight: 600; color: var(--text-1); margin: 24px 0 12px; }
.feedback-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 14px; }
.feedback-card {
  background: var(--surface-1); border: 1px solid var(--border-1);
  border-radius: var(--radius); padding: 18px; text-align: center;
}
.feedback-card--good { color: var(--success, #16a34a); }
.feedback-card--bad { color: var(--danger, #dc2626); }
.feedback-card--rate { color: var(--brand, #2563eb); }
.feedback-num { font-size: 26px; font-weight: 700; margin-top: 6px; }
.feedback-label { font-size: 12px; color: var(--text-3); margin-top: 4px; }

.top-list { display: flex; flex-direction: column; gap: 8px; }
.top-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 14px; background: var(--surface-1);
  border: 1px solid var(--border-1); border-radius: var(--radius);
}
.top-title { font-size: 13px; color: var(--text-1); }
.top-meta { display: flex; gap: 12px; font-size: 12px; }
.top-good { color: var(--success, #16a34a); font-weight: 600; }
.top-bad { color: var(--danger, #dc2626); font-weight: 600; }
.empty-tip { color: var(--text-3); font-size: 13px; padding: 24px; text-align: center; }
</style>
