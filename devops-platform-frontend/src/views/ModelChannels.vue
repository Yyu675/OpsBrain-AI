<script setup lang="ts">
/**
 * AI 模型渠道配置（P0 只读 → P1 编辑 → P3 热更新即时生效，无需重启）。
 *
 * ── 职责 ─────────────────────────────────────────────────────
 * 展示 sys_ai_channel 表中当前生效的渠道配置；P1 起支持编辑
 * （PUT /{channelKey}，null 字段不修改）。
 *
 * ── P3-1 热更新 ───────────────────────────────────────────────
 * 保存成功后 ChannelRefreshService 把 LangChain4j 模型 Bean 用
 * Refreshable 包装器原子替换——新请求立即走新配置，无需重启。
 * 仅当热更新失败时回落 restartRequired=true（提示需重启），
 * 保存动作本身始终成功（配置已落 DB）。
 */

import { ref, onMounted, computed } from 'vue'
import { RefreshCw, Cpu, Layers, Search, History, FlaskConical, ShieldAlert } from 'lucide-vue-next'

import { fetchModelChannels, updateModelChannel, resetModelChannel, testModelChannelConnectivity, fetchAvailableModels, fetchChannelHistory, rollbackModelChannel, probeChannelCapabilities, fetchChannelCapabilities, fetchChannelTemplates, applyChannelTemplate, revealModelChannelKey } from '@/api/modelChannels'
import type { AiChannelHistoryView, AiChannelView, AiChannelUpdatePayload, ChannelProbeResult, ChannelTemplate } from '@/api/types'
import { diffRows as diffOf } from '@/composables/useChannelDiff'
import { VENDOR_TEMPLATES } from '@/constants/modelVendors'
import {
  capabilityTagType, capabilityTagText, capabilitySummaryOf, staleReasonOf,
} from '@/composables/useChannelCapability'
import { copyText } from '@/utils/clipboard'
import DataStateBoundary from '@/components/common/DataStateBoundary.vue'
import SecretInput from '@/components/common/SecretInput.vue'
import ChannelKeyRow from '@/components/ai/ChannelKeyRow.vue'
import { notify, handleServerError } from '@/utils/notify'

defineOptions({ name: 'ModelChannels' })

type ChannelKey = 'chat' | 'embedding' | 'reranker'

// ==================== 数据状态 ====================

const channels = ref<AiChannelView[]>([])
const loading = ref(false)
const loadError = ref<unknown>(null)
/** 当前 AI 运行模式（MOCK/REAL），用于顶部人性化模式横幅 */
const aiMode = ref<string>('')
/** MODEL_KEY_CRYPT_SECRET 是否已配。false = 所有 API Key 明文落库（顶部警告横幅）。
 *  缺省 true：旧快照响应可能不含此字段，缺省不误报警。 */
const keyCryptConfigured = ref(true)

const loadChannels = async () => {
  loading.value = true
  loadError.value = null
  try {
    const resp = await fetchModelChannels()
    channels.value = resp.channels ?? []
    aiMode.value = resp.aiMode ?? ''
    keyCryptConfigured.value = resp.keyCryptConfigured ?? true
    // 能力探测结果一并拉取（GET 不触发实测，零成本）——探测过的渠道卡片直接显示三态摘要
    await loadAllCapabilities()
  } catch (e) {
    console.error('[模型渠道配置] 加载失败', e)
    loadError.value = e
  } finally {
    loading.value = false
  }
}

onMounted(loadChannels)

// ==================== 计算属性 ====================

const chatChannel = computed(() =>
  channels.value.find(c => c.channelKey === 'chat') ?? null
)
const embeddingChannel = computed(() =>
  channels.value.find(c => c.channelKey === 'embedding') ?? null
)
const rerankerChannel = computed(() =>
  channels.value.find(c => c.channelKey === 'reranker') ?? null
)

// ==================== 编辑对话框 ====================

interface EditForm {
  baseUrl: string
  /** API 协议（显式选择，不从 URL 推断） */
  protocol: string
  /** 供应商名称（可编辑；留空则后端从 baseUrl 推断兜底） */
  provider: string
  turboModel: string
  reasonerModel: string
  model: string
  dimension: number | undefined
  apiKey: string
  status: 'ACTIVE' | 'DISABLED'
  // 备用模型（方案 A）：仅 chat 渠道使用
  fallbackEnabled: boolean
  fallbackBaseUrl: string
  fallbackModel: string
  fallbackApiKey: string
}

const editOpen = ref(false)
const editTarget = ref<AiChannelView | null>(null)
const saving = ref(false)
const editForm = ref<EditForm>({ baseUrl: '', protocol: 'OPENAI_COMPATIBLE', provider: '', turboModel: '', reasonerModel: '', model: '', dimension: undefined, apiKey: '', status: 'ACTIVE', fallbackEnabled: false, fallbackBaseUrl: '', fallbackModel: '', fallbackApiKey: '' })
const editRestartHint = ref(false)

