<script setup lang="ts">
/**
 * 接入管理（L2）。
 *
 * ── 这个页面的定位 ────────────────────────────────────────────
 * 它是「实时监控 / 趋势分析」两页的**出口**：那两页依赖 Prometheus，
 * 数据源没配好时它们只能显示错误，用户需要一个地方去看「到底哪没通」。
 * 所以本页刻意先于那两页落地。
 *
 * ── 为什么是只读 + 诊断，而不是 CRUD ──────────────────────────
 * 数据源地址来自后端配置（`devops.metrics.prometheus.base-url`，
 * 支持环境变量覆盖）。做成页面可编辑会带来两个问题：
 *   1. 改一个能让后端去连任意内网地址的字段 —— 这是 SSRF 面，
 *      而本页的读权限比治理页宽（一线运维也要能看）；
 *   2. 配置改了要持久化到哪？写库就出现「库里的值和配置文件的值
 *      哪个生效」的二义性，而这类二义性在排障时最耗时间。
 *
 * 所以本页只做三件事：**如实展示当前连的是谁、通不通、不通时怎么办**。
 * 真要改地址，改环境变量重启——这也符合十二要素应用的配置原则。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  AlertTriangle,
  CheckCircle2,
  Database,
  ExternalLink,
  Gauge,
  RefreshCw,
  Server,
  XCircle,
} from 'lucide-vue-next'

import {
  fetchDatasources,
  fetchMetricCatalog,
  type Datasource,
  type MetricMeta,
} from '@/api/metrics'
import DataStateBoundary from '@/components/common/DataStateBoundary.vue'
import { useAsyncAction } from '@/composables/useAsyncAction'
import { parseDate } from '@/utils/time'

defineOptions({ name: 'Integrations' })

// ==================== 数据 ====================

const datasources = ref<Datasource[]>([])
const metrics = ref<MetricMeta[]>([])
const loading = ref(false)
const loadError = ref<unknown>(null)
/** 最近一次检查时刻，让用户知道看到的是多新的状态 */
const lastCheckedAt = ref<number | null>(null)

const load = async () => {
  loading.value = true
  loadError.value = null
  try {
    // 两个接口独立取：目录接口不依赖 Prometheus 可达，
    // 数据源挂了时目录仍能展示「本系统支持哪些指标」
    const [ds, catalog] = await Promise.allSettled([
      fetchDatasources(),
      fetchMetricCatalog(),
    ])

    if (ds.status === 'fulfilled') {
      datasources.value = ds.value.datasources ?? []
    } else {
      // 这个接口后端保证不抛（连不上也返回 reachable:false），
      // 真失败说明是后端自身问题，如实报错而不是伪造一个「不可达」
      throw ds.reason
    }

    metrics.value = catalog.status === 'fulfilled' ? (catalog.value.metrics ?? []) : []
    lastCheckedAt.value = Date.now()
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
}

/** 手动重新检查。用 useAsyncAction 防重入——连点会发多个探测请求 */
const recheck = useAsyncAction(
  async () => {
    await load()
  },
  { action: '检查连接', successMessage: '已重新检查' }
)

// ==================== 自动刷新 ====================

/**
 * 30 秒轮询。
 *
 * 比监控页（10s）慢得多是刻意的：这页看的是「连接状态」，
 * 它不会秒级变化，而每次探测都会真的发一个 HTTP 请求到 Prometheus。
 * 刷太快只是给数据源添无谓负载。
 */
const REFRESH_MS = 30_000
let timer: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  await load()
  timer = setInterval(() => {
    // 上一次还没回来就跳过这一轮，避免请求堆积
    if (!loading.value) void load()
  }, REFRESH_MS)
})

onUnmounted(() => {
  // 不清会导致离开页面后仍在轮询——既浪费也会在登出后继续打接口
  if (timer) clearInterval(timer)
  timer = null
})

// ==================== 派生 ====================

const healthyCount = computed(() => datasources.value.filter((d) => d.reachable).length)
const totalCount = computed(() => datasources.value.length)
const allHealthy = computed(() => totalCount.value > 0 && healthyCount.value === totalCount.value)

/** 延迟着色：本地 Prometheus 正常在个位数毫秒，超过 1s 说明有问题 */
const latencyLevel = (ms?: number): 'good' | 'warn' | 'bad' => {
  if (ms === undefined || ms === null) return 'warn'
  if (ms < 200) return 'good'
  if (ms < 1000) return 'warn'
  return 'bad'
}

