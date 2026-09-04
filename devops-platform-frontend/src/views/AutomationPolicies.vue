<script setup lang="ts">
/**
 * 自动化策略（L3）。
 *
 * ── 三张表的分工 ──────────────────────────────────────────────
 *   动作白名单   —— 能不能做
 *   风险等级配置 —— 怎么做（审批、爆炸半径、升级）
 *   本页         —— 什么时候做（告警匹配规则）
 *
 * ── 两个不同于普通 CRUD 的设计 ────────────────────────────────
 * 1. **列表顺序即引擎求值顺序**（priority 升序）。
 *    若列表按创建时间排而引擎按 priority 排，用户就无法回答
 *    「为什么是这条策略生效」——而这是排查自动化行为的第一个问题。
 *
 * 2. **演练模式是一等公民**，独立开关 + 独立视觉标识。
 *    自动化最危险的时刻是「刚配好、还没人知道它会匹配到什么」。
 *    关掉演练是本页风险最高的操作，因此它不混在编辑表单里，
 *    而是列表上的独立按钮 + 二次确认。
 *
 * 3. **匹配预演**是本页最有价值的功能。策略配置的核心风险是
 *    匹配范围与预期不符——你以为只圈了 order 服务，实际把整个集群
 *    都包进去了，而这在真实告警来临前无从发现。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  AlertTriangle,
  ArrowDown,
  CheckCircle2,
  FlaskConical,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Search,
  ShieldAlert,
  Trash2,
  X,
  XCircle,
} from 'lucide-vue-next'

import {
  createPolicy,
  deletePolicy,
  fetchActionFilterOptions,
  fetchActions,
  fetchPolicies,
  fetchPolicyStats,
  simulatePolicies,
  togglePolicy,
  togglePolicyDryRun,
  updatePolicy,
  type ActionAllowlistEntry,
  type ActionFilterOptions,
  type AutomationPolicy,
  type PolicyPayload,
  type PolicyStats,
  type SimulateResult,
} from '@/api/governance'
import DataStateBoundary from '@/components/common/DataStateBoundary.vue'
import ServerPagination from '@/components/common/ServerPagination.vue'
import { useAsyncAction } from '@/composables/useAsyncAction'
import { useServerPagination } from '@/composables/useServerPagination'
import {
  defineUrlFilter,
  enumParser,
  positiveIntParser,
  textParser,
  useUrlFilters,
} from '@/composables/useUrlFilters'
import { notify } from '@/utils/notify'

defineOptions({ name: 'AutomationPolicies' })

const ALERT_LEVELS = ['P0', 'P1', 'P2', 'P3', 'P4'] as const
const MODULES = ['K8S', 'ALIYUN_SLB', 'MYSQL', 'NETWORK', 'OTHER'] as const

// ==================== 筛选 ====================

const keyword = ref('')
const environment = ref('')
const enabledFilter = ref('')

const pagination = useServerPagination({ pageSize: 20 })
const { currentPage, totalPages, pageNumbers, pageStart, pageEnd } = pagination
const totalCount = pagination.total

useUrlFilters([
  defineUrlFilter({ ref: keyword, key: 'q', defaultValue: '', parse: textParser(64) }),
  defineUrlFilter({
    ref: environment,
    key: 'env',
    defaultValue: '',
    parse: enumParser(['', 'prod', 'staging', 'dev']),
  }),
  defineUrlFilter({
    ref: enabledFilter,
    key: 'enabled',
    defaultValue: '',
    parse: enumParser(['', 'true', 'false']),
  }),
  defineUrlFilter({
    ref: currentPage,
    key: 'page',
    defaultValue: 1,
    parse: positiveIntParser(10000),
  }),
])

// ==================== 数据 ====================

const rows = ref<AutomationPolicy[]>([])
const stats = ref<PolicyStats | null>(null)
/** 可选动作（只列已启用的——引用停用动作会被后端拒绝） */
const actions = ref<ActionAllowlistEntry[]>([])
const options = ref<ActionFilterOptions>({
  categories: [],
  riskLevels: [],
  environments: ['prod', 'staging', 'dev'],
  knownCategories: [],
})
const loading = ref(false)
const loadError = ref<unknown>(null)

const hasFilters = computed(
  () => !!keyword.value || !!environment.value || !!enabledFilter.value
)

