<script setup lang="ts">
/**
 * 单个通知渠道卡片（钉钉/企微通用，批89）。
 *
 * 编辑走内嵌展开而非抽屉/弹窗：一次只改一个渠道，就地展开改完即收，
 * 视线不用在列表和弹层之间来回跳（用户明确不喜抽屉式 UI）。
 *
 * 三态编辑语义（对齐后端 PUT）：webhook/密钥「留空 = 保留现有」，
 * 清空用独立按钮显式触发，绝不让「没填」被误当「清空」。
 * 已存的 webhook/密钥不回填进输入框——查看已存明文走 reveal（有审计），
 * 编辑框只接受新值。
 */
import { computed, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Check, Eye, Pencil, Send, X } from 'lucide-vue-next'

import {
  revealNotifyChannel,
  sendNotifyTest,
  updateNotifyChannel,
} from '@/api/notifyChannels'
import type { NotifyChannelView, NotifyTestResult } from '@/api/types/notify'
import { handleServerError, notify } from '@/utils/notify'
import SecretInput from '@/components/common/SecretInput.vue'

defineOptions({ name: 'NotifyChannelCard' })

const props = defineProps<{ channel: NotifyChannelView }>()
const emit = defineEmits<{ saved: [] }>()

const LABELS: Record<string, string> = { dingtalk: '钉钉', wecom: '企业微信' }
const label = computed(() => LABELS[props.channel.channelKey] ?? props.channel.channelKey)
const isDingTalk = computed(() => props.channel.channelKey === 'dingtalk')

const editing = ref(false)
const saving = ref(false)
const testing = ref(false)
const webForm = ref('')
const secretForm = ref('')
const revealed = ref<{ webhook: string | null; secret: string | null } | null>(null)
const revealBusy = ref(false)
const testResult = ref<NotifyTestResult | null>(null)

const SOURCE_TEXT: Record<string, string> = { db: 'DB 权威', yml: '环境变量', none: '未配置' }

const openEdit = () => {
  webForm.value = ''
  secretForm.value = ''
  testResult.value = null
  editing.value = true
}
const cancelEdit = () => { editing.value = false }

const save = async () => {
  if (saving.value) return
  saving.value = true
  try {
    await updateNotifyChannel(props.channel.channelKey as 'dingtalk' | 'wecom', {
      // 三态：空串=不动（保留），真要清空走 clearWebhook 显式路径
      webhook: webForm.value.trim() || null,
      secret: secretForm.value.trim() || null,
    })
    notify.success(`${label.value} 已保存，即时生效`)
    editing.value = false
    revealed.value = null
    emit('saved')
  } catch (e) {
    handleServerError(e, { action: `保存${label.value}配置` })
  } finally {
    saving.value = false
  }
}

const clearWebhook = async () => {
  try {
    await ElMessageBox.confirm(
      `清空 ${label.value} webhook 后该渠道将停止推送（回到未配置态）。确认清空？`,
      '清空确认',
      { confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning' },
    )
  } catch { return }
  saving.value = true
  try {
    await updateNotifyChannel(props.channel.channelKey as 'dingtalk' | 'wecom', { webhook: '' })
    notify.success(`${label.value} webhook 已清空`)
    emit('saved')
  } catch (e) {
    handleServerError(e, { action: `清空${label.value}配置` })
  } finally {
    saving.value = false
  }
}

const toggleEnabled = async (on: boolean) => {
  try {
    await updateNotifyChannel(props.channel.channelKey as 'dingtalk' | 'wecom', { enabled: on })
    notify.success(`${label.value} 已${on ? '启用' : '停用'}`)
    emit('saved')
  } catch (e) {
    handleServerError(e, { action: '切换开关' })
  }
}

const doTest = async () => {
  if (testing.value) return
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await sendNotifyTest(props.channel.channelKey as 'dingtalk' | 'wecom')
  } catch (e) {
    handleServerError(e, { action: '测试发送' })
  } finally {
    testing.value = false
  }
}

const doReveal = async () => {
  if (revealBusy.value) return
  if (revealed.value) { revealed.value = null; return }
  revealBusy.value = true
  try {
    revealed.value = await revealNotifyChannel(props.channel.channelKey as 'dingtalk' | 'wecom')
  } catch (e) {
    handleServerError(e, { action: '查看明文' })
  } finally {
    revealBusy.value = false
  }
}
</script>

