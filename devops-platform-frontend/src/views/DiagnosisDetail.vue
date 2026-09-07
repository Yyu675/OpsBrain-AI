<template>
  <div class="diagnosis-detail">
    <header class="diag-header">
      <h2>诊断回放</h2>
      <code class="trace-id">{{ traceId }}</code>
      <span v-if="session.sufficiency" class="badge" :class="suffClass(session.sufficiency)">
        {{ session.sufficiency }}
      </span>
    </header>

    <p v-if="session.summary" class="summary">{{ session.summary }}</p>

    <section class="evidence-section">
      <h3>证据（{{ evidences.length }}）</h3>
      <ul class="evidence-list">
        <li v-for="ev in evidences" :key="ev.id" class="evidence-card" :class="'st-' + ev.status">
          <div class="ev-head">
            <span class="ev-type">{{ ev.evidence_type }}</span>
            <span class="ev-status">{{ statusLabel(ev.status) }}</span>
          </div>
          <div class="ev-title">{{ ev.title }}</div>
          <code v-if="ev.source_ref" class="ev-ref">{{ ev.source_ref }}</code>
        </li>
      </ul>
      <p v-if="!evidences.length" class="empty-hint">无证据记录——孤证或已清理</p>
    </section>

    <section class="hypo-section">
      <h3>根因假设（{{ hypotheses.length }}）</h3>
      <ul class="hypo-list">
        <li v-for="h in hypotheses" :key="h.id" class="hypo-card">
          <div class="hypo-head">
            <span class="rank">#{{ h.rank }}</span>
            <span class="statement">{{ h.statement }}</span>
          </div>
          <div class="conf-bar">
            <div class="conf-fill" :style="{ width: Math.round(h.confidence * 100) + '%' }"></div>
            <span class="conf-text">{{ Math.round(h.confidence * 100) }}%</span>
          </div>
          <p v-if="h.reasoning" class="reasoning">{{ h.reasoning }}</p>
          <p v-if="h.suggested_action" class="action">建议：{{ h.suggested_action }}</p>
          <p v-if="h.evidence_ids" class="evlink">支撑证据：{{ h.evidence_ids }}</p>
          <div class="feedback-row">
            <button
              v-for="f in feedbackOptions" :key="f.key"
              class="fb-btn" :class="{ active: h.feedback === f.key }"
              :disabled="fbBusy[h.id]"
              @click="mark(h, f.key)">
              {{ f.label }}
            </button>
          </div>
        </li>
      </ul>
      <p v-if="!hypotheses.length" class="empty-hint">未见假设——规则基线判为「无物可说」或该会话已被终判</p>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  fetchDiagnosisReplay,
  postHypothesisFeedback,
  type DiagnosisEvidenceView,
  type DiagnosisHypothesisView,
  type DiagnosisSessionView,
  type HypothesisFeedback
} from '../api/diagnosis'

const route = useRoute()
const traceId = String(route.params.traceId ?? '')

const session = ref<DiagnosisSessionView>({})
const evidences = ref<DiagnosisEvidenceView[]>([])
const hypotheses = ref<DiagnosisHypothesisView[]>([])
const fbBusy = ref<Record<number, boolean>>({})

const feedbackOptions: { key: HypothesisFeedback; label: string }[] = [
  { key: 'HELPFUL', label: '有帮助' },
  { key: 'PARTIAL', label: '部分正确' },
  { key: 'WRONG', label: '错误' }
]

function statusLabel(status: string): string {
  return ({ 'SUCCESS': '✅ 成功', 'NO_DATA': '⭕ 无数据', 'FAILED': '❌ 失败', 'UNAVAILABLE': '🚫 未启用' } as const)[status as never] || status
}

function suffClass(suff: string): string {
  return suff === 'SUFFICIENT' ? 'suff-ok' : suff === 'WEAK' ? 'suff-weak' : 'suff-bad'
}

async function mark(h: DiagnosisHypothesisView, feedback: HypothesisFeedback) {
  fbBusy.value[h.id] = true
  try {
    await postHypothesisFeedback(h.id, feedback)
    h.feedback = feedback
  } finally {
    fbBusy.value[h.id] = false
  }
}

onMounted(async () => {
  const replay = await fetchDiagnosisReplay(traceId)
  session.value = replay.session
  evidences.value = replay.evidences
  hypotheses.value = replay.hypotheses
})
</script>

<style scoped>
.diagnosis-detail { padding: 20px; max-width: 1080px; margin: 0 auto; }
.diag-header { display: flex; align-items: center; gap: 12px; }
.trace-id { background: #f2f4f7; padding: 2px 10px; border-radius: 4px; font-size: 12px; }
.badge { padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600; }
.suff-ok { background: #e6f6e6; color: #237804; }
.suff-weak { background: #fff7e0; color: #ad6800; }
.suff-bad { background: #ffe1e1; color: #a8071a; }
.summary { background: #fafafa; padding: 12px; border-left: 3px solid #3b82f6; margin: 12px 0; }
.evidence-list, .hypo-list { list-style: none; padding: 0; display: grid; gap: 10px; }
.evidence-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 12px; }
.ev-head { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 6px; }
.ev-type { font-weight: 600; text-transform: uppercase; color: #475569; }
.st-SUCCESS .ev-status { color: #237804; }
.st-FAILED .ev-status { color: #a8071a; }
.st-NO_DATA .ev-status { color: #64748b; }
.st-UNAVAILABLE .ev-status { color: #94a3b8; }
.ev-title { font-size: 13px; }
.ev-ref { font-size: 11px; color: #64748b; display: block; margin-top: 6px; white-space: pre-wrap; }
.hypo-card { border: 1px solid #e5e7eb; border-radius: 10px; padding: 14px; }
.hypo-head { display: flex; gap: 10px; align-items: baseline; }
.rank { font-weight: 700; color: #3b82f6; }
.statement { font-weight: 600; }
.conf-bar { position: relative; height: 18px; background: #eef2f6; border-radius: 9px; margin: 10px 0; overflow: hidden; }
.conf-fill { height: 100%; background: linear-gradient(90deg, #60a5fa, #3b82f6); }
.conf-text { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font-size: 11px; font-weight: 700; }
.reasoning, .action { font-size: 13px; color: #475569; margin: 6px 0; }
.evlink { font-family: monospace; font-size: 11px; color: #94a3b8; }
.feedback-row { display: flex; gap: 8px; margin-top: 10px; }
.fb-btn { border: 1px solid #dbe2ea; background: #fff; border-radius: 6px; padding: 4px 12px; font-size: 12px; cursor: pointer; }
.fb-btn.active { border-color: #3b82f6; color: #1d4ed8; background: #eff6ff; }
.fb-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.empty-hint { color: #94a3b8; font-size: 13px; }
</style>