const load = async () => {
  loading.value = true
  loadError.value = null
  try {
    const page = await fetchPolicies({
      keyword: keyword.value || undefined,
      environment: environment.value || undefined,
      enabled: enabledFilter.value === '' ? undefined : enabledFilter.value === 'true',
      page: currentPage.value,
      size: 20,
    })
    rows.value = page.items ?? []
    pagination.setMeta({ total: page.total ?? 0, totalPages: page.totalPages ?? 1 })
    // 统计全量取，不随筛选变——否则「真会动手的策略数」会跟着筛选变小，
    // 让用户误判风险敞口
    stats.value = await fetchPolicyStats()
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
}

const loadRefs = async () => {
  try {
    const [actionPage, opts] = await Promise.all([
      fetchActions({ enabled: true, size: 200 }),
      fetchActionFilterOptions(),
    ])
    actions.value = actionPage.items ?? []
    options.value = opts
  } catch {
    // 参考数据拉取失败不阻断列表——为它弹错误会掩盖真正重要的列表错误
  }
}

onMounted(async () => {
  await loadRefs()
  await load()
})

watch([keyword, environment, enabledFilter], () => {
  if (currentPage.value !== 1) {
    currentPage.value = 1
    return
  }
  void load()
})
watch(currentPage, () => void load())

const resetFilters = () => {
  keyword.value = ''
  environment.value = ''
  enabledFilter.value = ''
}

// ==================== 展示辅助 ====================

const levelList = (s: string | null) =>
  (s ?? '').split(',').map((x) => x.trim()).filter(Boolean)

/** 把四个匹配条件汇成一句人话，列表里比四个字段更好扫 */
const describeMatch = (p: AutomationPolicy): string => {
  const parts: string[] = []
  if (p.matchAlertLevels) parts.push(p.matchAlertLevels)
  if (p.matchModule) parts.push(p.matchModule)
  if (p.matchServicePattern && p.matchServicePattern !== '*') {
    parts.push(`服务 ${p.matchServicePattern}`)
  }
  if (p.matchAlertNamePattern && p.matchAlertNamePattern !== '*') {
    parts.push(p.matchAlertNamePattern)
  }
  return parts.length ? parts.join(' · ') : '（无限制）'
}

const isHighRisk = (level: string | null) => level === 'HIGH_RISK_EXECUTION'

// ==================== 启停 / 演练 ====================

const toggling = useAsyncAction(
  async (row: AutomationPolicy) => {
    const next = !row.enabled
    const updated = await togglePolicy(row.id, next, row.version)
    const i = rows.value.findIndex((r) => r.id === row.id)
    if (i >= 0) rows.value[i] = updated
    stats.value = await fetchPolicyStats()
    return updated
  },
  { action: '切换策略状态' }
)

/**
 * 切换演练模式。
 *
 * 关掉演练要二次确认，且确认框里列出这条策略会做什么——
 * 这是策略从「只记录」变成「真动手」的时刻。
 * 切回演练不确认：回到更安全的状态不该有摩擦。
 */
const togglingDryRun = useAsyncAction(
  async (row: AutomationPolicy) => {
    const next = !row.dryRun

    if (!next) {
      await ElMessageBox.confirm(
        `即将关闭「${row.name}」的演练模式。\n\n` +
          `关闭后，匹配到的告警将真实执行动作：${row.actionDisplayName ?? row.actionKey}\n` +
          `匹配条件：${describeMatch(row)}\n` +
          `生效环境：${row.environment}\n\n` +
          '建议先用「匹配预演」确认匹配范围符合预期。确认继续？',
        '关闭演练模式',
        {
          type: 'warning',
          confirmButtonText: '我已确认，关闭演练',
          cancelButtonText: '取消',
        }
      )
    }

    const updated = await togglePolicyDryRun(row.id, next, row.version)
    const i = rows.value.findIndex((r) => r.id === row.id)
    if (i >= 0) rows.value[i] = updated
    stats.value = await fetchPolicyStats()
    return updated
  },
  { action: '切换演练模式' }
)

const removing = useAsyncAction(
  async (row: AutomationPolicy) => {
    await ElMessageBox.confirm(
      `确定删除策略「${row.name}」？删除后不可恢复。`,
      '删除策略',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await deletePolicy(row.id, row.version)
    await load()
  },
  { action: '删除策略', successMessage: '策略已删除' }
)

// ==================== 新增 / 编辑 ====================

const formOpen = ref(false)
const editingId = ref<number | null>(null)

const emptyForm = (): PolicyPayload & { version: number } => ({
  name: '',
  description: '',
  matchAlertLevels: '',
  matchModule: '',
  matchServicePattern: '*',
  matchAlertNamePattern: '*',
  actionKey: '',
  actionParams: '',
  environment: 'dev',
  priority: 100,
  stopOnMatch: true,
  cooldownMinutes: 30,
  maxExecutionsPerDay: 10,
  // 新建默认演练：直接上线的策略若匹配范围写宽了，第一次触发就是事故
  dryRun: true,
  enabled: false,
  version: 0,
})

const form = reactive<PolicyPayload & { version: number }>(emptyForm())

const openCreate = () => {
  Object.assign(form, emptyForm())
  editingId.value = null
  formOpen.value = true
}

const openEdit = (row: AutomationPolicy) => {
  Object.assign(form, {
    name: row.name,
    description: row.description ?? '',
    matchAlertLevels: row.matchAlertLevels ?? '',
    matchModule: row.matchModule ?? '',
    matchServicePattern: row.matchServicePattern ?? '*',
    matchAlertNamePattern: row.matchAlertNamePattern ?? '*',
    actionKey: row.actionKey,
    actionParams: row.actionParams ?? '',
    environment: row.environment,
    priority: row.priority,
    stopOnMatch: row.stopOnMatch,
    cooldownMinutes: row.cooldownMinutes,
    maxExecutionsPerDay: row.maxExecutionsPerDay,
    dryRun: row.dryRun,
    enabled: row.enabled,
    version: row.version,
  })
  editingId.value = row.id
  formOpen.value = true
}

const formLevels = computed(() => levelList(form.matchAlertLevels ?? ''))

const toggleFormLevel = (lv: string) => {
  const cur = formLevels.value
  const next = cur.includes(lv) ? cur.filter((x) => x !== lv) : [...cur, lv]
  // 按 P0..P4 固定序输出，避免勾选顺序造成的假「已改动」
  form.matchAlertLevels = ALERT_LEVELS.filter((x) => next.includes(x)).join(',')
}

/** 选中的动作，用于表单里展示其风险等级与开放环境 */
const selectedAction = computed(() =>
  actions.value.find((a) => a.actionKey === form.actionKey)
)

/**
 * 前端校验只做能独立判定的项。
 * 跨表规则（动作是否启用、环境是否超出动作范围）交给后端——
 * 前端复算必然与引擎漂移。
 */
const formError = computed<string | null>(() => {
  if (!form.name.trim()) return '策略名不能为空'
  if (!form.actionKey) return '必须选择要执行的动作'

  const noCondition =
    !formLevels.value.length &&
    !form.matchModule &&
    (!form.matchServicePattern || form.matchServicePattern.trim() === '*') &&
    (!form.matchAlertNamePattern || form.matchAlertNamePattern.trim() === '*')
  if (noCondition) {
    return '至少要指定一个匹配条件，否则会对所有告警执行该动作'
  }

  if (form.actionParams && form.actionParams.trim()) {
    try {
      JSON.parse(form.actionParams)
    } catch {
      return '动作参数必须是合法 JSON'
    }
  }
  // 选中动作后提前提示环境不符，省一次往返
  if (selectedAction.value && !selectedAction.value.environments
    .split(',').map((s) => s.trim()).includes(form.environment)) {
    return `动作「${selectedAction.value.displayName}」未在 ${form.environment} 环境开放`
  }
  return null
})

const submitting = useAsyncAction(
  async () => {
    if (formError.value) {
      notify.warning(formError.value)
      return
    }
    const payload: PolicyPayload = {
      name: form.name.trim(),
      description: form.description?.trim() || null,
      matchAlertLevels: form.matchAlertLevels || null,
      matchModule: form.matchModule || null,
      matchServicePattern: form.matchServicePattern?.trim() || null,
      matchAlertNamePattern: form.matchAlertNamePattern?.trim() || null,
      actionKey: form.actionKey,
      actionParams: form.actionParams?.trim() || null,
      environment: form.environment,
      priority: form.priority,
      stopOnMatch: form.stopOnMatch,
      cooldownMinutes: form.cooldownMinutes,
      maxExecutionsPerDay: form.maxExecutionsPerDay,
      dryRun: form.dryRun,
      enabled: form.enabled,
      version: form.version,
    }
    if (editingId.value == null) {
      await createPolicy(payload)
    } else {
      await updatePolicy(editingId.value, payload)
    }
    formOpen.value = false
    await load()
  },
  { action: '保存策略', successMessage: '策略已保存' }
)

// ==================== 匹配预演 ====================

const simOpen = ref(false)
const simInput = reactive({
  level: 'P3',
  module: 'K8S',
  service: 'order-service',
  alertName: 'PodCrashLoopBackOff',
  environment: 'prod',
})
const simResult = ref<SimulateResult | null>(null)

const openSimulate = () => {
  simResult.value = null
  simOpen.value = true
}

const simulating = useAsyncAction(
  async () => {
    simResult.value = await simulatePolicies({ ...simInput })
    return simResult.value
  },
  { action: '匹配预演' }
)

const OUTCOME_LABELS: Record<string, string> = {
  EXECUTE: '直接执行',
  DRY_RUN: '演练（不执行）',
  PENDING_APPROVAL: '待审批',
  BLOCKED: '被拦截',
}
</script>

<template>
  <div class="policy-page">
    <main class="policy-main">
      <header class="page-header">
        <div>
          <h1 class="page-title">自动化策略</h1>
          <p class="page-sub">
            定义「什么告警触发什么动作」。动作能否执行由
            <strong>动作白名单</strong>决定，执行时的审批与影响面由
            <strong>风险等级配置</strong>决定。
          </p>
        </div>
        <div class="header-actions">
          <button class="btn-ghost" type="button" @click="openSimulate">
            <FlaskConical :size="13" /> 匹配预演
          </button>
          <button class="icon-btn" type="button" title="刷新" :disabled="loading" @click="load">
            <RefreshCw :size="14" :class="{ spinning: loading }" />
          </button>
          <button class="btn-primary" type="button" @click="openCreate">
            <Plus :size="13" /> 新增策略
          </button>
        </div>
      </header>

      <div v-if="stats" class="stat-strip" role="group" aria-label="策略概览">
        <div class="stat-badge">
          <span class="stat-label">总策略</span>
          <span class="stat-value">{{ stats.total }}</span>
        </div>
        <div class="stat-badge">
          <span class="stat-label">演练中</span>
          <span class="stat-value">{{ stats.dryRunCount }}</span>
        </div>
        <div class="stat-badge" :class="{ 'is-danger': stats.liveCount > 0 }">
          <span class="stat-label">真实执行</span>
          <span class="stat-value">{{ stats.liveCount }}</span>
        </div>
        <div class="stat-badge" :class="{ 'is-danger': stats.prodLiveCount > 0 }">
          <span class="stat-label">生产环境生效</span>
          <span class="stat-value">{{ stats.prodLiveCount }}</span>
        </div>
      </div>

      <div class="filter-bar">
        <label class="filter-field filter-search">
          <Search :size="13" class="search-icon" />
          <input
            v-model="keyword"
            class="control has-icon"
            type="search"
            placeholder="搜索策略名 / 描述 / 动作"
            maxlength="64"
          />
        </label>
        <label class="filter-field">
          <select v-model="environment" class="control">
            <option value="">全部环境</option>
            <option v-for="e in options.environments" :key="e" :value="e">{{ e }}</option>
          </select>
        </label>
        <label class="filter-field">
          <select v-model="enabledFilter" class="control">
            <option value="">全部状态</option>
            <option value="true">已启用</option>
            <option value="false">已停用</option>
          </select>
        </label>
        <button v-if="hasFilters" class="btn-ghost" type="button" @click="resetFilters">
          <X :size="12" /> 清除筛选
        </button>
      </div>

      <p class="order-hint">
        <ArrowDown :size="12" />
        列表顺序即引擎求值顺序（优先级升序）。命中「命中即停」的策略后，其后的策略不再求值。
      </p>

      <DataStateBoundary
        :loading="loading"
        :error="loadError"
        :count="rows.length"
        :filtered="hasFilters"
        empty-title="尚未配置任何策略"
        empty-description="没有策略时，告警将走默认流程：自动建单，由人工处理"
        filtered-description="没有符合条件的策略，试试放宽筛选"
        :skeleton-rows="5"
        skeleton-height="72px"
        @retry="load"
      >
        <div class="table-wrap">
          <table class="policy-table">
            <thead>
              <tr>
                <th class="col-pri">顺序</th>
                <th class="col-name">策略</th>
                <th class="col-match">匹配条件</th>
                <th class="col-action">执行动作</th>
                <th class="col-state">状态</th>
                <th class="col-ops"></th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in rows"
                :key="row.id"
                :class="{ 'is-disabled-row': !row.enabled }"
              >
                <td class="col-pri">
                  <span class="pri-num">{{ row.priority }}</span>
                  <span v-if="row.stopOnMatch" class="stop-tag" title="命中后不再求值后续策略">
                    命中即停
                  </span>
                </td>

                <td class="col-name">
                  <span class="policy-name">{{ row.name }}</span>
                  <span v-if="row.description" class="cell-sub">{{ row.description }}</span>
                  <span class="env-chip" :class="{ 'is-prod': row.environment === 'prod' }">
                    {{ row.environment }}
                  </span>
                </td>

                <td class="col-match">
                  <div v-if="levelList(row.matchAlertLevels).length" class="level-row">
                    <span
                      v-for="lv in levelList(row.matchAlertLevels)"
                      :key="lv"
                      class="level-chip"
                      :class="{ 'is-critical': lv === 'P0' || lv === 'P1' }"
                    >{{ lv }}</span>
                  </div>
                  <span class="match-text">{{ describeMatch(row) }}</span>
                </td>

                <td class="col-action">
                  <span class="action-name">
                    {{ row.actionDisplayName ?? row.actionKey }}
                  </span>
                  <code class="action-key">{{ row.actionKey }}</code>
                  <span
                    v-if="isHighRisk(row.actionRiskLevel)"
                    class="risk-badge is-high"
                  >
                    <ShieldAlert :size="10" /> 高风险
                  </span>
                </td>

                <td class="col-state">
                  <span class="dot-tag" :class="row.enabled ? 'is-on' : 'is-off'">
                    <i class="dot" />{{ row.enabled ? '已启用' : '已停用' }}
                  </span>
                  <span v-if="row.dryRun" class="mode-tag is-dry">演练</span>
                  <span v-else class="mode-tag is-live">真实执行</span>
                  <!-- 「已启用但不生效」是最需要显式提示的状态：
                       界面说启用了、实际永远不会执行 -->
                  <span
                    v-if="row.enabled && row.effective === false"
                    class="ineffective"
                    :title="row.ineffectiveReason ?? ''"
                  >
                    <AlertTriangle :size="10" /> 不生效
                  </span>
                </td>

                <td class="col-ops">
                  <div class="ops">
                    <button
                      class="btn-ghost btn-xs"
                      type="button"
                      :disabled="toggling.pending.value"
                      @click="toggling.run(row)"
                    >
                      {{ row.enabled ? '停用' : '启用' }}
                    </button>
                    <button
                      class="btn-ghost btn-xs"
                      type="button"
                      :disabled="togglingDryRun.pending.value"
                      :title="row.dryRun ? '关闭演练，策略将真实执行' : '切回演练模式'"
                      @click="togglingDryRun.run(row)"
                    >
                      <Play :size="11" />{{ row.dryRun ? '上线' : '转演练' }}
                    </button>
                    <button class="btn-ghost btn-xs" type="button" @click="openEdit(row)">
                      <Pencil :size="11" />
                    </button>
                    <button
                      class="btn-ghost btn-xs is-danger"
                      type="button"
                      :disabled="removing.pending.value"
                      @click="removing.run(row)"
                    >
                      <Trash2 :size="11" />
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </DataStateBoundary>

      <ServerPagination
        :current-page="currentPage"
        :total-pages="totalPages"
        :total="totalCount"
        :page-start="pageStart"
        :page-end="pageEnd"
        :page-numbers="pageNumbers"
        @page-change="(p: number) => (currentPage = p)"
      />
    </main>

    <!-- ===== 新增 / 编辑 ===== -->
    <el-dialog
      v-model="formOpen"
      :title="editingId == null ? '新增策略' : '编辑策略'"
      width="min(720px, calc(100vw - 32px))"
      destroy-on-close
    >
      <div class="form-body">
        <fieldset class="form-group">
          <legend>基本信息</legend>
          <div class="field-row">
            <label class="field">
              <span class="field-label">策略名 <em class="req">*</em></span>
              <input
                v-model="form.name"
                class="control"
                type="text"
                placeholder="P3 Pod 崩溃自动重启"
                maxlength="64"
              />
            </label>
            <label class="field">
              <span class="field-label">生效环境</span>
              <select v-model="form.environment" class="control">
                <option v-for="e in options.environments" :key="e" :value="e">{{ e }}</option>
              </select>
            </label>
          </div>
          <label class="field">
            <span class="field-label">描述</span>
            <input
              v-model="form.description"
              class="control"
              type="text"
              placeholder="这条策略在什么场景下生效"
              maxlength="255"
            />
          </label>
        </fieldset>

        <fieldset class="form-group">
          <legend>匹配条件<span class="legend-note">（留空或 * 表示不限制）</span></legend>
          <div class="field">
            <span class="field-label">告警级别</span>
            <div class="level-checks">
              <label
                v-for="lv in ALERT_LEVELS"
                :key="lv"
                class="level-check"
                :class="{ 'is-critical': lv === 'P0' || lv === 'P1' }"
              >
                <input
                  type="checkbox"
                  :checked="formLevels.includes(lv)"
                  @change="toggleFormLevel(lv)"
                />
                <span>{{ lv }}</span>
              </label>
            </div>
          </div>
          <div class="field-row">
            <label class="field">
              <span class="field-label">业务模块</span>
              <select v-model="form.matchModule" class="control">
                <option value="">不限</option>
                <option v-for="m in MODULES" :key="m" :value="m">{{ m }}</option>
              </select>
            </label>
            <label class="field">
              <span class="field-label">服务名（支持 * 通配）</span>
              <input
                v-model="form.matchServicePattern"
                class="control mono"
                type="text"
                placeholder="order-*"
                maxlength="128"
              />
            </label>
          </div>
          <label class="field">
            <span class="field-label">告警规则名（支持 * 通配）</span>
            <input
              v-model="form.matchAlertNamePattern"
              class="control mono"
              type="text"
              placeholder="PodCrashLoopBackOff"
              maxlength="128"
            />
          </label>
          <p class="field-hint">
            四个条件是「与」关系，全部满足才算命中。多加一个条件只会收窄范围。
          </p>
        </fieldset>

        <fieldset class="form-group">
          <legend>执行动作</legend>
          <label class="field">
            <span class="field-label">动作 <em class="req">*</em></span>
            <select v-model="form.actionKey" class="control">
              <option value="">请选择</option>
              <option v-for="a in actions" :key="a.actionKey" :value="a.actionKey">
                {{ a.displayName }}（{{ a.actionKey }}）
              </option>
            </select>
            <p class="field-hint">
              只列出<strong>已启用</strong>的动作。需要的动作不在列表里？请先到
              「动作白名单」登记并启用。
            </p>
          </label>
          <div v-if="selectedAction" class="action-preview">
            <span class="preview-item">
              风险等级：<strong>{{ selectedAction.riskLevel }}</strong>
            </span>
            <span class="preview-item">
              开放环境：<strong>{{ selectedAction.environments }}</strong>
            </span>
            <span class="preview-item">
              执行约束：<strong>
                {{ selectedAction.effectiveRequiresApproval ? '需审批' : '免审批' }}
                · 最多 {{ selectedAction.effectiveBlastRadiusCount ?? '—' }} 个实例
              </strong>
            </span>
          </div>
          <label class="field">
            <span class="field-label">动作参数（JSON）</span>
            <textarea
              v-model="form.actionParams"
              class="control textarea mono"
              rows="3"
              placeholder='{"gracePeriodSeconds":30}'
            />
          </label>
        </fieldset>

        <fieldset class="form-group">
          <legend>执行控制</legend>
          <div class="field-row">
            <label class="field">
              <span class="field-label">求值顺序（越小越先）</span>
              <input
                v-model.number="form.priority"
                class="control"
                type="number"
                min="1"
                max="9999"
              />
            </label>
            <label class="field">
              <span class="field-label">冷却期（分钟）</span>
              <input
                v-model.number="form.cooldownMinutes"
                class="control"
                type="number"
                min="0"
                max="1440"
              />
            </label>
            <label class="field">
              <span class="field-label">每日执行上限</span>
              <input
                v-model.number="form.maxExecutionsPerDay"
                class="control"
                type="number"
                min="1"
                max="1000"
              />
            </label>
          </div>
          <p class="field-hint">
            冷却期防止「重启 → 还没起来 → 又告警 → 又重启」的自动化风暴。
          </p>
          <label class="switch-row">
            <input v-model="form.stopOnMatch" type="checkbox" />
            <span>命中即停</span>
            <span class="field-hint inline">命中后不再求值后续策略</span>
          </label>
        </fieldset>

        <fieldset class="form-group is-safety">
          <legend>安全开关</legend>
          <label class="switch-row">
            <input v-model="form.dryRun" type="checkbox" />
            <span>演练模式</span>
            <span class="field-hint inline">照常匹配与记录，但不真正执行</span>
          </label>
          <label class="switch-row">
            <input v-model="form.enabled" type="checkbox" />
            <span>启用策略</span>
          </label>
          <p v-if="!form.dryRun && form.enabled" class="field-hint is-warn">
            <AlertTriangle :size="12" />
            该策略保存后将<strong>立即真实执行</strong>。建议先以演练模式上线，
            用「匹配预演」确认范围后再关闭演练。
          </p>
        </fieldset>

        <p v-if="formError" class="form-error">
          <AlertTriangle :size="13" /> {{ formError }}
        </p>
      </div>

      <template #footer>
        <button class="btn-ghost" type="button" @click="formOpen = false">取消</button>
        <button
          class="btn-primary"
          type="button"
          :disabled="submitting.pending.value || !!formError"
          @click="submitting.run()"
        >
          {{ submitting.pending.value ? '保存中…' : '保存' }}
        </button>
      </template>
    </el-dialog>

    <!-- ===== 匹配预演 ===== -->
    <el-dialog
      v-model="simOpen"
      title="匹配预演"
      width="min(820px, calc(100vw - 32px))"
      destroy-on-close
    >
      <p class="sim-intro">
        给一个假想告警，按引擎真实的求值顺序走一遍，看哪条策略会命中、最终会发生什么。
        用于上线前确认匹配范围符合预期——这是策略配置最容易出错的地方。
      </p>

      <div class="field-row">
        <label class="field">
          <span class="field-label">告警级别</span>
          <select v-model="simInput.level" class="control">
            <option v-for="lv in ALERT_LEVELS" :key="lv" :value="lv">{{ lv }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field-label">业务模块</span>
          <select v-model="simInput.module" class="control">
            <option v-for="m in MODULES" :key="m" :value="m">{{ m }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field-label">环境</span>
          <select v-model="simInput.environment" class="control">
            <option v-for="e in options.environments" :key="e" :value="e">{{ e }}</option>
          </select>
        </label>
      </div>
      <div class="field-row">
        <label class="field">
          <span class="field-label">服务名</span>
          <input v-model="simInput.service" class="control mono" type="text" maxlength="128" />
        </label>
        <label class="field">
          <span class="field-label">告警规则名</span>
          <input v-model="simInput.alertName" class="control mono" type="text" maxlength="128" />
        </label>
      </div>

      <div v-if="simResult" class="sim-result">
        <div
          class="sim-summary"
          :class="simResult.firstEffective ? 'is-matched' : 'is-none'"
        >
          <component :is="simResult.firstEffective ? CheckCircle2 : XCircle" :size="15" />
          <span>{{ simResult.summary }}</span>
        </div>

        <ol class="sim-list">
          <li
            v-for="row in simResult.evaluated"
            :key="row.policyId"
            class="sim-item"
            :class="{
              'is-matched': row.matched,
              'is-skipped': row.skipped,
            }"
          >
            <div class="sim-head">
              <span class="sim-pri">#{{ row.priority }}</span>
              <span class="sim-name">{{ row.policyName }}</span>
              <span
                v-if="row.outcome"
                class="outcome-tag"
                :class="`is-${row.outcome.toLowerCase().replace('_', '-')}`"
              >
                {{ OUTCOME_LABELS[row.outcome] }}
              </span>
              <span v-else-if="row.skipped" class="outcome-tag is-skipped">未被求值</span>
              <span v-else class="outcome-tag is-nomatch">未命中</span>
            </div>
            <p class="sim-reason">{{ row.reason }}</p>
          </li>
        </ol>
        <p v-if="!simResult.evaluated.length" class="sim-empty">
          当前没有启用中的策略。
        </p>
      </div>

      <template #footer>
        <button class="btn-ghost" type="button" @click="simOpen = false">关闭</button>
        <button
          class="btn-primary"
          type="button"
          :disabled="simulating.pending.value"
          @click="simulating.run()"
        >
          {{ simulating.pending.value ? '预演中…' : '开始预演' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.policy-page {
  min-height: 100vh;
  background: var(--color-bg);
}

.policy-main {
  max-width: 1520px;
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
  max-width: 72ch;
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
  gap: 6px;
  flex-shrink: 0;
}

/* ===== 按钮 ===== */
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--color-border-light);
  border-radius: 8px;
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;

  &:hover:not(:disabled) { background: var(--color-fill-light); }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
}

.btn-ghost,
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 32px;
  padding: 0 12px;
  font-size: 13px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, opacity 0.15s;

  &:disabled { opacity: 0.5; cursor: not-allowed; }
}

.btn-ghost {
  border: 1px solid var(--color-border-light);
  background: transparent;
  color: var(--color-text-secondary);

  &:hover:not(:disabled) {
    background: var(--color-fill-light);
    color: var(--color-text-primary);
  }

  &.is-danger:hover:not(:disabled) {
    color: var(--color-danger);
    border-color: rgb(from var(--color-danger) r g b / 0.3);
    background: rgb(from var(--color-danger) r g b / 0.06);
  }
}

.btn-primary {
  border: 1px solid var(--color-primary);
  background: var(--color-primary);
  color: #fff;

  &:hover:not(:disabled) { opacity: 0.9; }
}

.btn-xs {
  height: 24px;
  padding: 0 8px;
  font-size: 12px;
  border-radius: 6px;
}

.spinning { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ===== 统计条 ===== */
.stat-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.stat-badge {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 28px;
  padding: 0 10px 0 9px;
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  background: var(--color-fill-lighter);
  overflow: hidden;

  &::before {
    content: '';
    position: absolute;
    left: 0;
    top: 0;
    bottom: 0;
    width: 2px;
    background: var(--color-text-tertiary);
  }

  &.is-danger::before { background: var(--color-danger); }
}

.stat-label { font-size: 12px; color: var(--color-text-tertiary); }

.stat-value {
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-primary);
}

/* ===== 筛选栏 ===== */
.filter-bar {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}

.filter-field { position: relative; display: flex; }
.filter-search { grid-column: span 2; }

.search-icon {
  position: absolute;
  left: 9px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--color-text-quaternary, var(--color-text-tertiary));
  pointer-events: none;
}

.order-hint {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0 0 10px;
  font-size: 11px;
  color: var(--color-text-tertiary);
}

/* ===== 控件 ===== */
.control {
  width: 100%;
  height: 32px;
  padding: 0 9px;
  font-size: 13px;
  font-family: inherit;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface);
  color: var(--color-text-primary);

  &.has-icon { padding-left: 28px; }

  &.mono {
    font-family: var(--font-mono, ui-monospace, monospace);
    font-size: 12px;
  }

  &.textarea {
    height: auto;
    padding: 8px 9px;
    line-height: 1.5;
    resize: vertical;
  }

  &:focus {
    outline: none;
    border-color: var(--color-primary);
    box-shadow: 0 0 0 3px rgb(from var(--color-primary) r g b / 0.1);
  }

  &:disabled { opacity: 0.55; cursor: not-allowed; }
}