<template>
  <article class="ch-card" :class="{ 'is-off': !channel.enabled }">
    <header class="ch-head">
      <div class="ch-title">
        <span class="ch-name">{{ label }}</span>
        <span class="src-badge" :class="`src-${channel.source}`">{{ SOURCE_TEXT[channel.source] ?? channel.source }}</span>
        <span v-if="channel.needsReEntry" class="reentry" title="库里的密文解不开（主密钥变更？），请把 webhook/密钥重新保存一次">需重新保存</span>
      </div>
      <label class="switch" :title="channel.enabled ? '点击停用' : '点击启用'">
        <input type="checkbox" :checked="channel.enabled" @change="toggleEnabled(($event.target as HTMLInputElement).checked)" />
      </label>
    </header>

    <div class="ch-body">
      <div class="row">
        <span class="k">Webhook</span>
        <code class="v mono">{{ channel.webhookMasked || '未配置' }}</code>
      </div>
      <div class="row">
        <span class="k">加签密钥</span>
        <span class="v">
          <template v-if="isDingTalk">{{ channel.hasSecret ? '已配置' : '未配置（关键词/IP 白名单模式）' }}</template>
          <template v-else>—（企微 key 直连无签名）</template>
        </span>
      </div>
      <div v-if="channel.updatedBy" class="row">
        <span class="k">最近修改</span>
        <span class="v">{{ channel.updatedBy }} · {{ channel.updateTime?.replace('T', ' ').slice(0, 16) || '—' }}</span>
      </div>

      <!-- reveal 明文（点击展开，仅本次会话内存显示） -->
      <div v-if="revealed" class="reveal-box">
        <div class="row"><span class="k">明文 URL</span><code class="v mono break">{{ revealed.webhook || '（解不开）' }}</code></div>
        <div v-if="isDingTalk" class="row"><span class="k">明文密钥</span><code class="v mono break">{{ revealed.secret || '未配置' }}</code></div>
        <div class="reveal-hint">明文查看已记入审计日志</div>
      </div>

      <!-- 测试发送结果 -->
      <div v-if="testResult" class="test-box" :class="testResult.success ? 'ok' : 'bad'">
        {{ testResult.success ? '✓ ' : '✗ ' }}{{ testResult.message }}
      </div>

      <!-- 内嵌编辑 -->
      <div v-if="editing" class="edit-box">
        <label class="lbl">Webhook URL<span class="hint">（留空 = 保留现有）</span></label>
        <SecretInput v-model="webForm" class="secret-wrap--box" autocomplete="off" placeholder="https://…/robot/send?access_token=…" />
        <template v-if="isDingTalk">
          <label class="lbl">加签密钥<span class="hint">（留空 = 保留现有；安全设置选「加签」时提供）</span></label>
          <SecretInput v-model="secretForm" class="secret-wrap--box" autocomplete="off" placeholder="SEC…" />
        </template>
      </div>
    </div>

    <footer class="ch-foot">
      <template v-if="editing">
        <button class="btn primary" :disabled="saving" @click="save"><Check :size="13" /> 保存</button>
        <button class="btn plain" :disabled="saving" @click="cancelEdit"><X :size="13" /> 取消</button>
      </template>
      <template v-else>
        <button class="btn plain" @click="openEdit"><Pencil :size="13" /> 编辑</button>
        <button class="btn plain" :disabled="testing || !channel.configured" @click="doTest">
          <Send :size="13" /> {{ testing ? '发送中…' : '测试发送' }}
        </button>
        <button class="btn link" :disabled="revealBusy || !channel.configured" @click="doReveal">
          <Eye :size="13" /> {{ revealed ? '收起明文' : '查看明文' }}
        </button>
        <button v-if="channel.configured" class="btn link danger" :disabled="saving" @click="clearWebhook">清空</button>
      </template>
    </footer>
  </article>
</template>

<style scoped lang="scss">
.ch-card {
  padding: 14px 16px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius);
  background: var(--surface-1);
  &.is-off { opacity: 0.72; }
}

.ch-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.ch-title { display: flex; align-items: center; gap: 8px; }
.ch-name { font-size: var(--text-base); font-weight: var(--weight-semibold); color: var(--text-1); }

.src-badge {
  font-size: 10px; padding: 1px 6px; border-radius: 10px; border: 1px solid var(--border-1); color: var(--text-3);
  &.src-db { color: var(--brand); border-color: var(--brand); }
  &.src-none { color: var(--text-3); }
}
.reentry { font-size: 11px; color: var(--warning); }

.switch {
  appearance: none; width: 36px; height: 20px; border-radius: 10px; background: var(--border-2);
  position: relative; cursor: pointer; transition: background 0.15s ease; flex-shrink: 0; margin: 0;
  &::after {
    content: ''; position: absolute; top: 2px; left: 2px; width: 16px; height: 16px; border-radius: 50%;
    background: white; transition: transform 0.15s ease; box-shadow: 0 1px 2px rgba(0, 0, 0, 0.12);
  }
  &:checked { background: var(--brand); &::after { transform: translateX(16px); } }
}

.ch-body { margin-top: 12px; display: flex; flex-direction: column; gap: 7px; }
.row { display: flex; gap: 10px; font-size: var(--text-sm); }
.row .k { flex-shrink: 0; width: 72px; color: var(--text-3); }
.row .v { color: var(--text-1); }
.mono { font-family: var(--font-mono, ui-monospace, monospace); font-size: var(--text-xs); }
.break { word-break: break-all; }

.reveal-box, .test-box, .edit-box {
  margin-top: 6px; padding: 10px 12px; border-radius: var(--radius-sm);
  background: var(--surface-2); border: 1px solid var(--border-1);
}
.reveal-hint { font-size: 11px; color: var(--text-3); }
.test-box { font-size: var(--text-xs); }
.test-box.ok { color: var(--success); border-color: rgb(from var(--success) r g b / 0.3); }
.test-box.bad { color: var(--danger); border-color: rgb(from var(--danger) r g b / 0.3); }

.edit-box { display: flex; flex-direction: column; gap: 6px; }
.lbl { font-size: var(--text-xs); color: var(--text-2); }
.lbl .hint { color: var(--text-3); }

.ch-foot { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.btn {
  display: inline-flex; align-items: center; gap: 5px; height: 30px; padding: 0 12px;
  border-radius: var(--radius-sm); font-size: var(--text-sm); cursor: pointer;
  border: 1px solid var(--border-1); background: var(--surface-1); color: var(--text-1);
  &:disabled { opacity: 0.5; cursor: not-allowed; }
  &.primary { background: var(--brand); color: var(--text-inverse); border-color: var(--brand); }
  &.link { border-color: transparent; background: transparent; color: var(--brand); }
  &.link.danger { color: var(--danger); }
  &.link:hover { text-decoration: underline; }
}
</style>
