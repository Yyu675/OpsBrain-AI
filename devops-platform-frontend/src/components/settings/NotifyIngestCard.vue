<script setup lang="ts">
/**
 * 告警接入密钥卡（Alertmanager → 本平台 webhook 的共享密钥，批89）。
 *
 * 这张卡比渠道卡多一分小心：改这里的密钥会立刻改变「平台认得哪些告警」——
 * Alertmanager 那边不同步改，下一条告警就 401，且**静默**（告警断流不会
 * 自己报警）。所以保存前强制二次确认 + 保存后把「去改 Alertmanager」的
 * 动作原样打在屏幕上，不留「改完忘了另一半」的空间。
 *
 * 展示语义与渠道卡一致：默认打码，reveal 才出明文（限 ADMIN + 审计留痕）。
 */
import { ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { AlertTriangle, Check, Eye, KeyRound } from 'lucide-vue-next'

import { fetchIngestSecret, updateIngestSecret } from '@/api/notifyChannels'
import type { IngestSecretView, NotifySecretRevealed } from '@/api/types/notify'
import { handleServerError, notify } from '@/utils/notify'
import SecretInput from '@/components/common/SecretInput.vue'

defineOptions({ name: 'NotifyIngestCard' })

defineProps<{ ingest: IngestSecretView }>()
const emit = defineEmits<{ saved: [] }>()

const SOURCE_TEXT: Record<string, string> = { db: 'DB 权威', yml: '环境变量' }

const revealed = ref<string | null>(null)
const revealBusy = ref(false)
const secretForm = ref('')
const saving = ref(false)

const doReveal = async () => {
  if (revealBusy.value) return
  if (revealed.value !== null) { revealed.value = null; return }
  revealBusy.value = true
  try {
    const r = await fetchIngestSecret(true)
    revealed.value = (r as NotifySecretRevealed).webhook ?? ''
  } catch (e) {
    handleServerError(e, { action: '查看接入密钥' })
  } finally {
    revealBusy.value = false
  }
}

const save = async () => {
  if (saving.value) return
  const next = secretForm.value.trim()
  try {
    await ElMessageBox.confirm(
      '修改告警接入密钥会**立即**改变平台校验告警用的密钥。必须同步修改 Alertmanager 的 '
      + 'webhook_configs（url 里的 token 或 X-Webhook-Token 头），否则下一条告警将被拒绝且不会有任何提醒。确认继续？',
      '改密钥会断开告警链，务必同步两端',
      { confirmButtonText: '我已同步 Alertmanager，确认保存', cancelButtonText: '先不去改', type: 'warning' },
    )
  } catch { return }
  saving.value = true
  try {
    await updateIngestSecret(next)
    notify.success('告警接入密钥已保存并即时生效')
    secretForm.value = ''
    revealed.value = null
    emit('saved')
  } catch (e) {
    handleServerError(e, { action: '保存接入密钥' })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <article class="ing-card">
    <header class="ing-head">
      <KeyRound :size="16" class="ing-icon" />
      <div class="ing-title">
        告警接入密钥
        <span class="src-badge" :class="`src-${ingest.source}`">{{ SOURCE_TEXT[ingest.source] ?? ingest.source }}</span>
      </div>
    </header>
    <p class="ing-desc">
      Alertmanager 推送告警时携带的共享密钥（X-Webhook-Token / Bearer）。改这里即时生效，
      <b>必须同步 Alertmanager 的 webhook_configs，否则告警静默断链</b>。
    </p>

    <div class="row">
      <span class="k">当前密钥</span>
      <code v-if="revealed === null" class="v mono">{{ ingest.secretMasked || '未配置' }}</code>
      <code v-else class="v mono break">{{ revealed || '未配置（已清空）' }}</code>
    </div>
    <div v-if="revealed !== null" class="reveal-hint">明文查看已记入审计日志</div>
    <div class="row">
      <span class="k">HMAC 通道</span>
      <span class="v">{{ ingest.hmacConfigured ? '已启用（X-Webhook-Signature 优先）' : '未启用' }}</span>
    </div>

    <div class="edit-row">
      <SecretInput v-model="secretForm" class="secret-wrap--box" autocomplete="off" :placeholder="ingest.configured ? '新密钥（留空则不保存）' : '填入共享密钥'" />
      <div class="btns">
        <button class="btn plain" @click="doReveal"><Eye :size="13" /> {{ revealed === null ? '查看' : '收起' }}</button>
        <button class="btn primary" :disabled="saving || !secretForm.trim()" @click="save"><Check :size="13" /> 保存</button>
      </div>
    </div>
    <p v-if="ingest.hint" class="hint-line"><AlertTriangle :size="12" /> {{ ingest.hint }}</p>
  </article>
</template>

<style scoped lang="scss">
.ing-card {
  padding: 14px 16px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  background: var(--surface-1);
}

.ing-head { display: flex; align-items: center; gap: 8px; }
.ing-icon { color: var(--text-3); }
.ing-title { font-size: var(--text-base); font-weight: var(--weight-semibold); color: var(--text-1); display: flex; align-items: center; gap: 8px; }
.src-badge {
  font-size: 10px; padding: 1px 6px; border-radius: 10px; border: 1px solid var(--border-1); color: var(--text-3);
  &.src-db { color: var(--brand); border-color: var(--brand); }
}

.ing-desc { margin: 8px 0 10px; font-size: var(--text-xs); line-height: 1.6; color: var(--text-3); }
.ing-desc b { color: var(--text-2); font-weight: var(--weight-semibold); }

.row { display: flex; gap: 10px; font-size: var(--text-sm); margin-bottom: 6px; }
.row .k { flex-shrink: 0; width: 84px; color: var(--text-3); }
.row .v { color: var(--text-1); }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: var(--text-xs); }
.break { word-break: break-all; }
.reveal-hint { font-size: 11px; color: var(--text-3); margin: 0 0 6px 94px; }

.edit-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 10px;

  :deep(.secret-wrap) { flex: 1; min-width: 240px; }
}
.btns { display: flex; gap: 8px; }

.hint-line {
  display: flex; align-items: center; gap: 5px;
  margin: 10px 0 0; font-size: 11px; color: var(--warning);
}

.btn {
  display: inline-flex; align-items: center; gap: 5px; height: 30px; padding: 0 12px;
  border-radius: var(--radius-sm); font-size: var(--text-sm); cursor: pointer;
  border: 1px solid var(--border-1); background: var(--surface-1); color: var(--text-1);
  &:disabled { opacity: 0.5; cursor: not-allowed; }
  &.primary { background: var(--brand); color: var(--text-inverse); border-color: var(--brand); }
}
</style>
