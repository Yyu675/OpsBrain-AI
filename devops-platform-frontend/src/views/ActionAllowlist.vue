<script setup lang="ts">
/**
 * 动作白名单（L3）。
 *
 * ── 语义：允许清单，不是禁止清单 ──────────────────────────────
 * 表里没有的动作 = 不允许自动执行。页面文案必须把这点说清楚，
 * 否则用户会把它当成「黑名单」，以为不加就等于放行——方向正好相反。
 *
 * ── 相对纯 CRUD 多做的两件事 ──────────────────────────────────
 * 1. 每行展示「生效后的实际约束」（由后端算好下发）。
 *    条目可以覆盖风险策略，但只能收紧；列表直接显示合并结果，
 *    用户不必在两个页面之间对照推算。
 *
 * 2. 「模拟校验」抽屉。安全配置最糟的失效模式是「以为配好了实际没生效」，
 *    用户改完一堆开关无从确认。这里可以直接问一句
 *    「在 prod 执行 k8s.pod.restart 允许吗」，拿到带原因的明确答复。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  AlertTriangle,
  CheckCircle2,
  FlaskConical,
  Plus,
  RefreshCw,
  Search,
  ShieldAlert,
  ShieldCheck,
  X,
  XCircle,
} from 'lucide-vue-next'

import {
  createAction,
  evaluateAction,
  fetchActionFilterOptions,
  fetchActionStats,
  fetchActions,
  toggleAction,
  updateAction,
  type ActionAllowlistEntry,
  type ActionFilterOptions,
  type ActionPayload,
  type ActionStats,
  type EvaluateResult,
  type RiskLevel,
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

defineOptions({ name: 'ActionAllowlist' })

// ==================== 筛选 ====================

const keyword = ref('')
const category = ref('')
const riskLevel = ref<RiskLevel | ''>('')
/** '' 不限 / 'true' 已启用 / 'false' 已停用 */
const enabledFilter = ref('')

const pagination = useServerPagination({ pageSize: 20 })
const { currentPage, totalPages, pageNumbers, pageStart, pageEnd } = pagination
const totalCount = pagination.total

const RISK_VALUES: RiskLevel[] = [
  'READ_ONLY',
  'DRAFT',
  'CONTROLLED_WRITE',
  'HIGH_RISK_EXECUTION',
]

