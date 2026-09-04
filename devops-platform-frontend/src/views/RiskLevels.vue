<script setup lang="ts">
/**
 * 风险等级配置（L3）。
 *
 * ── 这个页面配置什么 ──────────────────────────────────────────
 * 四个风险等级各自的自动化约束：要不要审批、能不能自动执行、
 * 一次最多影响几个实例、失败后怎么升级。
 * 对齐蓝图 §三 的「爆炸半径控制」与「原子操作白名单」两条安全防线，
 * 把它们从 Java 常量搬到可运行时调整的配置。
 *
 * ── 为什么是四张卡片而不是表格 ────────────────────────────────
 * 表格适合「同构的多行、需要横向比较某一列」。这里只有四行，
 * 但每行有十个字段、字段间还有依赖（开了自动执行才需要看爆炸半径）。
 * 塞进表格会得到一个横向滚动、每格塞一个输入框的怪物。
 *
 * 卡片按风险从低到高排列，自上而下就是一条「越往下越危险」的渐进线，
 * 高危卡片带红色左边条——用户扫一眼就知道该重点看哪张。
 *
 * ── 编辑模型：显式保存，不做自动保存 ──────────────────────────
 * 这是刻意的。自动保存适合低风险的个人偏好；这里改一个开关可能
 * 让 AI 获得在生产环境自动执行的权限，必须有一个明确的「我确认要这么做」动作。
 * 高危变更还要过二次确认。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  AlertTriangle,
  Check,
  Clock3,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Undo2,
} from 'lucide-vue-next'

import {
  fetchRiskPolicies,
  updateRiskPolicy,
  type ApprovalMode,
  type EscalateTarget,
  type RiskLevel,
  type RiskPolicy,
  type RiskPolicyPage,
} from '@/api/governance'
import DataStateBoundary from '@/components/common/DataStateBoundary.vue'
import RelativeTime from '@/components/common/RelativeTime.vue'
import { useAsyncAction } from '@/composables/useAsyncAction'

defineOptions({ name: 'RiskLevels' })

// ==================== 数据 ====================

const policies = ref<RiskPolicy[]>([])
const approvalModes = ref<RiskPolicyPage['approvalModes']>([])
const escalateTargets = ref<RiskPolicyPage['escalateTargets']>([])
const loading = ref(false)
const loadError = ref<unknown>(null)

/**
 * 草稿区：riskLevel -> 编辑中的副本。
 *
 * 不直接改 `policies` 里的对象，是为了「取消」能真正回退。
 * 直接改原对象则取消时只能重新请求接口——网络不好时用户会看到
 * 输入框先保留旧值几秒再跳回，像是取消没生效。
 */
const drafts = reactive<Record<string, RiskPolicy>>({})
const editing = ref<Set<string>>(new Set())

const ENVIRONMENTS = ['prod', 'staging', 'dev'] as const

/** 高危等级需要额外的视觉与确认强度 */
const isHighRisk = (level: RiskLevel) => level === 'HIGH_RISK_EXECUTION'