/* ===== 表格 ===== */
.table-wrap {
  border: 1px solid var(--color-border-light);
  border-radius: 10px;
  overflow: hidden;
  background: var(--color-surface);
}

.policy-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;

  thead th {
    padding: 9px 12px;
    text-align: left;
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--color-text-tertiary);
    background: var(--color-fill-lighter);
    border-bottom: 1px solid var(--color-border-light);
  }

  tbody td {
    padding: 10px 12px;
    vertical-align: top;
    border-bottom: 1px solid var(--color-border-lighter, var(--color-border-light));
  }

  tbody tr:last-child td { border-bottom: none; }
  tbody tr:hover { background: var(--color-fill-lighter); }
  tbody tr.is-disabled-row { opacity: 0.62; }
}

.col-pri { width: 92px; }
.col-match { width: 240px; }
.col-action { width: 220px; }
.col-state { width: 150px; }
.col-ops { width: 190px; }

.pri-num {
  display: block;
  font-size: 15px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-primary);
}

.stop-tag {
  display: inline-block;
  margin-top: 2px;
  padding: 1px 5px;
  font-size: 10px;
  border-radius: 3px;
  background: var(--color-fill-light);
  color: var(--color-text-tertiary);
}

.policy-name {
  display: block;
  font-weight: 600;
  color: var(--color-text-primary);
}