useUrlFilters([
  defineUrlFilter({ ref: keyword, key: 'q', defaultValue: '', parse: textParser(64) }),
  defineUrlFilter({ ref: category, key: 'category', defaultValue: '', parse: textParser(32) }),
  defineUrlFilter<RiskLevel | ''>({
    ref: riskLevel,
    key: 'risk',
    defaultValue: '',
    parse: enumParser(['', ...RISK_VALUES]),
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

const rows = ref<ActionAllowlistEntry[]>([])
const stats = ref<ActionStats | null>(null)
const options = ref<ActionFilterOptions>({
  categories: [],
  riskLevels: [],
  environments: [],
  knownCategories: [],
})
const loading = ref(false)
const loadError = ref<unknown>(null)

const hasFilters = computed(
  () => !!keyword.value || !!category.value || !!riskLevel.value || !!enabledFilter.value
)

const load = async () => {
  loading.value = true
  loadError.value = null
  try {
    const page = await fetchActions({
      keyword: keyword.value || undefined,
      category: category.value || undefined,
      riskLevel: riskLevel.value || undefined,
      enabled: enabledFilter.value === '' ? undefined : enabledFilter.value === 'true',
      page: currentPage.value,
      size: 20,
    })
    rows.value = page.items ?? []
    pagination.setMeta({ total: page.total ?? 0, totalPages: page.totalPages ?? 1 })
    // 统计与列表分开取：统计是全量的（不随筛选变），
    // 让用户切筛选时顶部的「已启用高危动作数」不会跟着变小而产生误判
    stats.value = await fetchActionStats()
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
}

const loadOptions = async () => {
  try {
    options.value = await fetchActionFilterOptions()
  } catch {
    // 筛选选项拉取失败不阻断主流程——列表仍可用，只是下拉为空。
    // 为它弹错误提示会掩盖真正重要的列表错误
  }
}

onMounted(async () => {
  await loadOptions()
  await load()
})

// 筛选变化重置到第一页并重拉。
// 不重置页码会出现「第 5 页 + 新筛选只有 2 页」→ 空列表，用户以为没数据
watch([keyword, category, riskLevel, enabledFilter], () => {
  if (currentPage.value !== 1) {
    currentPage.value = 1
    return // 页码变化会触发下面的 watch，避免重复请求
  }
  void load()
})
watch(currentPage, () => void load())

const resetFilters = () => {
  keyword.value = ''
  category.value = ''
  riskLevel.value = ''
  enabledFilter.value = ''
}

// ==================== 展示辅助 ====================

const RISK_LABELS: Record<RiskLevel, string> = {
  READ_ONLY: '只读查询',
  DRAFT: '草稿生成',
  CONTROLLED_WRITE: '受控写操作',
  HIGH_RISK_EXECUTION: '高风险执行',
}

const riskLabel = (level: string) => RISK_LABELS[level as RiskLevel] ?? level
const isHighRisk = (level: string) => level === 'HIGH_RISK_EXECUTION'

const envList = (envs: string | null) =>
  (envs ?? '').split(',').map((s) => s.trim()).filter(Boolean)

// ==================== 启停 ====================

/**
 * 切换启用状态。
 *
 * 启用高危动作需二次确认；停用不需要——收紧方向的操作应当无摩擦，
 * 故障当下用户要能一秒关掉某个动作，弹确认框只会拖慢止血。
 */
const toggling = useAsyncAction(
  async (row: ActionAllowlistEntry) => {
    const next = !row.enabled

    if (next && isHighRisk(row.riskLevel)) {
      await ElMessageBox.confirm(
        `即将启用高风险动作「${row.displayName}」。\n\n` +
          `动作标识：${row.actionKey}\n` +
          `生效环境：${row.environments}\n` +
          `目标范围：${row.targetPattern || '（未限制）'}\n\n` +
          '启用后自动化引擎可在满足审批条件时调用该动作。确认继续？',
        '启用高风险动作',
        {
          type: 'warning',
          confirmButtonText: '我已确认，启用',
          cancelButtonText: '取消',
        }
      )
    }

    const updated = await toggleAction(row.id, next, row.version)
    const index = rows.value.findIndex((r) => r.id === row.id)
    if (index >= 0) rows.value[index] = updated
    // 启停会改变「已启用高危动作数」，统计需要同步刷新
    stats.value = await fetchActionStats()
    return updated
  },
  { action: '切换动作状态' }
)

// ==================== 新增 / 编辑 ====================

const formOpen = ref(false)
/** null = 新增；否则为编辑中的条目 ID */
const editingId = ref<number | null>(null)

const emptyForm = (): ActionPayload & { version: number } => ({
  actionKey: '',
  displayName: '',
  description: '',
  category: 'k8s',
  riskLevel: 'READ_ONLY',
  targetPattern: '',
  environments: 'dev',
  paramSchema: '',
  requiresApproval: null,
  maxBlastRadiusCount: null,
  enabled: false,
  version: 0,
})

const form = reactive<ActionPayload & { version: number }>(emptyForm())

const openCreate = () => {
  Object.assign(form, emptyForm())
  editingId.value = null
  formOpen.value = true
}

const openEdit = (row: ActionAllowlistEntry) => {
  Object.assign(form, {
    actionKey: row.actionKey,
    displayName: row.displayName,
    description: row.description ?? '',
    category: row.category,
    riskLevel: row.riskLevel,
    targetPattern: row.targetPattern ?? '',
    environments: row.environments,
    paramSchema: row.paramSchema ?? '',
    requiresApproval: row.requiresApproval,
    maxBlastRadiusCount: row.maxBlastRadiusCount,
    enabled: row.enabled,
    version: row.version,
  })
  editingId.value = row.id
  formOpen.value = true
}

/** 写操作必须指定目标模式——与后端校验保持一致，提前在表单里提示 */
const requiresTargetPattern = computed(
  () => form.riskLevel !== 'READ_ONLY' && form.riskLevel !== 'DRAFT'
)

const formEnvList = computed(() => envList(form.environments))

const toggleFormEnv = (env: string) => {
  const current = formEnvList.value
  const next = current.includes(env) ? current.filter((e) => e !== env) : [...current, env]
  const order = options.value.environments.length
    ? options.value.environments
    : ['prod', 'staging', 'dev']
  form.environments = order.filter((e) => next.includes(e)).join(',')
}

/**
 * 表单校验。
 *
 * 前端校验只为提前反馈，**后端才是边界**——尤其是跨表规则
 * （环境是否超出策略范围、爆炸半径是否超上限），前端拿不到完整策略上下文，
 * 强行在这里复算必然与引擎的判断漂移。这里只查前端能独立判定的项。
 */
const formError = computed<string | null>(() => {
  if (!form.actionKey.trim()) return '动作标识不能为空'
  if (!/^[a-z0-9]+(\.[a-z0-9-]+)+$/.test(form.actionKey.trim().toLowerCase())) {
    return '动作标识需为点分小写标识，如 k8s.pod.restart'
  }
  if (!form.displayName.trim()) return '显示名称不能为空'
  if (!formEnvList.value.length) return '至少选择一个生效环境'
  if (requiresTargetPattern.value && !(form.targetPattern ?? '').trim()) {
    return '写操作必须指定目标匹配模式，留空意味着对所有资源生效'
  }
  if (form.paramSchema && form.paramSchema.trim()) {
    try {
      JSON.parse(form.paramSchema)
    } catch {
      return '参数约束必须是合法 JSON'
    }
  }
  return null
})

const submitting = useAsyncAction(
  async () => {
    if (formError.value) {
      notify.warning(formError.value)
      return
    }

    const payload: ActionPayload = {
      actionKey: form.actionKey.trim(),
      displayName: form.displayName.trim(),
      description: form.description?.trim() || null,
      category: form.category,
      riskLevel: form.riskLevel,
      targetPattern: form.targetPattern?.trim() || null,
      environments: form.environments,
      paramSchema: form.paramSchema?.trim() || null,
      requiresApproval: form.requiresApproval,
      maxBlastRadiusCount: form.maxBlastRadiusCount,
      enabled: form.enabled,
      version: form.version,
    }

    if (editingId.value == null) {
      await createAction(payload)
    } else {
      await updateAction(editingId.value, payload)
    }
    formOpen.value = false
    await load()
  },
  {
    action: editingId.value == null ? '创建动作' : '更新动作',
    successMessage: '已保存',
  }
)

// ==================== 模拟校验 ====================

const evalOpen = ref(false)
const evalActionKey = ref('')
const evalEnvironment = ref('prod')
const evalResult = ref<EvaluateResult | null>(null)

const openEvaluate = (row?: ActionAllowlistEntry) => {
  evalActionKey.value = row?.actionKey ?? ''
  // 默认 prod：用户最想确认的永远是「生产环境上到底能不能跑」
  evalEnvironment.value = 'prod'
  evalResult.value = null
  evalOpen.value = true
}

const evaluating = useAsyncAction(
  async () => {
    if (!evalActionKey.value.trim()) {
      notify.warning('请输入动作标识')
      return
    }
    evalResult.value = await evaluateAction(
      evalActionKey.value.trim(),
      evalEnvironment.value
    )
    return evalResult.value
  },
  { action: '模拟校验' }
)
</script>

<template>
  <div class="allowlist-page">
    <main class="allowlist-main">
      <header class="page-header">
        <div>
          <h1 class="page-title">动作白名单</h1>
          <p class="page-sub">
            自动化引擎允许调用的动作清单。<strong>未登记的动作一律不允许自动执行</strong>——
            这是允许清单，不是禁止清单。
          </p>
        </div>
        <div class="header-actions">
          <button class="btn-ghost" type="button" @click="openEvaluate()">
            <FlaskConical :size="13" /> 模拟校验
          </button>
          <button class="icon-btn" type="button" title="刷新" :disabled="loading" @click="load">
            <RefreshCw :size="14" :class="{ spinning: loading }" />
          </button>
          <button class="btn-primary" type="button" @click="openCreate">
            <Plus :size="13" /> 新增动作
          </button>
        </div>
      </header>

      <!-- 统计条：后两个是风险敞口，值 > 0 时标警示色 -->
      <div v-if="stats" class="stat-strip" role="group" aria-label="白名单概览">
        <div class="stat-badge">
          <span class="stat-label">已登记</span>
          <span class="stat-value">{{ stats.total }}</span>
        </div>
        <div class="stat-badge">
          <span class="stat-label">已启用</span>
          <span class="stat-value">{{ stats.enabledCount }}</span>
        </div>
        <div class="stat-badge" :class="{ 'is-danger': stats.highRiskEnabled > 0 }">
          <span class="stat-label">已启用高危动作</span>
          <span class="stat-value">{{ stats.highRiskEnabled }}</span>
        </div>
        <div class="stat-badge" :class="{ 'is-danger': stats.prodEnabled > 0 }">
          <span class="stat-label">覆盖生产环境</span>
          <span class="stat-value">{{ stats.prodEnabled }}</span>
        </div>
      </div>

      <!-- 筛选栏 -->
      <div class="filter-bar">
        <label class="filter-field filter-search">
          <Search :size="13" class="search-icon" />
          <input
            v-model="keyword"
            class="control has-icon"
            type="search"
            placeholder="搜索标识 / 名称 / 描述"
            maxlength="64"
          />
        </label>
        <label class="filter-field">
          <select v-model="category" class="control">
            <option value="">全部类别</option>
            <option v-for="c in options.categories" :key="c" :value="c">{{ c }}</option>
          </select>
        </label>
        <label class="filter-field">
          <select v-model="riskLevel" class="control">
            <option value="">全部风险等级</option>
            <option v-for="r in options.riskLevels" :key="r.value" :value="r.value">
              {{ r.label }}
            </option>
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

      <DataStateBoundary
        :loading="loading"
        :error="loadError"
        :count="rows.length"
        :filtered="hasFilters"
        empty-title="尚未登记任何动作"
        empty-description="白名单为空意味着引擎不会自动执行任何动作"
        filtered-description="没有符合筛选条件的动作，试试放宽条件"
        :skeleton-rows="6"
        skeleton-height="64px"
        @retry="load"
      >
        <div class="table-wrap">
          <table class="action-table">
            <thead>
              <tr>
                <th class="col-action">动作</th>
                <th class="col-risk">风险等级</th>
                <th class="col-scope">生效范围</th>
                <th class="col-effective">生效约束</th>
                <th class="col-status">状态</th>
                <th class="col-ops"></th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in rows"
                :key="row.id"
                :class="{ 'is-disabled-row': !row.enabled }"
              >
                <td class="col-action">
                  <div class="action-cell">
                    <component
                      :is="isHighRisk(row.riskLevel) ? ShieldAlert : ShieldCheck"
                      :size="14"
                      class="action-icon"
                      :class="{ 'is-high': isHighRisk(row.riskLevel) }"
                    />
                    <div class="action-text">
                      <span class="action-name">{{ row.displayName }}</span>
                      <code class="action-key">{{ row.actionKey }}</code>
                      <span v-if="row.description" class="action-desc">{{ row.description }}</span>
                    </div>
                  </div>
                </td>

                <td class="col-risk">
                  <span class="risk-badge" :class="{ 'is-high': isHighRisk(row.riskLevel) }">
                    {{ riskLabel(row.riskLevel) }}
                  </span>
                  <span class="cell-sub">{{ row.category }}</span>
                </td>

                <td class="col-scope">
                  <div class="env-row">
                    <span
                      v-for="env in envList(row.environments)"
                      :key="env"
                      class="env-chip"
                      :class="{ 'is-prod': env === 'prod' }"
                    >{{ env }}</span>
                  </div>
                  <code v-if="row.targetPattern" class="pattern">{{ row.targetPattern }}</code>
                  <span v-else class="cell-sub warn-text">未限制目标</span>
                </td>

                <td class="col-effective">
                  <!-- 生效值由后端合并下发，前端不复算——复算必然与引擎漂移 -->
                  <span
                    class="pill"
                    :class="row.effectiveRequiresApproval ? 'is-guarded' : 'is-open'"
                  >
                    {{ row.effectiveRequiresApproval ? '需审批' : '免审批' }}
                  </span>
                  <span class="cell-sub">
                    最多影响
                    <strong class="num">{{ row.effectiveBlastRadiusCount ?? '—' }}</strong>
                    个实例
                  </span>
                  <span v-if="row.requiresApproval !== null" class="override-tag">
                    条目已覆盖策略
                  </span>
                </td>

                <td class="col-status">
                  <span class="dot-tag" :class="row.enabled ? 'is-on' : 'is-off'">
                    <i class="dot" />{{ row.enabled ? '已启用' : '已停用' }}
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
                    <button class="btn-ghost btn-xs" type="button" @click="openEdit(row)">
                      编辑
                    </button>
                    <button
                      class="btn-ghost btn-xs"
                      type="button"
                      title="模拟校验"
                      @click="openEvaluate(row)"
                    >
                      <FlaskConical :size="11" />
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
      :title="editingId == null ? '新增动作' : '编辑动作'"
      width="min(680px, calc(100vw - 32px))"
      destroy-on-close
    >
      <div class="form-body">
        <fieldset class="form-group">
          <legend>基本信息</legend>
          <div class="field-row">
            <label class="field">
              <span class="field-label">
                动作标识 <em class="req">*</em>
                <span v-if="editingId != null" class="field-note">创建后不可修改</span>
              </span>
              <input
                v-model="form.actionKey"
                class="control mono"
                type="text"
                placeholder="k8s.pod.restart"
                maxlength="64"
                :disabled="editingId != null"
              />
            </label>
            <label class="field">
              <span class="field-label">显示名称 <em class="req">*</em></span>
              <input
                v-model="form.displayName"
                class="control"
                type="text"
                placeholder="优雅重启 Pod"
                maxlength="64"
              />
            </label>
          </div>
          <label class="field">
            <span class="field-label">描述</span>
            <input
              v-model="form.description"
              class="control"
              type="text"
              placeholder="这个动作做什么、什么场景下会被调用"
              maxlength="255"
            />
          </label>
          <div class="field-row">
            <label class="field">
              <span class="field-label">类别</span>
              <select v-model="form.category" class="control">
                <option v-for="c in options.knownCategories" :key="c" :value="c">{{ c }}</option>
              </select>
            </label>
            <label class="field">
              <span class="field-label">风险等级</span>
              <select v-model="form.riskLevel" class="control">
                <option v-for="r in options.riskLevels" :key="r.value" :value="r.value">
                  {{ r.label }}
                </option>
              </select>
            </label>
          </div>
          <p v-if="isHighRisk(form.riskLevel)" class="field-hint is-warn">
            <AlertTriangle :size="12" />
            高风险执行必须经过审批，且受该等级策略的爆炸半径限制
          </p>
        </fieldset>

        <fieldset class="form-group">
          <legend>生效范围</legend>
          <div class="field">
            <span class="field-label">环境 <em class="req">*</em></span>
            <div class="env-checks">
              <label
                v-for="env in options.environments"
                :key="env"
                class="env-check"
                :class="{ 'is-prod': env === 'prod' }"
              >
                <input
                  type="checkbox"
                  :checked="formEnvList.includes(env)"
                  @change="toggleFormEnv(env)"
                />
                <span>{{ env }}</span>
              </label>
            </div>
            <p class="field-hint">
              不能超出该风险等级策略允许的环境范围，超出时提交会被后端拒绝。
            </p>
          </div>
          <label class="field">
            <span class="field-label">
              目标匹配模式
              <em v-if="requiresTargetPattern" class="req">*</em>
            </span>
            <input
              v-model="form.targetPattern"
              class="control mono"
              type="text"
              placeholder="ns:staging/deploy:order-*"
              maxlength="255"
            />
            <p v-if="requiresTargetPattern" class="field-hint is-warn">
              <AlertTriangle :size="12" />
              写操作必须限定目标，留空意味着对所有资源生效
            </p>
          </label>
        </fieldset>

        <fieldset class="form-group">
          <legend>约束覆盖<span class="legend-note">（留空则跟随风险等级策略）</span></legend>
          <div class="field-row">
            <label class="field">
              <span class="field-label">审批要求</span>
              <select v-model="form.requiresApproval" class="control">
                <option :value="null">跟随策略</option>
                <option :value="true">强制要求审批</option>
              </select>
              <!-- 刻意不提供「强制免审批」选项：只能收紧不能放宽，
                   把它列出来再让后端拒绝，是在让用户白填一遍 -->
            </label>
            <label class="field">
              <span class="field-label">爆炸半径上限（实例数）</span>
              <input
                v-model.number="form.maxBlastRadiusCount"
                class="control"
                type="number"
                min="1"
                max="9999"
                placeholder="跟随策略"
              />
            </label>
          </div>
          <label class="field">
            <span class="field-label">参数约束（JSON）</span>
            <textarea
              v-model="form.paramSchema"
              class="control textarea mono"
              rows="4"
              placeholder="{&quot;replicas&quot;:{&quot;type&quot;:&quot;int&quot;,&quot;min&quot;:1,&quot;max&quot;:10}}"
            />
            <p class="field-hint">
              引擎执行前按此校验模型给出的参数，防止「重启 1 个」被写成「重启 100 个」。
            </p>
          </label>
        </fieldset>

        <label class="switch-row">
          <input v-model="form.enabled" type="checkbox" />
          <span>立即启用</span>
          <span class="field-hint inline">未启用的动作已登记但引擎不会调用</span>
        </label>

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

    <!-- ===== 模拟校验 ===== -->
    <el-dialog
      v-model="evalOpen"
      title="模拟校验"
      width="min(560px, calc(100vw - 32px))"
      destroy-on-close
    >
      <p class="eval-intro">
        问一句「现在这个动作在指定环境能否自动执行」，拿到带原因的明确答复。
        用于改完配置后确认结果，而不是等真出故障时才发现没生效。
      </p>

      <div class="field-row">
        <label class="field">
          <span class="field-label">动作标识</span>
          <input
            v-model="evalActionKey"
            class="control mono"
            type="text"
            placeholder="k8s.pod.restart"
            maxlength="64"
          />
        </label>
        <label class="field">
          <span class="field-label">环境</span>
          <select v-model="evalEnvironment" class="control">
            <option v-for="env in options.environments" :key="env" :value="env">{{ env }}</option>
          </select>
        </label>
      </div>

      <div v-if="evalResult" class="eval-result" :class="evalResult.allowed ? 'is-allow' : 'is-deny'">
        <div class="eval-head">
          <component :is="evalResult.allowed ? CheckCircle2 : XCircle" :size="16" />
          <strong>{{ evalResult.allowed ? '允许自动执行' : '不允许自动执行' }}</strong>
        </div>
        <p class="eval-reason">{{ evalResult.reason }}</p>
        <dl v-if="evalResult.allowed" class="eval-detail">
          <div>
            <dt>审批要求</dt>
            <dd>{{ evalResult.requiresApproval ? '需审批' : '免审批' }}</dd>
          </div>
          <div>
            <dt>单次影响上限</dt>
            <dd class="num">{{ evalResult.blastRadiusCount ?? '—' }} 个实例</dd>
          </div>
          <div>
            <dt>观察窗口</dt>
            <dd class="num">{{ evalResult.cooldownSeconds ?? '—' }} 秒</dd>
          </div>
        </dl>
      </div>

      <template #footer>
        <button class="btn-ghost" type="button" @click="evalOpen = false">关闭</button>
        <button
          class="btn-primary"
          type="button"
          :disabled="evaluating.pending.value"
          @click="evaluating.run()"
        >
          {{ evaluating.pending.value ? '校验中…' : '执行校验' }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.allowlist-page {
  min-height: 100vh;
  background: var(--color-bg);
}

.allowlist-main {
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
  max-width: 68ch;
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
  transition: background 0.15s;

  &:hover:not(:disabled) {
    background: var(--color-fill-light);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
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

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.btn-ghost {
  border: 1px solid var(--color-border-light);
  background: transparent;
  color: var(--color-text-secondary);

  &:hover:not(:disabled) {
    background: var(--color-fill-light);
    color: var(--color-text-primary);
  }
}

.btn-primary {
  border: 1px solid var(--color-primary);
  background: var(--color-primary);
  color: #fff;

  &:hover:not(:disabled) {
    opacity: 0.9;
  }
}

.btn-xs {
  height: 24px;
  padding: 0 8px;
  font-size: 12px;
  border-radius: 6px;
}

.spinning {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

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

  /* 风险敞口 > 0 时用 danger 竖条，让它在一排徽章里跳出来 */
  &.is-danger::before {
    background: var(--color-danger);
  }
}

.stat-label {
  font-size: 12px;
  color: var(--color-text-tertiary);
}

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
  margin-bottom: 12px;
}

.filter-field {
  position: relative;
  display: flex;
}

.filter-search {
  grid-column: span 2;
}

.search-icon {
  position: absolute;
  left: 9px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--color-text-quaternary, var(--color-text-tertiary));
  pointer-events: none;
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

  &.has-icon {
    padding-left: 28px;
  }

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

  &:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }
}

/* ===== 表格 ===== */
.table-wrap {
  border: 1px solid var(--color-border-light);
  border-radius: 10px;
  overflow: hidden;
  background: var(--color-surface);
}

.action-table {
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
    /* 极淡的表头底色，而非常见的深灰条 */
    background: var(--color-fill-lighter);
    border-bottom: 1px solid var(--color-border-light);
  }

  tbody td {
    padding: 10px 12px;
    vertical-align: top;
    border-bottom: 1px solid var(--color-border-lighter, var(--color-border-light));
  }

  tbody tr:last-child td {
    border-bottom: none;
  }

  tbody tr:hover {
    background: var(--color-fill-lighter);
  }

  /* 停用行整体降透明度：一眼区分「开着的」与「登记了但没开的」 */
  tbody tr.is-disabled-row {
    opacity: 0.62;
  }
}

.col-risk { width: 130px; }
.col-scope { width: 200px; }
.col-effective { width: 190px; }
.col-status { width: 100px; }
.col-ops { width: 160px; }

.action-cell {
  display: flex;
  gap: 8px;
}

.action-icon {
  margin-top: 2px;
  flex-shrink: 0;
  color: var(--color-text-quaternary, var(--color-text-tertiary));

  &.is-high {
    color: var(--color-danger);
  }
}

.action-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.action-name {
  font-weight: 600;
  color: var(--color-text-primary);
}

.action-key {
  font-size: 11px;
  font-family: var(--font-mono, ui-monospace, monospace);
  color: var(--color-text-tertiary);
}

.action-desc {
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text-quaternary, var(--color-text-tertiary));
}

.risk-badge {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 7px;
  font-size: 11px;
  font-weight: 500;
  border-radius: 4px;
  border: 1px solid var(--color-border-light);
  background: var(--color-fill-lighter);
  color: var(--color-text-secondary);

  &.is-high {
    color: var(--color-danger);
    border-color: rgb(from var(--color-danger) r g b / 0.28);
    background: rgb(from var(--color-danger) r g b / 0.07);
  }
}

.cell-sub {
  display: block;
  margin-top: 3px;
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.warn-text {
  color: var(--color-warning);
}

.env-row {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
  margin-bottom: 4px;
}

.env-chip {
  display: inline-flex;
  align-items: center;
  height: 19px;
  padding: 0 6px;
  font-size: 11px;
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

.pattern {
  display: block;
  font-size: 11px;
  font-family: var(--font-mono, ui-monospace, monospace);
  color: var(--color-text-tertiary);
  word-break: break-all;
}

.pill {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 7px;
  font-size: 11px;
  font-weight: 500;
  border-radius: 4px;
  border: 1px solid var(--color-border-light);

  &.is-open {
    color: var(--color-warning);
    background: rgb(from var(--color-warning) r g b / 0.08);
    border-color: rgb(from var(--color-warning) r g b / 0.25);
  }

  &.is-guarded {
    color: var(--color-success);
    background: rgb(from var(--color-success) r g b / 0.08);
    border-color: rgb(from var(--color-success) r g b / 0.25);
  }
}

.override-tag {
  display: inline-block;
  margin-top: 3px;
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--color-fill-light);
  color: var(--color-text-tertiary);
}

.num {
  font-variant-numeric: tabular-nums;
}

.dot-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;

  .dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }

  &.is-on { color: var(--color-success); }
  &.is-off { color: var(--color-text-quaternary, var(--color-text-tertiary)); }
}

.ops {
  display: flex;
  gap: 4px;
  justify-content: flex-end;
}

/* ===== 表单 ===== */
.form-body {
  display: grid;
  gap: 14px;
}

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
}

.legend-note {
  margin-left: 4px;
  font-weight: 400;
  text-transform: none;
  letter-spacing: 0;
}

.field-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
  gap: 10px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;

  &:last-child {
    margin-bottom: 0;
  }
}