/**
 * 把技术错误翻译成「下一步做什么」。
 *
 * 直接把 `ConnectException: Connection refused` 显示给运维没有帮助——
 * 他知道连不上，需要的是「去哪查」。这里按错误特征给出具体动作。
 */
const troubleshoot = (d: Datasource): string[] => {
  if (d.reachable) return []
  const err = (d.error ?? '').toLowerCase()

  if (!d.enabled) {
    return [
      '当前配置为「未启用」（devops.metrics.prometheus.enabled=false）',
      '若需启用：设置环境变量 PROMETHEUS_ENABLED=true 后重启后端',
    ]
  }
  if (err.includes('connect') || err.includes('refused') || err.includes('unreachable')) {
    return [
      `确认 Prometheus 已启动：docker compose -f docker-compose.dev.yml up -d prometheus`,
      `确认地址可达：curl ${d.baseUrl}/-/healthy`,
      '若 Prometheus 部署在其他主机，用环境变量 PROMETHEUS_BASE_URL 指向它',
    ]
  }
  if (err.includes('timeout')) {
    return [
      'Prometheus 响应超时，通常是它自身负载过高或查询积压',
      `打开 ${d.baseUrl}/targets 查看抓取目标是否大量超时`,
      '必要时调大 PROMETHEUS_TIMEOUT_MS（默认 5000ms）',
    ]
  }
  if (err.includes('non-json') || err.includes('非 json')) {
    return [
      `base-url 可能指向了非 Prometheus 服务：${d.baseUrl}`,
      '确认端口正确（docker-compose 默认把容器 9090 映射到宿主机 29090）',
    ]
  }
  return [
    `原始错误：${d.error ?? '未知'}`,
    `手工验证：curl ${d.baseUrl}/-/healthy`,
  ]
}

/**
 * 只显示时分秒——这个时间戳是「最近检查于」，用户关心的是
 * 「几秒前」而不是哪一天，带上日期反而是噪音。
 *
 * 走 parseDate 而非 new Date()：项目有 lint 规则禁止直接 new Date()，
 * 因为后端 LocalDateTime 不带时区，直接解析会跨时区差 12 小时。
 * 这里虽然是本地产生的毫秒数不受该问题影响，但统一入口能避免
 * 「这处能用那处不能用」的判断成本，也让将来改时区策略只需改一处。
 */