.cell-sub {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text-tertiary);
}

.env-chip {
  display: inline-flex;
  align-items: center;
  height: 18px;
  margin-top: 4px;
  padding: 0 6px;
  font-size: 10px;
  font-family: var(--font-mono, ui-monospace, monospace);
  border-radius: 4px;
  border: 1px solid var(--color-border-light);
  background: var(--color-fill-lighter);
  color: var(--color-text-secondary);

  &.is-prod {
    color: var(--color-danger);
    border-color: rgb(from var(--color-danger) r g b / 0.3);
    background: rgb(from var(--color-danger) r g b / 0.07);
  }
}

.level-row {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
  margin-bottom: 4px;
}

.level-chip {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 5px;
  font-size: 10px;
  font-weight: 600;
  border-radius: 3px;
  border: 1px solid var(--color-border-light);
  background: var(--color-fill-lighter);
  color: var(--color-text-secondary);

  /* P0/P1 标红：它们必然走人机协同，视觉上要能一眼分辨 */
  &.is-critical {
    color: var(--color-danger);
    border-color: rgb(from var(--color-danger) r g b / 0.3);
    background: rgb(from var(--color-danger) r g b / 0.07);
  }
}

.match-text {
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text-tertiary);
  word-break: break-all;
}

.action-name {
  display: block;
  color: var(--color-text-primary);
}