.field-label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.field-note {
  font-size: 10px;
  color: var(--color-text-quaternary, var(--color-text-tertiary));
}

.req {
  color: var(--color-danger);
  font-style: normal;
}

.field-hint {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text-tertiary);

  &.is-warn {
    color: var(--color-warning);
  }

  &.inline {
    margin: 0;
  }
}

.env-checks {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.env-check {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
  font-family: var(--font-mono, ui-monospace, monospace);
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  background: var(--color-surface);
  cursor: pointer;

  &:has(input:checked) {
    border-color: var(--color-primary);
    background: rgb(from var(--color-primary) r g b / 0.06);
  }

  &.is-prod:has(input:checked) {
    border-color: var(--color-danger);
    background: rgb(from var(--color-danger) r g b / 0.07);
    color: var(--color-danger);
  }
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--color-text-primary);
  cursor: pointer;
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

/* ===== 模拟校验 ===== */
.eval-intro {
  margin: 0 0 14px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-text-tertiary);
}

.eval-result {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: 8px;
  border: 1px solid var(--color-border-light);

  &.is-allow {
    border-color: rgb(from var(--color-success) r g b / 0.25);
    background: rgb(from var(--color-success) r g b / 0.06);
    color: var(--color-success);
  }

  &.is-deny {
    border-color: rgb(from var(--color-danger) r g b / 0.25);
    background: rgb(from var(--color-danger) r g b / 0.06);
    color: var(--color-danger);
  }
}

.eval-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}

.eval-reason {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-text-secondary);
}

.eval-detail {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
  gap: 8px;
  margin: 10px 0 0;
  padding-top: 10px;
  border-top: 1px solid var(--color-border-light);

  dt {
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 0.03em;
    color: var(--color-text-tertiary);
  }

  dd {
    margin: 2px 0 0;
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text-primary);
  }
}
</style>