const formatTime = (ts: number | null) => {
  const d = parseDate(ts)
  if (!d) return '—'
  return d.toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="integrations-page">
    <main class="integrations-main">
      <header class="page-header">
        <div>
          <h1 class="page-title">接入管理</h1>
          <p class="page-sub">
            管理监控数据源的连接与健康状态。
            <strong>OpsBrain 代理查询 Prometheus，不自建时序存储</strong>——
            指标以监控系统为准，这里只负责确认链路是否通畅。
          </p>
        </div>
        <div class="header-actions">
          <span v-if="lastCheckedAt" class="last-checked">
            最近检查 {{ formatTime(lastCheckedAt) }}
          </span>
          <button
            class="btn-primary"
            type="button"
            :disabled="recheck.pending.value || loading"
            @click="recheck.run()"
          >
            <RefreshCw :size="13" :class="{ spinning: loading }" />
            重新检查
          </button>
        </div>
      </header>

      <DataStateBoundary
        :loading="loading"
        :error="loadError"
        :count="datasources.length"
        empty-title="尚未配置任何数据源"
        empty-description="请在后端配置 devops.metrics.prometheus.base-url"
        :skeleton-rows="2"
        skeleton-height="160px"
        @retry="load"
      >
        <!-- 总体状态条 -->
        <div class="summary" :class="allHealthy ? 'is-ok' : 'is-bad'">
          <component :is="allHealthy ? CheckCircle2 : AlertTriangle" :size="17" />
          <span v-if="allHealthy">
            全部 {{ totalCount }} 个数据源连接正常，监控与趋势分析功能可用
          </span>
          <span v-else>
            {{ totalCount - healthyCount }} / {{ totalCount }} 个数据源不可用，
            <strong>实时监控与趋势分析页将无数据</strong>
          </span>
        </div>

        <!-- 数据源卡片 -->
        <div class="ds-list">
          <article
            v-for="d in datasources"
            :key="d.type"
            class="ds-card"
            :class="d.reachable ? 'is-ok' : 'is-bad'"
          >
            <header class="ds-head">
              <div class="ds-title-wrap">
                <Database :size="17" class="ds-icon" />
                <div>
                  <h2 class="ds-name">{{ d.name }}</h2>
                  <code class="ds-url">{{ d.baseUrl }}</code>
                </div>
              </div>
              <span class="status-pill" :class="d.reachable ? 'is-ok' : 'is-bad'">
                <component :is="d.reachable ? CheckCircle2 : XCircle" :size="13" />
                {{ d.reachable ? '已连接' : '不可用' }}
              </span>
            </header>

            <dl class="ds-meta">
              <div class="meta-item">
                <dt>类型</dt>
                <dd>{{ d.type }}</dd>
              </div>
              <div class="meta-item">
                <dt>集成开关</dt>
                <dd>
                  <span :class="d.enabled ? 'txt-ok' : 'txt-muted'">
                    {{ d.enabled ? '已启用' : '未启用' }}
                  </span>
                </dd>
              </div>
              <div class="meta-item">
                <dt>探测延迟</dt>
                <dd>
                  <span v-if="d.latencyMs !== undefined" class="num" :class="`lat-${latencyLevel(d.latencyMs)}`">
                    {{ d.latencyMs }} ms
                  </span>
                  <span v-else class="txt-muted">—</span>
                </dd>
              </div>
            </dl>

            <!-- 不可达时给「下一步做什么」，而不是只丢一句技术错误 -->
            <div v-if="!d.reachable" class="trouble">
              <p class="trouble-title">
                <AlertTriangle :size="13" /> 排查建议
              </p>
              <ol class="trouble-list">
                <li v-for="(tip, i) in troubleshoot(d)" :key="i">{{ tip }}</li>
              </ol>
            </div>

            <footer v-else class="ds-foot">
              <a
                class="ext-link"
                :href="`${d.baseUrl}/targets`"
                target="_blank"
                rel="noopener noreferrer"
              >
                <ExternalLink :size="12" /> 查看抓取目标
              </a>
              <a
                class="ext-link"
                :href="`${d.baseUrl}/graph`"
                target="_blank"
                rel="noopener noreferrer"
              >
                <ExternalLink :size="12" /> 打开 Prometheus 控制台
              </a>
            </footer>
          </article>
        </div>

        <!-- 可用指标目录 -->
        <section class="catalog">
          <header class="catalog-head">
            <Gauge :size="15" />
            <h2>可查询指标</h2>
            <span class="catalog-count">{{ metrics.length }} 项</span>
          </header>
          <p class="catalog-hint">
            指标语句维护在后端，前端只按 ID 查询——
            避免任意 PromQL 拖垮监控体系，也让指标改名时只需改一处。
          </p>
          <div v-if="metrics.length" class="metric-grid">
            <div v-for="m in metrics" :key="m.id" class="metric-item">
              <div class="metric-top">
                <Server :size="12" />
                <span class="metric-name">{{ m.name }}</span>
                <span class="metric-unit">{{ m.unit }}</span>
              </div>
              <code class="metric-id">{{ m.id }}</code>
              <p class="metric-desc">{{ m.describe }}</p>
            </div>
          </div>
          <p v-else class="catalog-empty">指标目录暂不可用</p>
        </section>
      </DataStateBoundary>
    </main>
  </div>
</template>

<style scoped lang="scss">
.integrations-page {
  min-height: 100vh;
  background: var(--color-bg);
}

.integrations-main {
  max-width: 1180px;
  margin: 0 auto;
  padding: 20px 24px 32px;
}

/* ===== 页头 ===== */
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-title {
  margin: 0 0 3px;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--color-text-primary);
}

.page-sub {
  margin: 0;
  max-width: 70ch;
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text-tertiary);

  strong {
    color: var(--color-text-secondary);
    font-weight: 600;
  }
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.last-checked {
  font-size: 11px;
  color: var(--color-text-quaternary, var(--color-text-tertiary));
  font-variant-numeric: tabular-nums;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 12px;
  font-size: 13px;
  border-radius: 8px;
  border: 1px solid var(--color-primary);
  background: var(--color-primary);
  color: #fff;
  cursor: pointer;

  &:hover:not(:disabled) { opacity: 0.9; }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
}

.spinning { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ===== 总体状态条 ===== */
.summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 14px;
  margin-bottom: 14px;
  border-radius: 8px;
  font-size: 13px;
  border: 1px solid var(--color-border-light);

  strong { font-weight: 600; }

  &.is-ok {
    color: var(--color-success);
    background: rgb(from var(--color-success) r g b / 0.06);
    border-color: rgb(from var(--color-success) r g b / 0.25);
  }

  &.is-bad {
    color: var(--color-danger);
    background: rgb(from var(--color-danger) r g b / 0.06);
    border-color: rgb(from var(--color-danger) r g b / 0.25);
  }
}

