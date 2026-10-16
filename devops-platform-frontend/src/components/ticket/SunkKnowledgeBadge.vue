<script setup lang="ts">
/**
 * SunkKnowledgeBadge.vue — 工单「已沉淀为知识」徽标（#7 飞轮可见性）。
 *
 * 反查本工单已沉淀的知识文档（来源回链），并在每篇旁展示该文档收到的
 * 反馈计数（有帮助/没用，来自 sys_knowledge_boost 按文档聚合）。
 * 「沉淀了多少」与「沉淀的知识有没有用」这两件事在此收口——
 * 前者证明闭环走通了，后者证明飞轮在转。
 *
 * 设计约束：
 * - 纯展示：加载失败静默（徽标是次要信息，不该在每次打开详情页时弹错误）；
 * - 无回链文档时渲染空（不影响布局——绝大多数工单尚未沉淀过）；
 * - 计数与列表分两个后端调用，各自失败降级为空数组，不互相拖垮。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { findDocsBySourceTicket, fetchFeedbackStatsBySourceTicket } from '@/api/knowledge'

const props = defineProps<{ ticketId: string }>()

const emit = defineEmits<{ 'goto-doc': [docId: number] }>()

interface SunkDoc {
  docId: number
  title: string
  helpful: number
  wrong: number
}

const rows = ref<SunkDoc[]>([])

async function load() {
  const tid = props.ticketId
  if (!tid) {
    rows.value = []
    return
  }
  try {
    const [docs, stats] = await Promise.all([
      findDocsBySourceTicket(tid).catch(() => [] as Awaited<ReturnType<typeof findDocsBySourceTicket>>),
      fetchFeedbackStatsBySourceTicket(tid).catch(() => [] as Awaited<ReturnType<typeof fetchFeedbackStatsBySourceTicket>>),
    ])
    const statMap = new Map(stats.map(s => [s.docId, s]))
    rows.value = docs.map(d => {
      const s = statMap.get(d.id)
      return {
        docId: d.id,
        title: d.title,
        helpful: s?.helpfulCount ?? 0,
        wrong: s?.wrongCount ?? 0,
      }
    })
  } catch {
    // 徽标加载失败不打扰用户——详情页的主角是工单本身
    rows.value = []
  }
}

onMounted(load)
watch(() => props.ticketId, load)

const visible = computed(() => rows.value.length > 0)
</script>

<template>
  <div v-if="visible" class="sunk-badge">
    <span class="badge-label">已沉淀为知识 · {{ rows.length }} 篇</span>
    <button
      v-for="row in rows"
      :key="row.docId"
      type="button"
      class="badge-doc"
      @click="emit('goto-doc', row.docId)"
    >
      <span class="doc-title">{{ row.title }}</span>
      <span class="doc-count" :title="`有帮助 ${row.helpful} · 没用 ${row.wrong}`">
        👍 {{ row.helpful }} · 👎 {{ row.wrong }}
      </span>
    </button>
  </div>
</template>

<style scoped lang="scss">
.sunk-badge {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 12px;
  padding: 6px 12px;
  border-radius: var(--radius-sm, 6px);
  background: var(--brand-subtle, #eef4ff);
  font-size: var(--text-xs, 12px);
  color: var(--text-2, #667085);

  .badge-label { font-weight: var(--weight-medium, 500); }
  .badge-doc {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 1px 0;
    border: none;
    background: none;
    cursor: pointer;
    color: var(--brand, #3a6ff0);
    &:hover { text-decoration: underline; }
  }
  .doc-title { color: inherit; }
  .doc-count { color: var(--text-3, #98a2b3); white-space: nowrap; }
}
</style>