const load = async () => {
  loading.value = true
  loadError.value = null
  try {
    const data = await fetchRiskPolicies()
    policies.value = data.items ?? []
    approvalModes.value = data.approvalModes ?? []
    escalateTargets.value = data.escalateTargets ?? []
    // 重新加载时清空草稿：保留草稿会让用户基于旧版本继续编辑，
    // 提交时必然撞版本冲突，等于白填一遍
    editing.value = new Set()
    for (const key of Object.keys(drafts)) delete drafts[key]
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ==================== 编辑 ====================

const startEdit = (policy: RiskPolicy) => {
  // 深拷贝：策略是扁平对象，展开即可
  drafts[policy.riskLevel] = { ...policy }
  editing.value = new Set([...editing.value, policy.riskLevel])
}

const cancelEdit = (level: string) => {
  delete drafts[level]
  const next = new Set(editing.value)
  next.delete(level)
  editing.value = next
}

const isEditing = (level: string) => editing.value.has(level)

/** 环境多选：以逗号分隔串存储，UI 用复选框 */
const hasEnvironment = (draft: RiskPolicy, env: string) =>
  draft.allowedEnvironments.split(',').map((s) => s.trim()).includes(env)

const toggleEnvironment = (draft: RiskPolicy, env: string) => {
  const current = draft.allowedEnvironments
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  const next = current.includes(env)
    ? current.filter((e) => e !== env)
    : [...current, env]
  // 按固定顺序输出，避免「勾选顺序不同 → 字符串不同 → 看起来像改过了」
  draft.allowedEnvironments = ENVIRONMENTS.filter((e) => next.includes(e)).join(',')
}

/**
 * 判断草稿相对原值是否真有改动。
 *
 * 用于禁用「保存」按钮。没有它，用户点开编辑什么都没改也能提交，
 * 而每次提交都会 version+1，把别人正在编辑的表单顶成冲突状态——
 * 一次无意义的空提交会让同事的编辑白费。
 */
const isDirty = (level: string): boolean => {
  const draft = drafts[level]
  const original = policies.value.find((p) => p.riskLevel === level)
  if (!draft || !original) return false
  return (
    draft.approvalMode !== original.approvalMode ||
    draft.approvalTimeoutMinutes !== original.approvalTimeoutMinutes ||
    draft.autoExecuteAllowed !== original.autoExecuteAllowed ||
    draft.maxBlastRadiusPercent !== original.maxBlastRadiusPercent ||
    draft.maxBlastRadiusCount !== original.maxBlastRadiusCount ||
    draft.cooldownSeconds !== original.cooldownSeconds ||
    draft.maxRetries !== original.maxRetries ||
    draft.escalateAfterMinutes !== original.escalateAfterMinutes ||
    draft.escalateTarget !== original.escalateTarget ||
    draft.allowedEnvironments !== original.allowedEnvironments
  )
}

/**
 * 识别「放宽」型改动，用于决定是否二次确认。
 *
 * 只对放宽方向弹确认——收紧是安全的，为收紧也弹确认会让用户
 * 对确认框脱敏，真正危险的那次也一路点过去。
 */
const loosenings = (level: string): string[] => {
  const draft = drafts[level]
  const original = policies.value.find((p) => p.riskLevel === level)
  if (!draft || !original) return []

  const reasons: string[] = []
  const rank: Record<ApprovalMode, number> = { DUAL: 2, SINGLE: 1, NONE: 0 }
  if (rank[draft.approvalMode] < rank[original.approvalMode]) {
    reasons.push(`审批门槛从「${modeLabel(original.approvalMode)}」降为「${modeLabel(draft.approvalMode)}」`)
  }
  if (draft.autoExecuteAllowed && !original.autoExecuteAllowed) {
    reasons.push('开启了自动执行，AI 将可在无人介入下执行该等级动作')
  }
  if (draft.maxBlastRadiusCount > original.maxBlastRadiusCount) {
    reasons.push(`单次影响实例上限从 ${original.maxBlastRadiusCount} 提升到 ${draft.maxBlastRadiusCount}`)
  }
  if (draft.maxBlastRadiusPercent > original.maxBlastRadiusPercent) {
    reasons.push(`爆炸半径从 ${original.maxBlastRadiusPercent}% 提升到 ${draft.maxBlastRadiusPercent}%`)
  }
  const originalEnvs = original.allowedEnvironments.split(',').map((s) => s.trim())
  const added = draft.allowedEnvironments
    .split(',')
    .map((s) => s.trim())
    .filter((e) => e && !originalEnvs.includes(e))
  if (added.length) {
    reasons.push(`新增生效环境：${added.join('、')}`)
  }
  return reasons
}

const modeLabel = (mode: ApprovalMode) =>
  approvalModes.value.find((m) => m.value === mode)?.label ?? mode

const targetLabel = (t: EscalateTarget) =>
  escalateTargets.value.find((x) => x.value === t)?.label ?? t

/**
 * 保存。用 useAsyncAction 统一防重入 + 错误提示。
 *
 * 防重入在这里不是可选项：慢网络下双击保存会发两次 PUT，
 * 第二次必然因 version 已递增而报「已被他人修改」——
 * 用户被自己的第二次点击误导成「有别人在改」。
 */
const saving = useAsyncAction(
  async (level: RiskLevel) => {
    const draft = drafts[level]
    if (!draft) return

    const reasons = loosenings(level)
    if (reasons.length) {
      await ElMessageBox.confirm(
        `本次修改会放宽安全边界：\n\n${reasons.map((r) => `· ${r}`).join('\n')}\n\n` +
          '该配置直接决定 AI 能对生产系统做什么，确认继续？',
        '确认放宽安全边界',
        {
          type: 'warning',
          confirmButtonText: '我已确认，继续保存',
          cancelButtonText: '再想想',
          // 保留换行，否则多条理由会挤成一行读不清
          customClass: 'risk-confirm-dialog',
        }
      )
    }

    const updated = await updateRiskPolicy(level, {
      approvalMode: draft.approvalMode,
      approvalTimeoutMinutes: draft.approvalTimeoutMinutes,
      autoExecuteAllowed: draft.autoExecuteAllowed,
      maxBlastRadiusPercent: draft.maxBlastRadiusPercent,
      maxBlastRadiusCount: draft.maxBlastRadiusCount,
      cooldownSeconds: draft.cooldownSeconds,
      maxRetries: draft.maxRetries,
      escalateAfterMinutes: draft.escalateAfterMinutes,
      escalateTarget: draft.escalateTarget,
      allowedEnvironments: draft.allowedEnvironments,
      version: draft.version,
    })

    // 就地替换而非整表重拉：其他卡片可能正在编辑中，
    // 整表重拉会把它们的草稿一起清掉
    const index = policies.value.findIndex((p) => p.riskLevel === level)
    if (index >= 0) policies.value[index] = updated
    cancelEdit(level)
    return updated
  },
  { action: '保存策略', successMessage: '策略已保存' }
)

/** 顶部概览：有多少级已经开放了自动执行 */
const autonomousCount = computed(
  () => policies.value.filter((p) => p.autoExecuteAllowed).length
)
const noApprovalCount = computed(
  () => policies.value.filter((p) => p.approvalMode === 'NONE').length
)
const prodEnabledCount = computed(
  () => policies.value.filter((p) => p.allowedEnvironments.includes('prod')).length
)
</script>

<template>
  <div class="risk-page">
    <main class="risk-main">
      <header class="page-header">
        <div>
          <h1 class="page-title">风险等级配置</h1>
          <p class="page-sub">
            定义每个风险等级的审批门槛、执行限制与升级路径。配置立即对自动化引擎生效。
          </p>
        </div>
        <div class="header-actions">
          <button class="icon-btn" type="button" title="刷新" :disabled="loading" @click="load">
            <RefreshCw :size="14" :class="{ spinning: loading }" />
          </button>
        </div>
      </header>

      <!-- 概览：三个「该警惕」的数字，而不是无意义的总数 -->
      <div class="stat-strip" role="group" aria-label="配置概览">
        <div class="stat-badge">
          <span class="stat-label">允许自动执行</span>
          <span class="stat-value">{{ autonomousCount }} / {{ policies.length }}</span>
        </div>
        <div class="stat-badge" :class="{ 'is-warn': noApprovalCount > 2 }">
          <span class="stat-label">免审批等级</span>
          <span class="stat-value">{{ noApprovalCount }}</span>
        </div>
        <div class="stat-badge" :class="{ 'is-warn': prodEnabledCount > 0 }">
          <span class="stat-label">已开放生产环境</span>
          <span class="stat-value">{{ prodEnabledCount }}</span>
        </div>
      </div>

      <DataStateBoundary
        :loading="loading"
        :error="loadError"
        :count="policies.length"
        empty-title="暂无风险策略"
        empty-description="策略表尚未初始化，请确认已执行 migration_v26"
        :skeleton-rows="4"
        skeleton-height="180px"
        @retry="load"
      >
        <div class="policy-grid">
          <article
            v-for="policy in policies"
            :key="policy.riskLevel"
            class="policy-card"
            :class="{
              'is-high-risk': isHighRisk(policy.riskLevel),
              'is-editing': isEditing(policy.riskLevel),
            }"
          >
            <!-- ===== 卡片头 ===== -->
            <header class="card-head">
              <div class="card-title-wrap">
                <component
                  :is="isHighRisk(policy.riskLevel) ? ShieldAlert : ShieldCheck"
                  :size="16"
                  class="card-icon"
                />
                <div>
                  <h2 class="card-title">{{ policy.displayName }}</h2>
                  <code class="card-key">{{ policy.riskLevel }}</code>
                </div>
              </div>

              <div class="card-head-actions">
                <template v-if="!isEditing(policy.riskLevel)">
                  <button class="btn-ghost" type="button" @click="startEdit(policy)">
                    编辑
                  </button>
                </template>
                <template v-else>
                  <button
                    class="btn-ghost"
                    type="button"
                    :disabled="saving.pending.value"
                    @click="cancelEdit(policy.riskLevel)"
                  >
                    <Undo2 :size="12" /> 取消
                  </button>
                  <button
                    class="btn-primary"
                    type="button"
                    :disabled="saving.pending.value || !isDirty(policy.riskLevel)"
                    :title="!isDirty(policy.riskLevel) ? '没有改动' : '保存'"
                    @click="saving.run(policy.riskLevel)"
                  >
                    <Check :size="12" /> 保存
                  </button>
                </template>
              </div>
            </header>

            <p class="card-desc">{{ policy.description || '—' }}</p>

            <!-- ===== 只读视图 ===== -->
            <dl v-if="!isEditing(policy.riskLevel)" class="kv-grid">
              <div class="kv">
                <dt>审批门槛</dt>
                <dd>
                  <span class="pill" :class="policy.approvalMode === 'NONE' ? 'is-open' : 'is-guarded'">
                    {{ modeLabel(policy.approvalMode) }}
                  </span>
                  <span v-if="policy.approvalMode !== 'NONE'" class="kv-sub">
                    {{ policy.approvalTimeoutMinutes }} 分钟内未审批则过期
                  </span>
                </dd>
              </div>
              <div class="kv">
                <dt>自动执行</dt>
                <dd>
                  <span class="pill" :class="policy.autoExecuteAllowed ? 'is-open' : 'is-guarded'">
                    {{ policy.autoExecuteAllowed ? '允许' : '禁止' }}
                  </span>
                </dd>
              </div>
              <div class="kv">
                <dt>爆炸半径</dt>
                <dd>
                  <span class="num">{{ policy.maxBlastRadiusPercent }}%</span>
                  <span class="kv-sep">或</span>
                  <span class="num">{{ policy.maxBlastRadiusCount }}</span>
                  <span class="kv-sub">个实例（取较小）</span>
                </dd>
              </div>
              <div class="kv">
                <dt>观察窗口</dt>
                <dd><span class="num">{{ policy.cooldownSeconds }}</span> 秒 · 重试 {{ policy.maxRetries }} 次</dd>
              </div>
              <div class="kv">
                <dt>升级路径</dt>
                <dd>
                  {{ targetLabel(policy.escalateTarget) }}
                  <span v-if="policy.escalateTarget !== 'NONE'" class="kv-sub">
                    · 失败 {{ policy.escalateAfterMinutes }} 分钟后
                  </span>
                </dd>
              </div>
              <div class="kv">
                <dt>生效环境</dt>
                <dd>
                  <template v-if="policy.allowedEnvironments">
                    <span
                      v-for="env in policy.allowedEnvironments.split(',')"
                      :key="env"
                      class="env-chip"
                      :class="{ 'is-prod': env === 'prod' }"
                    >{{ env }}</span>
                  </template>
                  <span v-else class="kv-sub">未开放任何环境</span>
                </dd>
              </div>
            </dl>

            <!-- ===== 编辑视图 ===== -->
            <div v-else class="edit-form">
              <fieldset class="form-group">
                <legend>审批门槛</legend>
                <div class="field-row">
                  <label class="field">
                    <span class="field-label">审批模式</span>
                    <select v-model="drafts[policy.riskLevel].approvalMode" class="control">
                      <option
                        v-for="mode in approvalModes"
                        :key="mode.value"
                        :value="mode.value"
                        :disabled="isHighRisk(policy.riskLevel) && mode.value === 'NONE'"
                      >
                        {{ mode.label }}
                        <template v-if="isHighRisk(policy.riskLevel) && mode.value === 'NONE'">（该等级不可选）</template>
                      </option>
                    </select>
                  </label>
                  <label class="field">
                    <span class="field-label">审批时限（分钟）</span>
                    <input
                      v-model.number="drafts[policy.riskLevel].approvalTimeoutMinutes"
                      class="control"
                      type="number"
                      min="1"
                      max="1440"
                      :disabled="drafts[policy.riskLevel].approvalMode === 'NONE'"
                    />
                  </label>
                </div>
                <p v-if="isHighRisk(policy.riskLevel)" class="field-hint">
                  <AlertTriangle :size="12" />
                  高风险执行涵盖不可逆操作，不允许配置为免审批
                </p>
              </fieldset>

              <fieldset class="form-group">
                <legend>执行限制</legend>
                <label class="switch-row">
                  <input v-model="drafts[policy.riskLevel].autoExecuteAllowed" type="checkbox" />
                  <span>允许引擎自动执行</span>
                  <span class="field-hint inline">关闭后即便审批通过也只能人工手动触发</span>
                </label>
                <div class="field-row">
                  <label class="field">
                    <span class="field-label">爆炸半径（%）</span>
                    <input
                      v-model.number="drafts[policy.riskLevel].maxBlastRadiusPercent"
                      class="control"
                      type="number"
                      min="1"
                      max="100"
                    />
                  </label>
                  <label class="field">
                    <span class="field-label">实例数上限</span>
                    <input
                      v-model.number="drafts[policy.riskLevel].maxBlastRadiusCount"
                      class="control"
                      type="number"
                      min="1"
                      max="9999"
                    />
                  </label>
                </div>
                <p class="field-hint">
                  两者取较小值。只配百分比会让大集群一次影响过多实例，只配绝对值又会让小集群过于保守。
                </p>
                <div class="field-row">
                  <label class="field">
                    <span class="field-label">观察窗口（秒）</span>
                    <input
                      v-model.number="drafts[policy.riskLevel].cooldownSeconds"
                      class="control"
                      type="number"
                      min="0"
                      max="3600"
                    />
                  </label>
                  <label class="field">
                    <span class="field-label">最大重试次数</span>
                    <input
                      v-model.number="drafts[policy.riskLevel].maxRetries"
                      class="control"
                      type="number"
                      min="0"
                      max="5"
                    />
                  </label>
                </div>
              </fieldset>

              <fieldset class="form-group">
                <legend>升级路径</legend>
                <div class="field-row">
                  <label class="field">
                    <span class="field-label">升级目标</span>
                    <select v-model="drafts[policy.riskLevel].escalateTarget" class="control">
                      <option v-for="t in escalateTargets" :key="t.value" :value="t.value">
                        {{ t.label }}
                      </option>
                    </select>
                  </label>
                  <label class="field">
                    <span class="field-label">升级等待（分钟）</span>
                    <input
                      v-model.number="drafts[policy.riskLevel].escalateAfterMinutes"
                      class="control"
                      type="number"
                      min="0"
                      max="1440"
                      :disabled="drafts[policy.riskLevel].escalateTarget === 'NONE'"
                    />
                  </label>
                </div>
              </fieldset>

              <fieldset class="form-group">
                <legend>生效环境</legend>
                <div class="env-checks">
                  <label
                    v-for="env in ENVIRONMENTS"
                    :key="env"
                    class="env-check"
                    :class="{ 'is-prod': env === 'prod' }"
                  >
                    <input
                      type="checkbox"
                      :checked="hasEnvironment(drafts[policy.riskLevel], env)"
                      @change="toggleEnvironment(drafts[policy.riskLevel], env)"
                    />
                    <span>{{ env }}</span>
                  </label>
                </div>
                <p class="field-hint">
                  动作白名单的生效环境不能超出这里的范围——收紧本项会同步收紧该等级下的所有动作。
                </p>
              </fieldset>
            </div>

            <footer v-if="policy.updateTime" class="card-foot">
              <Clock3 :size="11" />
              最后修改
              <RelativeTime :value="policy.updateTime" />
              <template v-if="policy.updatedBy">· {{ policy.updatedBy }}</template>
            </footer>
          </article>
        </div>
      </DataStateBoundary>
    </main>
  </div>
</template>

<style scoped lang="scss">
.risk-page {
  min-height: 100vh;
  background: var(--color-bg);
}

.risk-main {
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
  font-size: 13px;
  color: var(--color-text-tertiary);
}

.header-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

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
  transition: background 0.15s, color 0.15s;

  &:hover:not(:disabled) {
    background: var(--color-fill-light);
    color: var(--color-text-primary);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
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
  margin-bottom: 16px;
}

.stat-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 28px;
  padding: 0 10px 0 8px;
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  background: var(--color-fill-lighter);
  position: relative;
  overflow: hidden;

  /* 左侧 2px 竖色条 */
  &::before {
    content: '';
    position: absolute;
    left: 0;
    top: 0;
    bottom: 0;
    width: 2px;
    background: var(--color-text-tertiary);
  }

  &.is-warn::before {
    background: var(--color-warning);
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

/* ===== 卡片 ===== */
.policy-grid {
  display: grid;
  gap: 12px;
}

.policy-card {
  position: relative;
  padding: 16px 18px;
  border: 1px solid var(--color-border-light);
  border-radius: 12px;
  background: var(--color-surface);
  transition: border-color 0.15s, box-shadow 0.15s;

  /* 左侧风险色条：高危用 danger，其余用中性色 */
  &::before {
    content: '';
    position: absolute;
    left: 0;
    top: 12px;
    bottom: 12px;
    width: 3px;
    border-radius: 0 3px 3px 0;
    background: var(--color-border);
  }

  &.is-high-risk::before {
    background: var(--color-danger);
  }

  &.is-editing {
    border-color: var(--color-primary);
    box-shadow: 0 0 0 3px rgb(from var(--color-primary) r g b / 0.08);
  }
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.card-title-wrap {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.card-icon {
  margin-top: 2px;
  color: var(--color-text-tertiary);
  flex-shrink: 0;

  .is-high-risk & {
    color: var(--color-danger);
  }
}

.card-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.card-key {
  font-size: 11px;
  font-family: var(--font-mono, ui-monospace, monospace);
  color: var(--color-text-quaternary, var(--color-text-tertiary));
}

.card-head-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.btn-ghost,
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
  border-radius: 6px;
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

.card-desc {
  margin: 6px 0 12px 24px;
  font-size: 12px;
  color: var(--color-text-tertiary);
}

/* ===== 只读视图 ===== */
.kv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
  gap: 10px 20px;
  margin: 0 0 0 24px;
}

.kv {
  display: flex;
  flex-direction: column;
  gap: 3px;

  dt {
    font-size: 11px;
    color: var(--color-text-quaternary, var(--color-text-tertiary));
    text-transform: uppercase;
    letter-spacing: 0.03em;
  }

  dd {
    margin: 0;
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 5px;
    font-size: 13px;
    color: var(--color-text-primary);
  }
}

.kv-sub {
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.kv-sep {
  font-size: 11px;
  color: var(--color-text-quaternary, var(--color-text-tertiary));
}

.num {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
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

.env-chip {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  font-size: 11px;
  font-family: var(--font-mono, ui-monospace, monospace);
  border-radius: 4px;
  border: 1px solid var(--color-border-light);
  background: var(--color-fill-lighter);
  color: var(--color-text-secondary);

  /* prod 单独标色：它是唯一「改错会出真事故」的环境 */
  &.is-prod {
    color: var(--color-danger);
    border-color: rgb(from var(--color-danger) r g b / 0.3);
    background: rgb(from var(--color-danger) r g b / 0.07);
  }
}

/* ===== 编辑视图 ===== */
.edit-form {
  display: grid;
  gap: 14px;
  margin-left: 24px;
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

.field-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 10px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-label {
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.control {
  height: 30px;
  padding: 0 8px;
  font-size: 13px;
  font-family: inherit;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface);
  color: var(--color-text-primary);

  &:focus {
    outline: none;
    border-color: var(--color-primary);
    box-shadow: 0 0 0 3px rgb(from var(--color-primary) r g b / 0.1);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--color-text-primary);
  cursor: pointer;

  input {
    cursor: pointer;
  }
}

.field-hint {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text-tertiary);

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

/* ===== 卡片脚 ===== */
.card-foot {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 12px 0 0 24px;
  padding-top: 10px;
  border-top: 1px solid var(--color-border-lighter, var(--color-border-light));
  font-size: 11px;
  color: var(--color-text-quaternary, var(--color-text-tertiary));
}
</style>
