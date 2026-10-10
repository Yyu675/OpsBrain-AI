<script setup lang="ts">
/**
 * 通知渠道配置面板（设置页「通知渠道」标签 · 批89 · 方案1「配置可视化」的 UI 面）。
 *
 * ── 为什么值得做成页面 ────────────────────────────────────────
 * 此前钉钉/企微 webhook 只能靠环境变量部署，改一次要重启，且「配没配对」
 * 全靠等一条真告警撞运气。本页把三件事收进一次点击：填地址、发测试消息
 * （走与真发同一套 NotifyPayloads 构造，探针绿=格式也对）、看明文核对。
 * DB 权威源热生效——保存即生效，灰度期零重启。
 *
 * ── 明文与留痕 ────────────────────────────────────────────────
 * 整屏永远打码；点「查看明文」才对单个渠道走 reveal 端点（限 ADMIN +
 * 后端写审计）。明文只在内存里显示，不落 localStorage（前端安全铁律）。
 *
 * ── 为什么用 TanStack Query ───────────────────────────────────
 * 保存/测试后 invalidate notifyKeys.list 声明式重拉，页面与后端单一真相，
 * 不用在每个按钮回调里各写一遍 load（6.17「漏刷症状」的根治）。
 */
import { computed } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { BellRing, ShieldAlert } from 'lucide-vue-next'

import { fetchNotifyChannels } from '@/api/notifyChannels'
import { notifyKeys } from '@/config/queryKeys'
import { useAppStore } from '@/stores/app'
import NotifyChannelCard from './NotifyChannelCard.vue'
import NotifyIngestCard from './NotifyIngestCard.vue'

defineOptions({ name: 'NotifyChannelsPanel' })

const app = useAppStore()
const isAdmin = computed(() => app.hasRole(['admin']))
const queryClient = useQueryClient()

const { data, isPending, isError, error, refetch } = useQuery({
  queryKey: notifyKeys.list(),
  queryFn: () => fetchNotifyChannels(),
  staleTime: 15_000,
})

/** 子卡片保存成功后调：失效整屏 → Query 自动重拉 */
const refresh = () => queryClient.invalidateQueries({ queryKey: notifyKeys.all })

const channels = computed(() => data.value?.channels ?? [])
const ingest = computed(() => data.value?.ingestSecret ?? null)
/** 主密钥未配 → webhook/密钥明文落库。缺省（旧快照未含此字段）按已配处理，不炸页面。 */
const cryptWarning = computed(() => data.value?.keyCryptConfigured === false)
</script>

<template>
  <div class="notify-panel">
    <header class="panel-header">
      <h2 class="panel-title"><BellRing :size="16" /> 通知渠道</h2>
      <p class="panel-sub">
        钉钉 / 企业微信群机器人 webhook 在线修改，保存即生效（DB 权威覆盖环境变量，零重启）。
        不确定配没配对就先「测试发送」——探针走与真实推送完全相同的消息构造，收到即配置有效。
      </p>
    </header>

    <div v-if="!isAdmin" class="plain-tip">仅管理员可查看与编辑通知渠道配置。</div>

    <template v-else>
      <div v-if="cryptWarning" class="crypt-banner">
        <ShieldAlert :size="15" />
        <span>
          未配置 <code>MODEL_KEY_CRYPT_SECRET</code>：所有 webhook / 密钥正以<b>明文</b>落库。
          功能可用，但生产环境应配置主密钥后到下方重新保存一次各渠道。
        </span>
      </div>

      <div v-if="isPending" class="plain-tip">加载中…</div>
      <div v-else-if="isError" class="plain-tip is-error">
        加载失败：{{ (error as Error)?.message || '未知错误' }}
        <button class="relink" @click="refetch()">重试</button>
      </div>

      <template v-else>
        <div class="card-list">
          <NotifyChannelCard
            v-for="ch in channels"
            :key="ch.channelKey"
            :channel="ch"
            @saved="refresh"
          />
        </div>
        <NotifyIngestCard v-if="ingest" :ingest="ingest" @saved="refresh" />
      </template>
    </template>
  </div>
</template>

<style scoped lang="scss">
.notify-panel {
  max-width: 780px;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  font-size: var(--text-lg);
  font-weight: var(--weight-semibold);
  color: var(--text-1);
}

.panel-sub {
  margin: 5px 0 0;
  max-width: 72ch;
  font-size: var(--text-xs);
  color: var(--text-3);
  line-height: 1.6;
}

.crypt-banner {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 13px;
  border-radius: var(--radius);
  font-size: var(--text-xs);
  line-height: 1.55;
  color: var(--warning);
  background: rgb(from var(--warning) r g b / 0.08);
  border: 1px solid rgb(from var(--warning) r g b / 0.28);

  code { font-family: var(--font-mono, ui-monospace, monospace); }
  b { font-weight: var(--weight-semibold); }
}

.card-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.plain-tip { font-size: var(--text-sm); color: var(--text-3); }
.plain-tip.is-error { color: var(--danger); }
.relink {
  border: none;
  background: none;
  cursor: pointer;
  color: var(--brand);
  font-size: var(--text-sm);
  padding: 0 0 0 4px;
  &:hover { text-decoration: underline; }
}
</style>