const openEdit = (ch: AiChannelView) => {
  editTarget.value = ch
  editRestartHint.value = false
  editForm.value = {
    baseUrl: ch.baseUrl ?? '',
    protocol: ch.protocol ?? 'OPENAI_COMPATIBLE',
    provider: ch.provider ?? '',
    turboModel: ch.turboModel ?? '',
    reasonerModel: ch.reasonerModel ?? '',
    model: ch.model ?? '',
    dimension: ch.dimension ?? undefined,
    apiKey: '',
    status: (ch.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE') as 'ACTIVE' | 'DISABLED',
    fallbackEnabled: Boolean(ch.fallbackModel),
    fallbackBaseUrl: ch.fallbackBaseUrl ?? '',
    fallbackModel: ch.fallbackModel ?? '',
    fallbackApiKey: '',
  }
  editOpen.value = true
}

// ==================== 渠道模板库（多渠道一键切换） ====================

const templates = ref<ChannelTemplate[]>([])
const templateDialogOpen = ref(false)
const templateDialogChannel = ref<AiChannelView | null>(null)
const applyingTemplate = ref(false)

/** 打开「切换供应商」对话框：列出该渠道适用的模板 */
const openTemplateDialog = async (ch: AiChannelView) => {
  templateDialogChannel.value = ch
  try {
    templates.value = await fetchChannelTemplates(ch.channelKey)
  } catch (e) {
    handleServerError(e, { action: '加载渠道模板' })
    return
  }
  templateDialogOpen.value = true
}

/** 一键应用模板：保留现有 apiKey，只换 baseUrl/协议/供应商/模型名 */
const applyTemplate = async (tpl: ChannelTemplate) => {
  const ch = templateDialogChannel.value
  if (!ch || applyingTemplate.value) return
  applyingTemplate.value = true
  try {
    const saved = await applyChannelTemplate(ch.channelKey, tpl.id)
    const idx = channels.value.findIndex(c => c.channelKey === saved.channelKey)
    if (idx >= 0) channels.value[idx] = saved
    templateDialogOpen.value = false
    notify.success(`已切换到「${tpl.templateName}」` + (saved.restartRequired ? '——热更新失败，重启后端后生效' : '，已即时生效（apiKey 保留现有）'))
  } catch (e) {
    handleServerError(e, { action: '应用渠道模板' })
  } finally {
    applyingTemplate.value = false
  }
}

/** 协议是否真实接入调用层。方案 A 已接入 Anthropic/Azure；仅 CUSTOM（未识别）未接入，按 OpenAI 兼容处理。 */
const isUnsupportedProtocol = (protocol?: string | null) =>
  Boolean(protocol && protocol !== 'OPENAI_COMPATIBLE' && protocol !== 'ANTHROPIC' && protocol !== 'AZURE_OPENAI')

/** 从表单构建 patch：空串/undefined 不打包 → 后端按「不修改」处理。 */
function buildPatch(): AiChannelUpdatePayload {
  const f = editForm.value
  const p: AiChannelUpdatePayload = {}
  const set = <K extends keyof AiChannelUpdatePayload>(k: K, v: AiChannelUpdatePayload[K] | '' | undefined) => {
    if (v !== '' && v !== undefined) p[k] = v
  }
  set('baseUrl', f.baseUrl)
  // 协议显式配置（显式选择，不从 URL 推断）；供应商留空 = 后端推断兜底
  set('protocol', f.protocol)
  set('provider', f.provider)
  if (editTarget.value?.channelKey === 'embedding' || editTarget.value?.channelKey === 'reranker') {
    set('model', f.model)
    if (editTarget.value?.channelKey === 'embedding') set('dimension', f.dimension)
  } else {
    set('turboModel', f.turboModel)
    set('reasonerModel', f.reasonerModel)
  }
  set('apiKey', f.apiKey)
  set('status', f.status !== (editTarget.value?.status ?? 'ACTIVE') ? f.status : undefined)
  // 备用模型（仅 chat）：开关关闭且既有配置存在 → 清除；开启 → 提交 url+model（key 可选）
  if (editTarget.value?.channelKey === 'chat') {
    if (!f.fallbackEnabled && editTarget.value?.fallbackModel) {
      p.clearFallback = true
    } else if (f.fallbackEnabled) {
      set('fallbackBaseUrl', f.fallbackBaseUrl)
      set('fallbackModel', f.fallbackModel)
      set('fallbackApiKey', f.fallbackApiKey)
    }
  }
  return p
}

const submitEdit = async () => {
  if (!editTarget.value || saving.value) return
  saving.value = true
  try {
    const saved = await updateModelChannel(editTarget.value.channelKey, buildPatch())
    // 更新列表中的该行（避免整体 reload）
    const idx = channels.value.findIndex(c => c.channelKey === saved.channelKey)
    if (idx >= 0) channels.value[idx] = saved
    editRestartHint.value = saved.restartRequired === true
    notify.success('渠道配置已保存' + (saved.restartRequired ? '——热更新失败，重启后端后生效' : '，已即时生效'))
    if (saved.restartRequired) {
      editRestartHint.value = true
    }
    // 改后验证（建议1）：新配置已热更新，立即自动探测——发现问题给强警告
    // 并可一键回滚到改前状态（V5 历史链在保存时已快照）。
    // 不做「保存前探测」：探测端点测的是已落库配置，保存前测的是旧配置，无意义。
    void probeAfterSave(saved)
  } catch (e) {
    handleServerError(e, { action: '保存渠道配置' })
  } finally {
    saving.value = false
  }
}

/**
 * 保存后自动探测（改后验证）：静默跑，全部 SUPPORTED 不打扰用户；
 * 发现 UNSUPPORTED / 全部 UNKNOWN 时弹强警告，附「一键回滚到改前」。
 * 探测失败本身（网络/超时）不弹——那属于 UNKNOWN 语义，不是配置错误。
 */
const probeAfterSave = async (saved: AiChannelView) => {
  try {
    const result = await probeChannelCapabilities(saved.channelKey)
    capabilityMap.value[result.channelKey] = result
    const items = Object.entries(result.capabilities)
    const bad = items.filter(([, i]) => i.state === 'UNSUPPORTED')
    const allUnknown = items.length > 0 && items.every(([, i]) => i.state === 'UNKNOWN')
    if (bad.length === 0 && !allUnknown) return

    const lines = allUnknown
      ? '所有能力项均未测成（探测本身失败：超时/限流/网络）。配置可能没问题，但无法确认——建议稍后手动重新探测。'
      : bad.map(([cap, i]) => `• ${cap}：${i.detail}`).join('\n')
    const m = await import('element-plus')
    m.ElMessageBox.confirm(
      `新配置已保存并热更新，但能力探测发现问题：\n\n${lines}\n\n可以回滚到改动前的状态（保存时已自动快照）。`,
      allUnknown ? '探测未能确认配置' : '配置探测发现问题',
      {
        confirmButtonText: allUnknown ? '知道了' : '一键回滚到改前',
        cancelButtonText: '保留新配置',
        type: 'warning',
        distinguishCancelAndClose: true,
      },
    ).then(async () => {
      if (allUnknown) return  // 全部 UNKNOWN 时确认键只是「知道了」，不回滚
      await rollbackToLatestSnapshot(saved.channelKey)
    }).catch(() => { /* 用户选保留——尊重决定，留 WARNING 痕迹在能力面板 */ })
  } catch {
    // 探测请求失败不打扰：保存已成功，探测是旁路验证
  }
}

/** 回滚到最近一次快照（= 本次保存前的状态）。V5 契约：保存时后端已快照改前整行。 */
const rollbackToLatestSnapshot = async (channelKey: ChannelKey) => {
  try {
    const resp = await fetchChannelHistory(channelKey, 1)
    const latest = resp.history?.[0]
    if (!latest) { notify.warning('未找到可回滚的快照'); return }
    const restored = await rollbackModelChannel(channelKey, latest.id)
    const idx = channels.value.findIndex((c) => c.channelKey === restored.channelKey)
    if (idx >= 0) channels.value[idx] = restored
    notify.success(`已回滚到 #${latest.id}` + (restored.restartRequired ? '——热更新失败，重启后端后生效' : '，已即时生效'))
  } catch (e) {
    handleServerError(e, { action: '回滚到改前状态' })
  }
}

const resetChannel = async (ch: AiChannelView) => {
  if (
    !(await confirmDialog(
      `确认重置 ${ch.channelKey} 为 yml 默认值？此操作将覆盖全部自定义字段。`
    ))
  )
    return
  saving.value = true
  try {
    const saved = await resetModelChannel(ch.channelKey)
    const idx = channels.value.findIndex((c) => c.channelKey === saved.channelKey)
    if (idx >= 0) channels.value[idx] = saved
    notify.success(`${ch.channelKey} 已重置为默认值` + (saved.restartRequired ? '——热更新失败，重启后端后生效' : '，已即时生效'))
  } catch (e) {
    handleServerError(e, { action: '重置渠道配置' })
  } finally {
    saving.value = false
  }
}

/** 简单确认弹窗：返回 true 当用户点了确定。 */
async function confirmDialog(msg: string): Promise<boolean> {
  try {
    await import('element-plus').then((m) =>
      m.ElMessageBox.confirm(msg, '重置确认', {
        confirmButtonText: '确定重置',
        cancelButtonText: '取消',
        type: 'warning',
      })
    )
    return true
  } catch {
    return false
  }
}

// ==================== V5：变更历史 + 回滚 ====================

const historyOpen = ref(false)
const historyTarget = ref<AiChannelView | null>(null)
const historyList = ref<AiChannelHistoryView[]>([])
const historyLoading = ref(false)
const rollingBackId = ref<number | null>(null)

// ---- 建议2：当前 vs 目标版本 diff（字段定义与算法见 composables/useChannelDiff.ts）----
/** 展开 diff 的历史行 id（null = 收起）。点击历史行切换，再点收起。 */
const diffOpenId = ref<number | null>(null)
const diffRows = (h: AiChannelHistoryView) => diffOf(historyTarget.value, h)

const toggleDiff = (h: AiChannelHistoryView) => {
  diffOpenId.value = diffOpenId.value === h.id ? null : h.id
}

const openHistory = async (ch: AiChannelView) => {
  historyTarget.value = ch
  historyList.value = []
  historyOpen.value = true
  await loadHistory(ch.channelKey)
}

const loadHistory = async (channelKey: ChannelKey) => {
  historyLoading.value = true
  try {
    const resp = await fetchChannelHistory(channelKey)
    historyList.value = resp.history ?? []
  } catch (e) {
    handleServerError(e, { action: '查询变更历史' })
  } finally {
    historyLoading.value = false
  }
}

/**
 * 回滚到历史版本。后端会先快照当前态再写回（滚错了能再滚回来），
 * 返回带 restartRequired 的视图，同编辑热更新契约。
 */
const rollbackTo = async (h: AiChannelHistoryView) => {
  if (!historyTarget.value || rollingBackId.value !== null) return
  try {
    await import('element-plus').then((m) =>
      m.ElMessageBox.confirm(
        `确认回滚到 #${h.id}（${fmtTime(h.changedAt)} 的快照）？当前配置会被覆盖，但会先自动快照——回滚错了还能再滚回来。`,
        '回滚确认',
        { confirmButtonText: '确定回滚', cancelButtonText: '取消', type: 'warning' }
      )
    )
  } catch {
    return
  }
  rollingBackId.value = h.id
  try {
    const saved = await rollbackModelChannel(historyTarget.value.channelKey, h.id)
    const idx = channels.value.findIndex((c) => c.channelKey === saved.channelKey)
    if (idx >= 0) channels.value[idx] = saved
    notify.success(`已回滚到 #${h.id}` + (saved.restartRequired ? '——热更新失败，重启后端后生效' : '，已即时生效'))
    // 回滚本身产生了新快照，刷新历史列表保持时序正确
    await loadHistory(saved.channelKey)
  } catch (e) {
    handleServerError(e, { action: '回滚渠道配置' })
  } finally {
    rollingBackId.value = null
  }
}

// ==================== V5：能力探测（三态） ====================

/** 每个渠道最近一次的落库探测结果（null = 从未探测） */
const capabilityMap = ref<Record<string, ChannelProbeResult | null>>({})
const capabilityOpen = ref(false)
const capabilityTarget = ref<AiChannelView | null>(null)
const probing = ref(false)

const loadAllCapabilities = async () => {
  const keys: ChannelKey[] = ['chat', 'embedding', 'reranker']
  await Promise.all(keys.map(async (k) => {
    try {
      capabilityMap.value[k] = await fetchChannelCapabilities(k)
    } catch {
      capabilityMap.value[k] = null  // 读不出当未探测，不阻断页面
    }
  }))
}

const openCapability = (ch: AiChannelView) => {
  capabilityTarget.value = ch
  capabilityOpen.value = true
}

/** 手动触发实测（真实 API 调用，计费+秒级延迟） */
const runProbe = async () => {
  if (!capabilityTarget.value || probing.value) return
  probing.value = true
  try {
    const result = await probeChannelCapabilities(capabilityTarget.value.channelKey)
    capabilityMap.value[result.channelKey] = result
    notify.success('能力探测完成')
  } catch (e) {
    handleServerError(e, { action: '能力探测' })
  } finally {
    probing.value = false
  }
}

/** 三态呈现映射与过期判定在 composables/useChannelCapability.ts（批89 抽出偿行红线） */
const capabilitySummary = (channelKey: string) =>
  capabilitySummaryOf(capabilityMap.value[channelKey])
const staleReason = (channelKey: string) =>
  staleReasonOf(capabilityMap.value[channelKey], channels.value.find((c) => c.channelKey === channelKey)?.updatedAt)

const isChatChannel = computed(() => editTarget.value?.channelKey === 'chat')
const isEmbeddingChannel = computed(() => editTarget.value?.channelKey === 'embedding')

// ==================== 明文查看（批89 与通知渠道 reveal 对称） ====================

/** 展开明文的渠道 → 明文（undefined=收起；null=解不开；''=没配）。收起即删 key，明文不过夜。 */
const revealedKeys = ref<Record<string, string | null | undefined>>({})
const revealing = ref<ChannelKey | null>(null)

const toggleReveal = async (ch: AiChannelView) => {
  if (revealedKeys.value[ch.channelKey] !== undefined) {
    delete revealedKeys.value[ch.channelKey]
    return
  }
  if (revealing.value) return
  revealing.value = ch.channelKey
  try {
    const r = await revealModelChannelKey(ch.channelKey)
    revealedKeys.value = { ...revealedKeys.value, [ch.channelKey]: r.apiKey ?? null }
  } catch (e) {
    handleServerError(e, { action: '查看明文' })
  } finally {
    revealing.value = null
  }
}

const copyPlainKey = async (ch: AiChannelView) => {
  const plain = revealedKeys.value[ch.channelKey]
  if (!plain) return
  if (await copyText(plain)) notify.success('明文 Key 已复制到剪贴板')
  else notify.warning('复制失败，请手动选中复制')
}

// ==================== P2-3 连通性测试 ====================

const testing = ref(false)
const testResult = ref<{ success: boolean; message: string } | null>(null)

const testConnectivity = async () => {
  if (!editTarget.value || testing.value) return
  testing.value = true
  testResult.value = null
  try {
    const key = editTarget.value.channelKey as 'chat' | 'embedding'
    const r = await testModelChannelConnectivity(key)
    testResult.value = { success: r.success, message: r.message }
    notify[r.success ? 'success' : 'warning'](r.message)
  } catch (e) {
    testResult.value = { success: false, message: '连通性测试请求失败' }
    handleServerError(e, { action: '连通性测试' })
  } finally {
    testing.value = false
  }
}

// ==================== P2-4 模型列表下拉 ====================

const availableModels = ref<string[]>([])
const loadingModels = ref(false)

const loadModels = async () => {
  if (!editTarget.value || loadingModels.value) return
  loadingModels.value = true
  try {
    availableModels.value = await fetchAvailableModels(editTarget.value.channelKey)
    if (availableModels.value.length === 0) notify.info('上游未返回模型列表，可手动输入')
  } catch {
    availableModels.value = []
  } finally {
    loadingModels.value = false
  }
}

// ==================== P3-2 供应商模板（数据见 constants/modelVendors.ts） ====================

const applyVendorTemplate = (key: string) => {
  const t = VENDOR_TEMPLATES[key]
  if (!t || !t.baseUrl) return
  editForm.value.baseUrl = t.baseUrl
  notify.info(`已填入 ${t.label} 端点`)
}

// ==================== 辅助 ====================

/** null / 空串 → '—' 展示 */
/**
 * 协议码 → 展示名（后端 AiChannelService 四值白名单；未知码原样透出）。
 * OPENAI_COMPATIBLE 全大写下划线对人不友好，但它是码值——只换展示不动数据。
 */
const PROTOCOL_LABELS: Record<string, string> = {
  OPENAI_COMPATIBLE: 'OpenAI 兼容',
  AZURE_OPENAI: 'Azure OpenAI',
  ANTHROPIC: 'Anthropic',
  CUSTOM: '自定义'
}
const protocolLabel = (p: string | null | undefined) =>
  (p && PROTOCOL_LABELS[p]) || p || '—'

const dash = (v: string | null | undefined) => v || '—'

/** status → Element Plus type */
const statusType = (s: string | null) =>
  s === 'ACTIVE' ? 'success' : s === 'DISABLED' ? 'info' : 'warning'

/** 时间格式化 */
const fmtTime = (t: string | null) => t ? t.replace('T', ' ').substring(0, 16) : '—'
</script>

<template>
  <div class="page-container">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="page-header__title-row">
        <h1 class="page-title">模型渠道配置</h1>
        <span class="badge-p1">P3 热更新</span>
      </div>
      <p class="page-desc">
        当前生效的 AI 模型渠道配置（DB 权威源）。编辑需管理员权限；
        保存后<b>即时生效</b>（Refreshable 包装器原子替换模型实例），仅热更新失败时才需重启。
        明文 Key 永不流出后端。变更历史可回滚到任意版本；能力探测为手动触发的真实 API 实测。
      </p>
    </div>

    <!-- AI 模式横幅（人性化提示：当前是演示假数据还是真实调用） -->
    <div v-if="aiMode" class="ai-mode-banner" :class="aiMode === 'REAL' ? 'ai-mode-banner--real' : 'ai-mode-banner--mock'">
      <span class="ai-mode-banner__dot"></span>
      <span v-if="aiMode === 'REAL'" class="ai-mode-banner__text">
        <b>真实模式（REAL）</b>——正在调用真实大模型，每次调用消耗 API 额度
      </span>
      <span v-else class="ai-mode-banner__text">
        <b>演示模式（MOCK）</b>——AI 返回写死的假数据，不调用真实模型、不产生费用。
        切换真实模式：改后端 .env 的 AI_MODE=REAL 并重启
      </span>
    </div>

    <!-- 主密钥未配 → Key 明文落库（批89 清查 P0：这个事实从日志搬到脸上） -->
    <div v-if="!keyCryptConfigured" class="crypt-banner">
      <ShieldAlert :size="15" />
      <span>
        未配置环境变量 <code>MODEL_KEY_CRYPT_SECRET</code>——所有 API Key 正以<b>明文</b>存进数据库。
        功能不受影响，但请配置主密钥并重启后，到各渠道重新保存一次 Key（旧明文行会被覆盖为密文）。
      </span>
    </div>

    <!-- 渠道卡片区 -->
    <DataStateBoundary
      :loading="loading"
      :error="loadError"
      :count="channels.length"
      empty-title="暂无渠道配置"
      empty-description="系统尚未写入任何渠道配置，请确认后端已启动并完成 AI 渠道镜像"
      @retry="loadChannels"
    >
      <!-- 内容区 -->
      <div class="channels-grid">
        <!-- chat 渠道 -->
        <div class="channel-card channel-card--chat">
          <div class="channel-card__header">
            <Cpu class="channel-icon" />
            <span class="channel-key">chat</span>
            <el-tag :type="statusType(chatChannel?.status ?? null)" size="small">
              {{ dash(chatChannel?.status) }}
            </el-tag>
          </div>
          <div class="channel-card__body">
            <div class="field-row">
              <span class="field-label">供应商</span>
              <span class="field-value"><el-tag size="small" type="primary" effect="plain">{{ dash(chatChannel?.provider) }}</el-tag></span>
            </div>
            <div class="field-row">
              <span class="field-label">API 协议</span>
              <span class="field-value">
                {{ dash(protocolLabel(chatChannel?.protocol)) }}
                <span v-if="isUnsupportedProtocol(chatChannel?.protocol)" class="protocol-unsupported-flag" title="该协议暂未接入调用层，当前按 OpenAI 兼容处理">未接入</span>
              </span>
            </div>
            <div class="field-row">
              <span class="field-label">端点</span>
              <span class="field-value field-value--mono">{{ dash(chatChannel?.baseUrl) }}</span>
            </div>
            <div class="field-row">
              <span class="field-label">Turbo 模型</span>
              <span class="field-value">{{ dash(chatChannel?.turboModel) }}</span>
            </div>
            <div class="field-row">
              <span class="field-label">Reasoner 模型</span>
              <span class="field-value">{{ dash(chatChannel?.reasonerModel) }}</span>
            </div>
            <div class="field-row">
              <span class="field-label">脱敏 Key</span>
              <ChannelKeyRow
                :masked="chatChannel?.maskedKey ?? null"
                :revealed="revealedKeys.chat"
                :busy="revealing === 'chat'"
                @toggle="chatChannel && toggleReveal(chatChannel)"
                @copy="chatChannel && copyPlainKey(chatChannel)"
              />
            </div>
            <div class="field-row">
              <span class="field-label">备用模型</span>
              <span class="field-value">
                <el-tag v-if="chatChannel?.fallbackModel" type="warning" size="small" effect="plain">
                  {{ chatChannel.fallbackModel }}
                </el-tag>
                <span v-else class="muted">未配置（主模型故障时无自动降级）</span>
              </span>
            </div>
            <div class="field-row">
              <span class="field-label">更新时间</span>
              <span class="field-value">{{ fmtTime(chatChannel?.updatedAt ?? null) }}</span>
            </div>
            <div v-if="chatChannel" class="field-row">
              <span class="field-label">能力探测</span>
              <span class="field-value">
                <el-tag :type="capabilitySummary('chat').type" size="small" effect="plain">
                  {{ capabilitySummary('chat').text }}
                </el-tag>
                <span v-if="staleReason('chat')" class="stale-flag" :title="staleReason('chat')!">结果可能过期</span>
              </span>
            </div>
            <button v-if="chatChannel" class="switch-btn" @click="openTemplateDialog(chatChannel)">切换供应商</button>
            <button v-if="chatChannel" class="edit-btn" @click="openEdit(chatChannel)">编辑</button>
            <div v-if="chatChannel" class="btn-pair">
              <button class="tool-btn" @click="openHistory(chatChannel)">
                <History class="tool-icon" />变更历史
              </button>
              <button class="tool-btn" @click="openCapability(chatChannel)">
                <FlaskConical class="tool-icon" />能力探测
              </button>
            </div>
            <button v-if="chatChannel" class="reset-btn" @click="resetChannel(chatChannel)">重置为 yml 默认值</button>
          </div>
        </div>

        <!-- embedding 渠道 -->
        <div class="channel-card channel-card--embedding">
          <div class="channel-card__header">
            <Layers class="channel-icon" />
            <span class="channel-key">embedding</span>
            <el-tag :type="statusType(embeddingChannel?.status ?? null)" size="small">
              {{ dash(embeddingChannel?.status) }}
            </el-tag>
          </div>
          <div class="channel-card__body">
            <div class="field-row">
              <span class="field-label">供应商</span>
              <span class="field-value"><el-tag size="small" type="primary" effect="plain">{{ dash(embeddingChannel?.provider) }}</el-tag></span>
            </div>
            <div class="field-row">
              <span class="field-label">API 协议</span>
              <span class="field-value">
                {{ dash(protocolLabel(embeddingChannel?.protocol)) }}
                <span v-if="isUnsupportedProtocol(embeddingChannel?.protocol)" class="protocol-unsupported-flag" title="该协议暂未接入调用层，当前按 OpenAI 兼容处理">未接入</span>
              </span>
            </div>
            <div class="field-row">
              <span class="field-label">端点</span>
              <span class="field-value field-value--mono">{{ dash(embeddingChannel?.baseUrl) }}</span>
            </div>
            <div class="field-row">
              <span class="field-label">模型</span>
              <span class="field-value">{{ dash(embeddingChannel?.model) }}</span>
            </div>
            <div class="field-row">
              <span class="field-label">向量维度</span>
              <span class="field-value">
                <span v-if="embeddingChannel?.dimension" class="dimension-badge">
                  {{ embeddingChannel.dimension }}
                </span>
                <span v-else>—</span>
              </span>
            </div>
            <div class="field-row">
              <span class="field-label">脱敏 Key</span>
              <ChannelKeyRow
                :masked="embeddingChannel?.maskedKey ?? null"
                :revealed="revealedKeys.embedding"
                :busy="revealing === 'embedding'"
                @toggle="embeddingChannel && toggleReveal(embeddingChannel)"
                @copy="embeddingChannel && copyPlainKey(embeddingChannel)"
              />
            </div>
            <div class="field-row">
              <span class="field-label">更新时间</span>
              <span class="field-value">{{ fmtTime(embeddingChannel?.updatedAt ?? null) }}</span>
            </div>
            <div v-if="embeddingChannel" class="field-row">
              <span class="field-label">能力探测</span>
              <span class="field-value">
                <el-tag :type="capabilitySummary('embedding').type" size="small" effect="plain">
                  {{ capabilitySummary('embedding').text }}
                </el-tag>
                <span v-if="staleReason('embedding')" class="stale-flag" :title="staleReason('embedding')!">结果可能过期</span>
              </span>
            </div>
            <button v-if="embeddingChannel" class="switch-btn" @click="openTemplateDialog(embeddingChannel)">切换供应商</button>
            <button v-if="embeddingChannel" class="edit-btn" @click="openEdit(embeddingChannel)">编辑</button>
            <div v-if="embeddingChannel" class="btn-pair">
              <button class="tool-btn" @click="openHistory(embeddingChannel)">
                <History class="tool-icon" />变更历史
              </button>
              <button class="tool-btn" @click="openCapability(embeddingChannel)">
                <FlaskConical class="tool-icon" />能力探测
              </button>
            </div>
            <button v-if="embeddingChannel" class="reset-btn" @click="resetChannel(embeddingChannel)">重置为 yml 默认值</button>
          </div>
        </div>

        <!-- reranker 渠道 -->
        <div class="channel-card channel-card--reranker">
          <div class="channel-card__header">
            <Search class="channel-icon" />
            <span class="channel-key">reranker</span>
            <el-tag :type="statusType(rerankerChannel?.status ?? null)" size="small">
              {{ dash(rerankerChannel?.status) }}
            </el-tag>
          </div>
          <div class="channel-card__body">
            <div class="field-row">
              <span class="field-label">供应商</span>
              <span class="field-value"><el-tag size="small" type="primary" effect="plain">{{ dash(rerankerChannel?.provider) }}</el-tag></span>
            </div>
            <div class="field-row">
              <span class="field-label">API 协议</span>
              <span class="field-value">
                {{ dash(protocolLabel(rerankerChannel?.protocol)) }}
                <span v-if="isUnsupportedProtocol(rerankerChannel?.protocol)" class="protocol-unsupported-flag" title="该协议暂未接入调用层，当前按 OpenAI 兼容处理">未接入</span>
              </span>
            </div>
            <div class="field-row">
              <span class="field-label">端点</span>
              <span class="field-value field-value--mono">{{ dash(rerankerChannel?.baseUrl) }}</span>
            </div>
            <div class="field-row">
              <span class="field-label">模型</span>
              <span class="field-value">{{ dash(rerankerChannel?.model) }}</span>
            </div>
            <div class="field-row">
              <span class="field-label">脱敏 Key</span>
              <ChannelKeyRow
                :masked="rerankerChannel?.maskedKey ?? null"
                :revealed="revealedKeys.reranker"
                :busy="revealing === 'reranker'"
                @toggle="rerankerChannel && toggleReveal(rerankerChannel)"
                @copy="rerankerChannel && copyPlainKey(rerankerChannel)"
              />
            </div>
            <div class="field-row">
              <span class="field-label">更新时间</span>
              <span class="field-value">{{ fmtTime(rerankerChannel?.updatedAt ?? null) }}</span>
            </div>
            <div v-if="rerankerChannel" class="field-row">
              <span class="field-label">能力探测</span>
              <span class="field-value">
                <el-tag :type="capabilitySummary('reranker').type" size="small" effect="plain">
                  {{ capabilitySummary('reranker').text }}
                </el-tag>
                <span v-if="staleReason('reranker')" class="stale-flag" :title="staleReason('reranker')!">结果可能过期</span>
              </span>
            </div>
            <button v-if="rerankerChannel" class="switch-btn" @click="openTemplateDialog(rerankerChannel)">切换供应商</button>
            <button v-if="rerankerChannel" class="edit-btn" @click="openEdit(rerankerChannel)">编辑</button>
            <div v-if="rerankerChannel" class="btn-pair">
              <button class="tool-btn" @click="openHistory(rerankerChannel)">
                <History class="tool-icon" />变更历史
              </button>
              <button class="tool-btn" @click="openCapability(rerankerChannel)">
                <FlaskConical class="tool-icon" />能力探测
              </button>
            </div>
            <button v-if="rerankerChannel" class="reset-btn" @click="resetChannel(rerankerChannel)">重置为 yml 默认值</button>
          </div>
        </div>
      </div>
    </DataStateBoundary>

    <!-- P1 编辑对话框（非抽屉，遵循项目 UI 约定） -->
    <el-dialog
      v-model="editOpen"
      :title="editTarget ? `编辑渠道 — ${editTarget.channelKey}` : '编辑渠道'"
      width="560px"
      :close-on-click-modal="false"
    >
      <div v-if="editTarget" class="edit-body">
        <!-- 热更新失败回落提示 -->
        <div v-if="editRestartHint" class="edit-restart-hint">
          配置已保存，但<b>热更新失败</b>——需重启后端才对新请求生效。
        </div>

        <div class="edit-form">
          <!-- P3-2 供应商模板快速填充 -->
          <label class="edit-label">供应商模板</label>
          <div class="vendor-chips">
            <button
              v-for="(v, key) in VENDOR_TEMPLATES"
              :key="key"
              class="vendor-chip"
              :class="{ 'vendor-chip--active': editForm.baseUrl === v.baseUrl }"
              @click="applyVendorTemplate(key)"
            >{{ v.label }}</button>
          </div>

          <label class="edit-label">
            API 协议
            <span class="edit-label-hint">（调用契约，勿靠 URL 猜）</span>
          </label>
          <select v-model="editForm.protocol" class="edit-input">
            <option value="OPENAI_COMPATIBLE">OpenAI 兼容（assistant/DeepSeek/智谱/本地 vLLM 等绝大多数）</option>
            <option value="AZURE_OPENAI">Azure OpenAI（/openai/deployments 路径 + api-version）</option>
            <option value="ANTHROPIC">Anthropic 原生（/v1/messages）</option>
            <option value="CUSTOM">自定义/其他（按 OpenAI 兼容处理）</option>
          </select>
          <!-- 仅 CUSTOM（未识别协议）提示：它按 OpenAI 兼容处理，可能不匹配。Anthropic/Azure 已真实接入。 -->
          <p v-if="editForm.protocol === 'CUSTOM'" class="protocol-warn">
            ⚠️ 自定义协议将按 <b>OpenAI 兼容协议</b>处理。若该服务不兼容 OpenAI 格式会调用失败——
            Anthropic/Azure OpenAI 请直接用对应选项（已真实接入）。
          </p>

          <label class="edit-label">
            供应商
            <span class="edit-label-hint">（留空 = 按端点地址自动识别）</span>
          </label>
          <input v-model="editForm.provider" class="edit-input" placeholder="如：阿里云 assistant / Azure / 自建网关" />

          <label class="edit-label">端点地址</label>
          <input v-model="editForm.baseUrl" class="edit-input" placeholder="https://api.example.com/v1" />

          <template v-if="isChatChannel">
            <label class="edit-label">
              Turbo 模型
              <button class="edit-label-btn" :disabled="loadingModels" @click="loadModels">{{ loadingModels ? '获取中…' : '获取列表' }}</button>
            </label>
            <input v-model="editForm.turboModel" class="edit-input" :list="'models-turbo'" placeholder="qwen-turbo" />
            <datalist id="models-turbo"><option v-for="m in availableModels" :key="m" :value="m" /></datalist>

            <label class="edit-label">Reasoner 模型</label>
            <input v-model="editForm.reasonerModel" class="edit-input" :list="'models-reasoner'" placeholder="deepseek-v4-flash-0731" />
            <datalist id="models-reasoner"><option v-for="m in availableModels" :key="m" :value="m" /></datalist>
          </template>

          <template v-else>
            <label class="edit-label">
              模型名
              <button class="edit-label-btn" :disabled="loadingModels" @click="loadModels">{{ loadingModels ? '获取中…' : '获取列表' }}</button>
            </label>
            <input v-model="editForm.model" class="edit-input" :list="'models-other'" placeholder="model-name" />
            <datalist id="models-other"><option v-for="m in availableModels" :key="m" :value="m" /></datalist>

            <label v-if="isEmbeddingChannel" class="edit-label">向量维度（铁律 {{ editForm.dimension }}，不可编辑）</label>
            <input v-if="isEmbeddingChannel" :value="editForm.dimension" class="edit-input" disabled title="维度只读：铁律由 devops.ai.vector.dimension 与 V1 基线 VECTOR(1536) 锁定" />
          </template>

          <label class="edit-label">
            API Key
            <span class="edit-label-hint">（留空 = 保留现有 Key）</span>
          </label>
          <SecretInput
            v-model="editForm.apiKey"
            class="secret-wrap--box"
            autocomplete="off"
            placeholder="新 Key（留空不修改，眼睛随时可核对）"
          />

          <label class="edit-label">状态</label>
          <select v-model="editForm.status" class="edit-input">
            <option value="ACTIVE">ACTIVE — 启用</option>
            <option value="DISABLED">DISABLED — 停用</option>
          </select>

          <!-- 备用模型（方案 A：模型池降级）——仅 chat 渠道 -->
          <template v-if="isChatChannel">
            <div class="fallback-section">
              <label class="edit-label fallback-toggle">
                <input v-model="editForm.fallbackEnabled" type="checkbox" />
                启用备用模型
                <span class="edit-label-hint">（主模型超时/限流/宕机时自动降级）</span>
              </label>

              <template v-if="editForm.fallbackEnabled">
                <div class="fallback-note">
                  建议选与主模型<b>不同厂商</b>的端点（如主 qwen、备 deepseek），
                  否则同一厂商整体故障时备用同样不可用。embedding 渠道不支持备用
                  （换模型 = 向量维度语义空间不兼容 = 全库重建）。
                </div>

                <label class="edit-label">备用端点地址</label>
                <input v-model="editForm.fallbackBaseUrl" class="edit-input" placeholder="https://api.deepseek.com/v1" />

                <label class="edit-label">备用模型名</label>
                <input v-model="editForm.fallbackModel" class="edit-input" placeholder="deepseek-chat" />

                <label class="edit-label">
                  备用 API Key
                  <span v-if="editTarget?.fallbackMaskedKey" class="edit-label-hint">
                    （现有 {{ editTarget.fallbackMaskedKey }}，留空 = 保留）
                  </span>
                  <span v-else class="edit-label-hint">（首次配置必填）</span>
                </label>
                <SecretInput
                  v-model="editForm.fallbackApiKey"
                  class="secret-wrap--box"
                  autocomplete="off"
                  placeholder="备用 Key（留空不修改现有）"
                />
              </template>
              <div v-else-if="editTarget?.fallbackModel" class="fallback-note">
                当前已配置备用模型 <b>{{ editTarget.fallbackModel }}</b>；保持未勾选并保存将<b>清除</b>备用配置。
              </div>
            </div>
          </template>
        </div>
      </div>

      <template #footer>
        <button
          v-if="isChatChannel || isEmbeddingChannel"
          class="edit-btn-test"
          :disabled="testing"
          @click="testConnectivity"
        >{{ testing ? '测试中…' : '测试连接' }}</button>
        <span v-if="testResult" :class="['test-result', testResult.success ? 'test-ok' : 'test-fail']">
          {{ testResult.success ? '✓' : '✗' }} {{ testResult.message }}
        </span>
        <div class="edit-footer-right">
          <button class="edit-btn-cancel" @click="editOpen = false">取消</button>
          <button class="edit-btn-save" :disabled="saving" @click="submitEdit">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </template>
    </el-dialog>

    <!-- V5 变更历史 Dialog：快照列表 + 一键回滚（非抽屉，遵循 UI 约定） -->
    <el-dialog
      v-model="historyOpen"
      :title="historyTarget ? `变更历史 — ${historyTarget.channelKey}` : '变更历史'"
      width="720px"
    >
      <div class="history-body">
        <p class="history-desc">
          每次编辑/回滚/重置前的<b>整行快照</b>（新→旧）。回滚任意版本前会先自动快照当前态——滚错了还能再滚回来。
        </p>
        <div v-if="historyLoading" class="history-empty">加载中…</div>
        <div v-else-if="historyList.length === 0" class="history-empty">
          暂无变更记录——渠道自镜像以来未被编辑过
        </div>
        <div v-else class="history-list">
          <div v-for="h in historyList" :key="h.id" class="history-item">
            <div class="history-item__main" @click="toggleDiff(h)">
              <div class="history-item__meta">
                <span class="history-id">#{{ h.id }}</span>
                <span class="history-note">{{ h.changeNote || '变更' }}</span>
                <span class="history-time">{{ fmtTime(h.changedAt) }} · {{ h.changedBy }}</span>
                <span class="diff-toggle">{{ diffOpenId === h.id ? '收起对比 ▲' : '对比当前 ▼' }}</span>
              </div>
              <div class="history-item__config">
                <span class="history-url">{{ h.baseUrl || '—' }}</span>
                <span class="history-model">
                  {{ h.turboModel ? `${h.turboModel} / ${h.reasonerModel}` : h.model || '—' }}
                </span>
                <el-tag v-if="h.status === 'DISABLED'" type="info" size="small">DISABLED</el-tag>
              </div>
              <!-- 建议2：当前 vs 目标版本 diff（内嵌展开，非抽屉） -->
              <div v-if="diffOpenId === h.id" class="diff-panel" @click.stop>
                <div class="diff-head">
                  <span class="diff-col-label">字段</span>
                  <span class="diff-col-label">当前配置</span>
                  <span class="diff-col-label">回滚目标 #{{ h.id }}</span>
                </div>
                <div
                  v-for="row in diffRows(h)"
                  :key="row.label"
                  class="diff-row"
                  :class="{ 'diff-row--changed': row.changed }"
                >
                  <span class="diff-cell diff-cell--label">{{ row.label }}</span>
                  <span class="diff-cell">{{ row.current }}</span>
                  <span class="diff-cell">{{ row.target }}</span>
                </div>
                <p v-if="diffRows(h).every(r => !r.changed)" class="diff-all-same">
                  与当前配置完全一致——回滚后无变化
                </p>
              </div>
            </div>
            <button
              class="rollback-btn"
              :disabled="rollingBackId !== null"
              @click="rollbackTo(h)"
            >{{ rollingBackId === h.id ? '回滚中…' : '回滚到此版本' }}</button>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- V5 能力探测 Dialog：三态明细 + 手动重新实测（非抽屉） -->
    <el-dialog
      v-model="capabilityOpen"
      :title="capabilityTarget ? `能力探测 — ${capabilityTarget.channelKey}` : '能力探测'"
      width="560px"
    >
      <div v-if="capabilityTarget" class="capability-body">
        <p class="capability-desc">
          连通性测试只证明「能调通」，这里实测平台真正依赖的能力：
          Agent 靠<b>工具调用</b>、SSE 靠<b>流式</b>、分析卡片靠 <b>JSON 模式</b>。
          探测是真实 API 调用（计费 + 秒级延迟），结果落库复用。
        </p>

        <div v-if="!capabilityMap[capabilityTarget.channelKey]" class="capability-empty">
          尚未实测过——点击下方按钮触发第一轮探测
        </div>

        <div v-else class="capability-list">
          <!-- 建议3：探测结果过期提醒（配置变更后未重探 / 探测超 7 天） -->
          <div v-if="staleReason(capabilityTarget.channelKey)" class="stale-banner">
            ⚠️ {{ staleReason(capabilityTarget.channelKey) }}——建议点击下方按钮重新实测
          </div>
          <div
            v-for="(item, cap) in capabilityMap[capabilityTarget.channelKey]!.capabilities"
            :key="cap"
            class="capability-item"
          >
            <div class="capability-item__head">
              <span class="capability-name">{{ cap }}</span>
              <el-tag :type="capabilityTagType(item.state)" size="small">
                {{ capabilityTagText(item.state) }}
              </el-tag>
            </div>
            <div class="capability-detail">{{ item.detail }}</div>
          </div>
          <p class="capability-probed-at">
            实测时间：{{ fmtTime(capabilityMap[capabilityTarget.channelKey]!.probedAt) }}
            <span class="edit-label-hint">（UNKNOWN = 探测失败如超时/限流，不代表不支持）</span>
          </p>
        </div>
      </div>

      <template #footer>
        <button class="edit-btn-cancel" @click="capabilityOpen = false">关闭</button>
        <button class="edit-btn-save" :disabled="probing" @click="runProbe">
          {{ probing ? '实测中（最长约 60s）…' : (capabilityMap[capabilityTarget?.channelKey ?? ''] ? '重新实测' : '开始实测') }}
        </button>
      </template>
    </el-dialog>

    <!-- 切换供应商（渠道模板库，多渠道一键切换） -->
    <el-dialog v-model="templateDialogOpen" :title="`切换供应商 — ${templateDialogChannel?.channelKey ?? ''}`" width="560px">
      <p class="template-dialog-desc">
        选择要切换到的供应商模板。应用只替换端点/协议/供应商/模型名，<b>现有 API Key 保留</b>。
      </p>
      <div class="template-list">
        <button
          v-for="tpl in templates"
          :key="tpl.id"
          class="template-item"
          :disabled="applyingTemplate"
          @click="applyTemplate(tpl)"
        >
          <div class="template-item__head">
            <span class="template-item__name">{{ tpl.templateName }}</span>
            <el-tag size="small" effect="plain" type="info">{{ protocolLabel(tpl.protocol) }}</el-tag>
          </div>
          <div class="template-item__url">{{ tpl.baseUrl }}</div>
          <div class="template-item__models">
            {{ tpl.channelKey === 'chat' ? `${tpl.turboModel} / ${tpl.reasonerModel}` : tpl.model }}
          </div>
          <div v-if="tpl.description" class="template-item__desc">{{ tpl.description }}</div>
        </button>
        <p v-if="!templates.length" class="template-empty">该渠道暂无可用模板</p>
      </div>
    </el-dialog>

    <!-- 刷新按钮 -->
    <div class="page-actions">
      <button class="btn-retry" @click="loadChannels" :disabled="loading">
        <RefreshCw :class="['retry-icon', { spinning: loading }]" />
        刷新配置
      </button>
    </div>
  </div>
</template>

<style scoped>
.page-container {
  max-width: 900px;
  padding: 24px;
}

.page-header {
  margin-bottom: 24px;
}

.page-header__title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
  margin: 0;
}