.action-key {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  font-family: var(--font-mono, ui-monospace, monospace);
  color: var(--color-text-tertiary);
  word-break: break-all;
}

.risk-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-top: 3px;
  height: 18px;
  padding: 0 5px;
  font-size: 10px;
  border-radius: 3px;

  &.is-high {
    color: var(--color-danger);
    border: 1px solid rgb(from var(--color-danger) r g b / 0.28);
    background: rgb(from var(--color-danger) r g b / 0.07);
  }
}

.dot-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;

  .dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
  &.is-on { color: var(--color-success); }
  &.is-off { color: var(--color-text-quaternary, var(--color-text-tertiary)); }
}

.mode-tag {
  display: inline-block;
  margin-top: 4px;
  padding: 1px 6px;
  font-size: 10px;
  border-radius: 3px;

  &.is-dry {
    color: var(--color-info, var(--color-text-secondary));
    background: var(--color-fill-light);
  }

  /* 真实执行用警示色——这是「会动手」的状态 */
  &.is-live {
    color: var(--color-warning);
    background: rgb(from var(--color-warning) r g b / 0.1);
  }
}

/* 「已启用但不生效」：界面说启用了、实际永远不会执行，必须显式提示 */
.ineffective {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-top: 4px;
  padding: 1px 5px;
  font-size: 10px;
  border-radius: 3px;
  color: var(--color-danger);
  background: rgb(from var(--color-danger) r g b / 0.08);
  cursor: help;
}