/* ===== 数据源卡片 ===== */
.ds-list {
  display: grid;
  gap: 12px;
  margin-bottom: 20px;
}

.ds-card {
  position: relative;
  padding: 16px 18px;
  border: 1px solid var(--color-border-light);
  border-radius: 12px;
  background: var(--color-surface);

  &::before {
    content: '';
    position: absolute;
    left: 0;
    top: 12px;
    bottom: 12px;
    width: 3px;
    border-radius: 0 3px 3px 0;
  }

  &.is-ok::before { background: var(--color-success); }
  &.is-bad::before { background: var(--color-danger); }
}

.ds-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-left: 12px;
}

.ds-title-wrap {
  display: flex;
  align-items: flex-start;
  gap: 9px;
}

.ds-icon {
  margin-top: 2px;
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}

.ds-name {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.ds-url {
  font-size: 11px;
  font-family: var(--font-mono, ui-monospace, monospace);
  color: var(--color-text-tertiary);
  word-break: break-all;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 9px;
  font-size: 12px;
  font-weight: 500;
  border-radius: 6px;
  flex-shrink: 0;
  border: 1px solid var(--color-border-light);

  &.is-ok {
    color: var(--color-success);
    background: rgb(from var(--color-success) r g b / 0.08);
    border-color: rgb(from var(--color-success) r g b / 0.25);
  }

  &.is-bad {
    color: var(--color-danger);
    background: rgb(from var(--color-danger) r g b / 0.08);
    border-color: rgb(from var(--color-danger) r g b / 0.25);
  }
}

.ds-meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
  gap: 10px 20px;
  margin: 14px 0 0 12px;
}

.meta-item {
  dt {
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.03em;
    color: var(--color-text-quaternary, var(--color-text-tertiary));
  }

  dd {
    margin: 3px 0 0;
    font-size: 13px;
    color: var(--color-text-primary);
  }
}

.num { font-variant-numeric: tabular-nums; font-weight: 600; }
.lat-good { color: var(--color-success); }
.lat-warn { color: var(--color-warning); }
.lat-bad { color: var(--color-danger); }
.txt-ok { color: var(--color-success); }
.txt-muted { color: var(--color-text-tertiary); }

/* ===== 排查建议 ===== */
.trouble {
  margin: 14px 0 0 12px;
  padding: 11px 13px;
  border-radius: 8px;
  background: rgb(from var(--color-danger) r g b / 0.05);
  border: 1px solid rgb(from var(--color-danger) r g b / 0.18);
}

.trouble-title {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0 0 7px;
  font-size: 12px;
  font-weight: 600;
  color: var(--color-danger);
}

.trouble-list {
  margin: 0;
  padding-left: 18px;
  display: grid;
  gap: 4px;

  li {
    font-size: 12px;
    line-height: 1.6;
    color: var(--color-text-secondary);
    word-break: break-all;
  }
}

.ds-foot {
  display: flex;
  gap: 14px;
  margin: 14px 0 0 12px;
  padding-top: 11px;
  border-top: 1px solid var(--color-border-lighter, var(--color-border-light));
}

.ext-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--color-primary);
  text-decoration: none;

  &:hover { text-decoration: underline; }
}

/* ===== 指标目录 ===== */
.catalog {
  padding: 16px 18px;
  border: 1px solid var(--color-border-light);
  border-radius: 12px;
  background: var(--color-surface);
}

.catalog-head {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--color-text-secondary);

  h2 {
    margin: 0;
    font-size: 14px;
    font-weight: 600;
    color: var(--color-text-primary);
  }
}

.catalog-count {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 10px;
  background: var(--color-fill-light);
  color: var(--color-text-tertiary);
}

.catalog-hint {
  margin: 6px 0 14px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--color-text-tertiary);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
  gap: 10px;
}

.metric-item {
  padding: 10px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: 8px;
  background: var(--color-fill-lighter);
}

.metric-top {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--color-text-tertiary);
}

.metric-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.metric-unit {
  margin-left: auto;
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--color-fill-light);
  color: var(--color-text-tertiary);
}

.metric-id {
  display: block;
  margin: 3px 0;
  font-size: 11px;
  font-family: var(--font-mono, ui-monospace, monospace);
  color: var(--color-text-tertiary);
}

.metric-desc {
  margin: 0;
  font-size: 11px;
  line-height: 1.55;
  color: var(--color-text-quaternary, var(--color-text-tertiary));
}

.catalog-empty {
  margin: 0;
  font-size: 12px;
  color: var(--color-text-tertiary);
}
</style>