.badge-p1 {
  font-size: 11px;
  font-weight: 600;
  color: #0d9488;
  background: #ccfbf1;
  border: 1px solid #5eead4;
  border-radius: 4px;
  padding: 2px 8px;
  letter-spacing: 0.05em;
}

.page-desc {
  font-size: 13px;
  color: var(--text-2);
  margin: 0;
  line-height: 1.6;
}

/* AI 模式横幅：REAL=绿（真实调用），MOCK=黄（演示假数据），一眼可辨 */
.ai-mode-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  margin-bottom: 16px;
  border-radius: var(--radius);
  font-size: 13px;
  line-height: 1.5;
  border: 1px solid;
}
.ai-mode-banner__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.ai-mode-banner--real {
  background: var(--success-subtle, #f0fdf4);
  border-color: var(--success, #16a34a);
  color: var(--success, #15803d);
}
.ai-mode-banner--real .ai-mode-banner__dot { background: var(--success, #16a34a); }
.ai-mode-banner--mock {
  background: var(--warning-subtle, #fffbeb);
  border-color: var(--warning, #d97706);
  color: var(--warning, #b45309);
}
.ai-mode-banner--mock .ai-mode-banner__dot { background: var(--warning, #d97706); }

/* 三列卡片 */
.channels-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

.channel-card {
  border: 1px solid var(--border-1);
  border-radius: 8px;
  background: var(--surface-1, var(--surface-1));
  overflow: hidden;
}

.channel-card__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-1);
  background: var(--surface-0);
}

.channel-card--chat .channel-card__header { background: #eff6ff; border-left: 3px solid #3b82f6; }
.channel-card--embedding .channel-card__header { background: #f0fdf4; border-left: 3px solid #22c55e; }
.channel-card--reranker .channel-card__header { background: #faf5ff; border-left: 3px solid #a855f7; }

.channel-icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}
.channel-card--chat .channel-icon { color: #3b82f6; }
.channel-card--embedding .channel-icon { color: #22c55e; }
.channel-card--reranker .channel-icon { color: #a855f7; }

.channel-key {
  font-weight: 600;
  font-size: 14px;
  flex: 1;
}

.channel-card__body {
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  line-height: 1.5;
}

.field-label {
  color: var(--text-2);
  min-width: 80px;
  flex-shrink: 0;
}

.field-value {
  color: var(--text-1);
  word-break: break-all;
}

.field-value--mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
}

.dimension-badge {
  background: #dcfce7;
  color: #15803d;
  border: 1px solid #86efac;
  border-radius: 4px;
  padding: 0 6px;
  font-weight: 600;
  font-size: 12px;
}

/* 刷新按钮 */
.page-actions {
  display: flex;
  justify-content: flex-end;
}

.btn-retry {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border: 1px solid var(--border-1);
  border-radius: 6px;
  background: var(--surface-1, var(--surface-1));
  color: var(--text-1);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-retry:hover:not(:disabled) {
  background: #f3f4f6;
}

.btn-retry:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.retry-icon {
  width: 14px;
  height: 14px;
}

.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ── P1 编辑按钮 ── */

.edit-btn {
  display: block;
  width: 100%;
  margin-top: 8px;
  padding: 5px 0;
  border: 1px solid var(--border-1);
  border-radius: 4px;
  background: #f9fafb;
  color: #374151;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.15s;
}

.edit-btn:hover { background: #e5e7eb; }

/* 切换供应商按钮（模板库一键切换，品牌色描边以区分于普通编辑） */
.switch-btn {
  display: block;
  width: 100%;
  margin-top: 8px;
  padding: 5px 0;
  border: 1px solid var(--brand, #2563eb);
  border-radius: 4px;
  background: var(--brand-subtle, #eff6ff);
  color: var(--brand, #2563eb);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}
.switch-btn:hover { background: var(--brand, #2563eb); color: #fff; }

/* 模板选择对话框 */
.template-dialog-desc { font-size: 13px; color: var(--text-2); margin: 0 0 12px; line-height: 1.6; }
.template-list { display: flex; flex-direction: column; gap: 10px; }
.template-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  background: var(--surface-1);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s, background 0.15s;
}
.template-item:hover:not(:disabled) { border-color: var(--brand, #2563eb); background: var(--brand-subtle, #eff6ff); }
.template-item:disabled { opacity: 0.6; cursor: not-allowed; }
.template-item__head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.template-item__name { font-weight: 600; color: var(--text-1); font-size: 13px; }
.template-item__url { font-family: var(--font-mono, monospace); font-size: 11px; color: var(--text-3); word-break: break-all; }
.template-item__models { font-size: 12px; color: var(--text-2); }
.template-item__desc { font-size: 12px; color: var(--text-3); }
.template-empty { text-align: center; color: var(--text-3); font-size: 13px; padding: 24px 0; }

/* 方案 B 协议边界提示：编辑表单选中非 OpenAI 协议时的警示横幅 */
.protocol-warn {
  margin: 6px 0 0;
  padding: 8px 10px;
  background: var(--warning-subtle, #fffbeb);
  border: 1px solid var(--warning, #d97706);
  border-radius: var(--radius-sm);
  color: var(--warning, #b45309);
  font-size: 12px;
  line-height: 1.5;
}
/* 渠道卡片协议行的「未接入」警示徽标 */
.protocol-unsupported-flag {
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 8px;
  background: var(--warning-subtle, #fffbeb);
  border: 1px solid var(--warning, #d97706);
  color: var(--warning, #b45309);
  font-size: 11px;
  font-weight: 600;
  cursor: help;
}

.reset-btn {
  display: block;
  width: 100%;
  margin-top: 4px;
  padding: 5px 0;
  border: 1px dashed #fca5a5;
  border-radius: 4px;
  background: #fef2f2;
  color: #dc2626;
  font-size: 11px;
  cursor: pointer;
  transition: background 0.15s;
}

.reset-btn:hover { background: #fee2e2; }

/* ── P1 编辑对话框 ── */

.edit-body { display: flex; flex-direction: column; gap: 12px; }

.edit-restart-hint {
  background: #fffbeb;
  border: 1px solid #fcd34d;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12px;
  color: #92400e;
}

.edit-form { display: flex; flex-direction: column; gap: 6px; }

.edit-label {
  font-size: 12px;
  font-weight: 600;
  color: #374151;
  margin-top: 4px;
}

.edit-label-hint { font-weight: 400; color: #9ca3af; }

.edit-input {
  border: 1px solid var(--border-1);
  border-radius: 4px;
  padding: 6px 10px;
  font-size: 13px;
  background: var(--surface-1, var(--surface-1));
}

.edit-input:disabled { background: #f3f4f6; color: #9ca3af; }

.edit-input--key { font-family: monospace; }

/* 主密钥未配的明文落库警告（批89）；编辑弹窗密钥框等宽字体 */
.crypt-banner {
  display: flex; align-items: flex-start; gap: 8px; margin-bottom: 14px; padding: 10px 13px;
  border-radius: 8px; font-size: 12px; line-height: 1.55; color: var(--warning, #d97706);
  background: rgb(from var(--warning, #d97706) r g b / 0.08);
  border: 1px solid rgb(from var(--warning, #d97706) r g b / 0.28);
  code { font-family: monospace; } b { font-weight: 600; }
}
:deep(.secret-wrap--box) { font-family: monospace; }

/* 备用模型区（方案 A） */
.fallback-section {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--border-1);
}
.fallback-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.fallback-toggle input[type="checkbox"] { width: auto; margin: 0; cursor: pointer; }
.fallback-note {
  font-size: 12px;
  line-height: 1.6;
  color: #92700c;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 4px;
  padding: 8px 10px;
  margin: 8px 0;
}
.muted { color: #9ca3af; font-size: 12px; }

.edit-btn-cancel,
.edit-btn-save {
  border-radius: 4px;
  padding: 6px 16px;
  font-size: 13px;
  cursor: pointer;
}

.edit-btn-cancel {
  border: 1px solid var(--border-1);
  background: var(--surface-1, var(--surface-1));
  color: #374151;
  margin-right: 8px;
}

.edit-btn-save {
  border: none;
  background: #3b82f6;
  color: #fff;
}

.edit-btn-save:disabled { opacity: 0.5; cursor: not-allowed; }

.edit-btn-save:hover:not(:disabled) { background: #2563eb; }

/* ── P2-3 连通性测试 ── */

.edit-btn-test {
  border: 1px solid #93c5fd;
  border-radius: 4px;
  padding: 6px 14px;
  font-size: 12px;
  background: #eff6ff;
  color: #1d4ed8;
  cursor: pointer;
}

.edit-btn-test:disabled { opacity: 0.5; cursor: not-allowed; }

.edit-btn-test:hover:not(:disabled) { background: #dbeafe; }

.edit-footer-right { margin-left: auto; display: flex; gap: 6px; }

.test-result { font-size: 12px; margin-left: 8px; }

.test-ok { color: #15803d; }

.test-fail { color: #dc2626; }

/* ── P3-2 供应商模板 ── */

.vendor-chips { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 2px; }

.vendor-chip {
  padding: 2px 8px;
  border: 1px solid #d1d5db;
  border-radius: 12px;
  background: var(--surface-1, var(--surface-1));
  font-size: 11px;
  cursor: pointer;
}

.vendor-chip:hover { background: #f0f9ff; border-color: #3b82f6; }

.vendor-chip--active { background: #dbeafe; border-color: #3b82f6; color: #1d4ed8; }

.edit-label-btn {
  margin-left: 8px;
  padding: 1px 6px;
  border: 1px solid #d1d5db;
  border-radius: 3px;
  background: #f9fafb;
  font-size: 10px;
  color: #6b7280;
  cursor: pointer;
}

.edit-label-btn:hover:not(:disabled) { background: #e5e7eb; }

.edit-label-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── V5：工具按钮对（变更历史 / 能力探测） ── */

.btn-pair {
  display: flex;
  gap: 6px;
  margin-top: 4px;
}

.tool-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 5px 0;
  border: 1px solid var(--border-1);
  border-radius: 4px;
  background: var(--surface-1, var(--surface-1));
  color: #4b5563;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.15s;
}

.tool-btn:hover { background: #f0f9ff; border-color: #93c5fd; color: #1d4ed8; }

.tool-icon { width: 13px; height: 13px; }

/* ── V5：变更历史 Dialog ── */

.history-body { display: flex; flex-direction: column; gap: 10px; }

.history-desc {
  font-size: 12px;
  color: var(--text-2);
  margin: 0;
  line-height: 1.6;
}

.history-empty {
  text-align: center;
  color: #9ca3af;
  font-size: 13px;
  padding: 24px 0;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 420px;
  overflow-y: auto;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid var(--border-1);
  border-radius: 6px;
  padding: 10px 12px;
}

.history-item__main { flex: 1; min-width: 0; }

.history-item__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.history-id {
  font-family: monospace;
  font-size: 12px;
  color: #6366f1;
  font-weight: 600;
}

.history-note {
  font-size: 12px;
  font-weight: 600;
  color: #374151;
  background: #f3f4f6;
  border-radius: 3px;
  padding: 1px 6px;
}

.history-time { font-size: 11px; color: #9ca3af; }

.history-item__config {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}

.history-url {
  font-family: monospace;
  color: #6b7280;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 300px;
}

.history-model { color: #111827; font-weight: 500; }

.rollback-btn {
  flex-shrink: 0;
  border: 1px solid #c7d2fe;
  border-radius: 4px;
  padding: 5px 12px;
  font-size: 12px;
  background: #eef2ff;
  color: #4338ca;
  cursor: pointer;
}

.rollback-btn:hover:not(:disabled) { background: #e0e7ff; }

.rollback-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── 建议2：当前 vs 目标版本 diff ── */

.history-item__main { cursor: pointer; }

.diff-toggle {
  margin-left: auto;
  font-size: 11px;
  color: var(--brand, #2563eb);
  user-select: none;
}

.diff-panel {
  margin-top: 8px;
  border-top: 1px dashed var(--border-1);
  padding-top: 8px;
  cursor: default;
}

.diff-head, .diff-row {
  display: grid;
  grid-template-columns: 110px 1fr 1fr;
  gap: 8px;
  align-items: center;
}

.diff-head {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-3, #9ca3af);
  padding: 2px 6px;
}

.diff-row {
  font-size: 12px;
  padding: 4px 6px;
  border-radius: 4px;
}

.diff-row--changed { background: rgba(245, 158, 11, 0.12); }

.diff-row--changed .diff-cell--label { color: #b45309; font-weight: 600; }

.diff-cell {
  font-family: monospace;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.diff-cell--label { font-family: inherit; color: var(--text-2); }

.diff-all-same {
  font-size: 12px;
  color: #16a34a;
  margin: 6px 6px 2px;
}

/* ── 建议3：探测结果过期提醒 ── */

.stale-flag {
  margin-left: 6px;
  font-size: 11px;
  color: #b45309;
  cursor: help;
}

.stale-banner {
  font-size: 12px;
  color: #92400e;
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.35);
  border-radius: 6px;
  padding: 8px 10px;
  line-height: 1.5;
}

/* ── V5：能力探测 Dialog ── */

.capability-body { display: flex; flex-direction: column; gap: 10px; }

.capability-desc {
  font-size: 12px;
  color: var(--text-2);
  margin: 0;
  line-height: 1.6;
}

.capability-empty {
  text-align: center;
  color: #9ca3af;
  font-size: 13px;
  padding: 24px 0;
  border: 1px dashed var(--border-1);
  border-radius: 6px;
}

.capability-list { display: flex; flex-direction: column; gap: 8px; }

.capability-item {
  border: 1px solid var(--border-1);
  border-radius: 6px;
  padding: 8px 12px;
}

.capability-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2px;
}

.capability-name {
  font-family: monospace;
  font-size: 12px;
  font-weight: 600;
  color: #374151;
}

.capability-detail { font-size: 12px; color: #6b7280; line-height: 1.5; }

.capability-probed-at {
  font-size: 11px;
  color: #9ca3af;
  margin: 4px 0 0;
}
</style>