.ops {
  display: flex;
  gap: 3px;
  justify-content: flex-end;
  flex-wrap: wrap;
}

/* ===== 表单 ===== */
.form-body { display: grid; gap: 14px; }

.form-group {
  padding: 12px 14px;
  border: 1px solid var(--color-border-light);
  border-radius: 8px;
  background: var(--color-fill-lighter);

  legend {
    padding: 0 6px;
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--color-text-tertiary);
  }

  /* 安全开关组用警示描边，与普通字段区分 */
  &.is-safety {
    border-color: rgb(from var(--color-warning) r g b / 0.3);
    background: rgb(from var(--color-warning) r g b / 0.04);
  }
}

.legend-note {
  margin-left: 4px;
  font-weight: 400;
  text-transform: none;
  letter-spacing: 0;
}

.field-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 10px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;

  &:last-child { margin-bottom: 0; }
}

.field-label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.req { color: var(--color-danger); font-style: normal; }

.field-hint {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text-tertiary);

  &.is-warn { color: var(--color-warning); }
  &.inline { margin: 0; }

  strong { font-weight: 600; }
}

.level-checks,
.env-checks {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.level-check {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  background: var(--color-surface);
  cursor: pointer;

  &:has(input:checked) {
    border-color: var(--color-primary);
    background: rgb(from var(--color-primary) r g b / 0.06);
  }

  &.is-critical:has(input:checked) {
    border-color: var(--color-danger);
    background: rgb(from var(--color-danger) r g b / 0.07);
    color: var(--color-danger);
  }
}

.action-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  font-size: 11px;
  color: var(--color-text-tertiary);

  strong {
    color: var(--color-text-primary);
    font-weight: 600;
  }
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--color-text-primary);
  cursor: pointer;

  &:last-of-type { margin-bottom: 0; }
}

.form-error {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 8px 10px;
  font-size: 12px;
  border-radius: 6px;
  color: var(--color-danger);
  background: rgb(from var(--color-danger) r g b / 0.07);
  border: 1px solid rgb(from var(--color-danger) r g b / 0.2);
}

/* ===== 预演 ===== */
.sim-intro {
  margin: 0 0 14px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-text-tertiary);
}

.sim-result { margin-top: 16px; }

.sim-summary {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  margin-bottom: 12px;

  &.is-matched {
    color: var(--color-success);
    background: rgb(from var(--color-success) r g b / 0.07);
    border: 1px solid rgb(from var(--color-success) r g b / 0.25);
  }

  &.is-none {
    color: var(--color-text-secondary);
    background: var(--color-fill-light);
    border: 1px solid var(--color-border-light);
  }
}

.sim-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 6px;
}

.sim-item {
  padding: 9px 11px;
  border: 1px solid var(--color-border-light);
  border-radius: 7px;
  background: var(--color-surface);

  &.is-matched {
    border-color: rgb(from var(--color-primary) r g b / 0.35);
    background: rgb(from var(--color-primary) r g b / 0.04);
  }

  /* 未被求值的策略降透明度——它们不参与本次决策 */
  &.is-skipped { opacity: 0.55; }
}

.sim-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.sim-pri {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--color-text-tertiary);
}

.sim-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--color-text-primary);
}

.outcome-tag {
  display: inline-flex;
  align-items: center;
  height: 19px;
  padding: 0 7px;
  font-size: 11px;
  font-weight: 500;
  border-radius: 4px;
  border: 1px solid var(--color-border-light);
  background: var(--color-fill-lighter);
  color: var(--color-text-secondary);

  &.is-execute {
    color: var(--color-warning);
    border-color: rgb(from var(--color-warning) r g b / 0.3);
    background: rgb(from var(--color-warning) r g b / 0.09);
  }

  &.is-pending-approval {
    color: var(--color-primary);
    border-color: rgb(from var(--color-primary) r g b / 0.3);
    background: rgb(from var(--color-primary) r g b / 0.07);
  }

  &.is-blocked {
    color: var(--color-danger);
    border-color: rgb(from var(--color-danger) r g b / 0.3);
    background: rgb(from var(--color-danger) r g b / 0.07);
  }
}

.sim-reason {
  margin: 5px 0 0;
  font-size: 11px;
  line-height: 1.55;
  color: var(--color-text-tertiary);
}

.sim-empty {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--color-text-tertiary);
}
</style>
