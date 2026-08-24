<script setup lang="ts">
import { notify } from '@/utils/notify'
import { ref, watch, computed, onBeforeUnmount } from 'vue'
import { ElMessageBox } from 'element-plus'
import { X, Plus, Sparkles, Loader2 } from 'lucide-vue-next'
import {
  useTicketsStore,
  SERVICE_OPTIONS,
  CATEGORY_OPTIONS,
  UNASSIGNED,
  TAG_OPTIONS,
  PRIORITY_HINTS,
  type Ticket,
  type TicketPriority
} from '@/stores/tickets'
import { TICKET_PRIORITY_OPTIONS } from '@/constants/ticket'
import { useAppStore } from '@/stores/app'
import { loadDraft, saveDraft, clearDraft } from '@/utils/draftStorage'
import { useFocusTrap } from '@/utils/focusTrap'
import { isAbortLike } from '@/utils/errors'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { chatStream } from '@/api/chat'
import type { SSETokenEvent, SSEToolStatusEvent, SSEErrorEvent } from '@/api/types'

interface Props {
  visible: boolean
  ticket?: Ticket | null
}

const props = withDefaults(defineProps<Props>(), {
  ticket: null
})

const emit = defineEmits<{
  'update:visible': [value: boolean]
  submit: [ticket: Ticket]
}>()

const store = useTicketsStore()
const app = useAppStore()

const dialogRef = ref<HTMLElement | null>(null)
const trap = useFocusTrap(() => dialogRef.value)

const isEdit = computed(() => !!props.ticket)

// Attachment 接口已删除：附件不再由表单管理（见下方注释）

const form = ref({
  title: '',
  description: '',
  priority: 'medium' as TicketPriority,
  service: SERVICE_OPTIONS[0],
  category: CATEGORY_OPTIONS[0],
  assignee: UNASSIGNED,
  sla: '4h 响应 / 8h 解决',
  tagInput: '',
  tags: [] as string[]
})

// A2：负责人名单来自后端 sys_team_member，不再硬编码
const assigneeOptions = computed(() => store.assignees)

/**
 * 负责人当前负载提示（如「张明（3 单在处理）」）
 * 供指派时参考，避免把新工单压给已经满负荷的人。
 * 「待分配」不是人，无负载可言。
 */
const workloadOf = (name: string): string => {
  if (name === UNASSIGNED) return ''
  const m = store.teamMembers.find(x => x.name === name)
  if (!m || !m.activeTicketCount) return ''
  return `（${m.activeTicketCount} 单在处理）`
}

const priorityOptions = TICKET_PRIORITY_OPTIONS.map(opt => ({
  ...opt,
  hint: PRIORITY_HINTS[opt.value]
}))

const resetForm = () => {
  if (props.ticket) {
    form.value = {
      title: props.ticket.title,
      description: props.ticket.description,
      priority: props.ticket.priority,
      service: props.ticket.service,
      category: props.ticket.category,
      assignee: props.ticket.assignee,
      sla: props.ticket.sla,
      tagInput: '',
      tags: [...props.ticket.tags]
    }
  } else {
    form.value = {
      title: '',
      description: '',
      priority: 'medium',
      service: SERVICE_OPTIONS[0],
      category: CATEGORY_OPTIONS[0],
      assignee: UNASSIGNED,
      sla: priorityOptions.find(p => p.value === 'medium')?.hint || '',
      tagInput: '',
      tags: []
    }
  }
}

const initialSnapshot = ref('')
const isDirty = computed(() => props.visible && JSON.stringify(form.value) !== initialSnapshot.value)
const submitting = ref(false)
const aiLoading = ref(false)
let aiAbort: AbortController | null = null

useDirtyGuard(isDirty, {
  message: '工单尚未提交（草稿已本地保存），确认离开当前页面吗？',
  onDiscard: () => {
    emit('update:visible', false)
  }
})

const draftKey = computed(() => `ticket-form:${props.ticket ? props.ticket.id : 'new'}`)

const applyDraft = async () => {
  const draft = loadDraft<typeof form.value>(draftKey.value)
  if (!draft) return
  try {
    await ElMessageBox.confirm('检测到未保存的工单草稿，是否恢复？', '草稿恢复', {
      type: 'info',
      confirmButtonText: '恢复草稿',
      cancelButtonText: '忽略'
    })
    form.value = { ...draft, tagInput: '' }
    notify.success('已恢复草稿')
  } catch {
    clearDraft(draftKey.value)
  }
}

watch(() => props.visible, v => {
  if (v) {
    resetForm()
    initialSnapshot.value = JSON.stringify(form.value)
    submitting.value = false
    document.addEventListener('keydown', onKeydown)
    void applyDraft()
    // 负责人名单按需加载（store 内已做「加载过则跳过」，重复打开不会重复请求）
    void store.loadTeamMembers()
    trap.activate()
  } else {
    document.removeEventListener('keydown', onKeydown)
    trap.deactivate()
    // 弹窗关闭时中断进行中的 AI 分析，避免关闭后仍在消费流并弹提示
    aiAbort?.abort()
  }
})

watch(
  () => form.value,
  (val) => {
    if (!props.visible) return
    if (!isDirty.value) return
    saveDraft(draftKey.value, val)
  },
  { deep: true }
)

watch(() => form.value.priority, p => {
  const hint = priorityOptions.find(x => x.value === p)?.hint
  if (hint) form.value.sla = hint
})

const onKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Escape') {
    e.stopPropagation()
    close()
  }
}

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  aiAbort?.abort()
})

const close = () => {
  if (isDirty.value) {
    ElMessageBox.confirm('有未保存的修改（已存草稿，下次可恢复），确认关闭吗？', '关闭确认', {
      type: 'warning',
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑',
      distinguishCancelAndClose: true
    })
      .then(() => {
        clearDraft(draftKey.value)
        emit('update:visible', false)
      })
      .catch((action) => {
        if (action === 'close') {
          emit('update:visible', false)
        }
      })
    return
  }
  emit('update:visible', false)
}

const addTag = () => {
  const t = form.value.tagInput.trim()
  if (t && !form.value.tags.includes(t)) form.value.tags.push(t)
  form.value.tagInput = ''
}
const removeTag = (t: string) => (form.value.tags = form.value.tags.filter(x => x !== t))
const addPresetTag = (t: string) => {
  if (!form.value.tags.includes(t)) form.value.tags.push(t)
}

// 抄送人（cc）已从表单移除。
// 后端与数据库中完全不存在此概念，填了也只留在本地 state 里。
// 更关键的是：抄送的意义在于**触达通知**，而通知渠道（钉钉机器人）
// 属 L2 规划（见 CLAUDE.md 6.3）。在没有通知能力的前提下，
// 存一串名字不触发任何行为，UI 却暗示会通知，属误导。
// 待 L2 通知落地后一并实现：届时需 sys_ticket_watcher 表 +
// 状态变更时向抄送人推送。

// 附件选择器已从表单移除。
// 原实现只把文件名记入本地 form.attachments，提交时后端并不处理——
// 用户以为附件已随工单提交，实际什么都没上传，是又一处假交互。
// 附件现由工单详情页管理：真实上传到 MinIO，含类型白名单与大小校验。
// 表单不做附件是因为创建时工单号尚未生成，无法确定归属对象。

// ==================== AI 自动分类（真实 AI） ====================
//
// 此前是纯前端关键词匹配（combined.includes('mysql') 之类）伪造推荐，
// 命中率低且与后端 AI 能力无关，属假智能。现改为调用真实 AI 流式接口：
// 模型读取标题+描述，返回结构化 JSON（分类/服务/优先级/标签），前端解析后填充。
//
// 关键约束：对话端点是带 createDevOpsTicket 写工具的 ReAct Agent，
// 分类请求必须显式禁止建单/调用工具，并防御性监听 tool_status。

const AI_CLASSIFY_PROMPT = `你是运维工单分类助手。请仅分析下面的工单标题与描述，给出分类建议。
严禁创建工单、严禁调用任何工具、严禁检索知识库，只需直接返回一段 JSON，不要额外解释。

可选分类(category，必须原样从中选一个)：数据库、服务器、网络、中间件、容器/K8s、存储、应用异常、性能、安全、其他
可选服务(service，必须原样从中选一个)：生产集群-K8s、生产环境-MySQL、生产环境-Nginx、网络、未分类
可选优先级(priority，只能填英文枚举)：urgent（生产宕机/严重故障）、high（影响业务但有临时方案）、medium（需处理但不紧急）、low（优化建议）

严格按以下 JSON 格式输出（用 \`\`\`json 代码块包裹）：
\`\`\`json
{"category":"数据库","service":"生产环境-MySQL","priority":"high","tags":["MySQL","主从延迟"]}
\`\`\`
其中 tags 为 2-5 个精炼标签（中文或技术名词均可）。`

const VALID_PRIORITIES: TicketPriority[] = ['urgent', 'high', 'medium', 'low']

/** 从流式文本中提取 JSON 对象（优先 ```json 代码块，兜底裸 {...}） */
const extractSuggestionJson = (raw: string): Record<string, unknown> | null => {
  if (!raw || !raw.trim()) return null
  const fence = raw.match(/```(?:json)?\s*\n?([\s\S]*?)```/i)
  const candidate = fence ? fence[1] : raw
  const start = candidate.indexOf('{')
  const end = candidate.lastIndexOf('}')
  if (start === -1 || end === -1 || end < start) return null
  try {
    return JSON.parse(candidate.slice(start, end + 1))
  } catch {
    return null
  }
}

/** 校验并应用建议，仅采纳合法值，返回是否产生了改动 */
const applySuggestion = (obj: Record<string, unknown>): boolean => {
  let applied = false
  const category = typeof obj.category === 'string' ? obj.category.trim() : ''
  if (category && CATEGORY_OPTIONS.includes(category)) {
    form.value.category = category
    applied = true
  }
  const service = typeof obj.service === 'string' ? obj.service.trim() : ''
  if (service && SERVICE_OPTIONS.includes(service)) {
    form.value.service = service
    applied = true
  }
  const priority = typeof obj.priority === 'string' ? obj.priority.trim() : ''
  if (priority && VALID_PRIORITIES.includes(priority as TicketPriority)) {
    form.value.priority = priority as TicketPriority
    applied = true
  }
  if (Array.isArray(obj.tags)) {
    for (const t of obj.tags) {
      const tag = String(t).trim()
      if (tag && tag.length <= 20 && form.value.tags.length < 20 && !form.value.tags.includes(tag)) {
        form.value.tags.push(tag)
        applied = true
      }
    }
  }
  return applied
}

const aiSuggest = async () => {
  if (aiLoading.value) return
  const title = form.value.title.trim()
  const desc = form.value.description.trim()
  if (!title && !desc) {
    notify.warning('请先填写标题或问题描述，AI 才能分析')
    return
  }

  // chatStream 限制 1500 字，截断超长描述避免请求被拒
  const safeDesc = desc.slice(0, 900)
  const query = `${AI_CLASSIFY_PROMPT}\n\n工单标题：${title || '（未填写）'}\n工单描述：${safeDesc || '（未填写）'}`

  aiLoading.value = true
  aiAbort = new AbortController()
  let raw = ''
  let streamError: string | null = null

  try {
    await chatStream(query, {
      onToken: (data: SSETokenEvent) => { raw += data.text },
      onToolStatus: (data: SSEToolStatusEvent) => {
        // 防御：分类请求不应触发建单工具，若意外触发仅告警不影响表单
        if (data.toolName === 'createDevOpsTicket') {
          console.warn('[TicketFormDialog] 分类请求意外触发了建单工具，已忽略')
        }
      },
      onComplete: () => {},
      onError: (data: SSEErrorEvent) => { streamError = data.message || '分析失败' }
    }, aiAbort)

    if (streamError && !raw.trim()) {
      notify.error(`AI 分析失败：${streamError}`)
      return
    }
    const parsed = extractSuggestionJson(raw)
    if (!parsed) {
      notify.warning('AI 未能给出可用的分类建议，请手动选择')
      return
    }
    if (applySuggestion(parsed)) {
      notify.success('AI 已根据描述推荐分类、优先级和标签，请确认后提交')
    } else {
      notify.info('AI 分析完成，但未产生可采纳的建议')
    }
  } catch (error: unknown) {
    if (isAbortLike(error) || aiAbort?.signal.aborted) {
      // 用户关闭弹窗或主动中断，静默处理
    } else {
      console.error('[TicketFormDialog] AI 分类失败', error)
      notify.error('AI 分析失败，请稍后重试或手动选择分类')
    }
  } finally {
    aiLoading.value = false
    aiAbort = null
  }
}

const validate = (): string | null => {
  if (!form.value.title.trim()) return '请填写工单标题'
  if (form.value.title.trim().length < 5) return '标题至少 5 个字符'
  if (!form.value.description.trim()) return '请填写问题描述'
  if (form.value.description.trim().length < 10) return '描述至少 10 个字符，便于处理'
  if (!form.value.service) return '请选择关联服务'
  if (!form.value.category) return '请选择工单分类'
  return null
}

// now() 已移除：原用于本地伪造回复与活动流的时间戳。
// 现在这些数据由后端生成，时间戳以服务端时钟为准，
// 避免客户端时钟偏差导致时间线错序。

const submit = async () => {
  if (submitting.value) return
  const err = validate()
  if (err) {
    notify.warning(err)
    return
  }

  submitting.value = true
  try {
    // 时间戳原用于本地伪造回复与活动流，现由后端生成，已不需要

    if (isEdit.value && props.ticket) {
      // 更新走后端持久化（store 内部乐观更新 + 失败回滚）
      await store.updateTicket(props.ticket.id, {
        title: form.value.title.trim(),
        description: form.value.description.trim(),
        priority: form.value.priority,
        service: form.value.service,
        category: form.value.category,
        assignee: form.value.assignee,
        sla: form.value.sla,
        tags: [...form.value.tags],
        // attachments 不再由表单提交：后端 updateTicket 不处理该字段，
        // 附件由详情页的专用接口管理
      })
      const updated = store.getById(props.ticket.id)!
      notify.success(`工单 ${updated.id} 已更新`)
      emit('submit', updated)
    } else {
      // 创建走后端，工单号由后端生成（Redis INCR 保证并发安全）
      // 标签随创建请求一并落库，此前用户输入在此被丢弃
      const created = await store.createTicket({
        title: form.value.title.trim(),
        description: form.value.description.trim(),
        priority: form.value.priority,
        service: form.value.service,
        category: form.value.category,
        assignee: form.value.assignee,
        sla: form.value.sla,
        creator: app.currentUser.name || '当前用户',
        tags: [...form.value.tags]
      })

      // 加载后端生成的活动流（工单创建 + 负责人分配）。
      // 此前此处本地伪造回复与活动流，会与后端真实记录重复。
      await store.loadTicketDetail(created.id)
      const local = store.getById(created.id)

      // 比对提交的标签与实际存入的标签。
      // 标签写入失败时后端不回滚工单（工单本体有效），但会返回实际结果，
      // 前端须如实告知用户，否则用户以为标签存上了。
      const submittedTags = form.value.tags.length
      const savedTags = (local?.tags ?? created.tags ?? []).length
      if (submittedTags > 0 && savedTags === 0) {
        notify.warning(
          `工单 ${created.id} 已创建，但 ${submittedTags} 个标签保存失败，请在详情页重新添加`,
          { duration: 6000, showClose: true }
        )
      } else if (savedTags < submittedTags) {
        // 后端会归一化（去空/去重/截断/限量 20），少于提交数属正常
        notify.success(`工单 ${created.id} 已创建（标签去重后保留 ${savedTags} 个）`)
      } else {
        notify.success(`工单 ${created.id} 已创建`)
      }
      emit('submit', local ?? created)
    }

    clearDraft(draftKey.value)
    initialSnapshot.value = JSON.stringify(form.value)
    emit('update:visible', false)
  } catch (e) {
    // store 已弹错误提示，此处保留弹窗与草稿，便于用户修正后重试
    console.error('[TicketFormDialog] 提交失败', e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <transition name="dialog-fade">
      <div v-if="visible" class="dialog-mask" @click.self="close">
        <div
          ref="dialogRef"
          class="dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="ticket-form-title"
        >
          <header class="dialog-header">
            <h3 id="ticket-form-title">{{ isEdit ? '编辑工单' : '创建工单' }}</h3>
            <button class="btn-icon" @click="close">
              <X :size="18" />
            </button>
          </header>

          <div class="dialog-body">
            <!--
              表单分组：按「填写心智」而非字段类型划分。
              一屏十几个字段平铺时，用户无法判断哪些必填、哪些可以先跳过；
              分组后每组 2-4 项，配合小标题形成节奏，扫读成本显著下降。
            -->
            <section class="form-section">
              <h4 class="form-section-title">
                基本信息
                <span class="form-section-hint">描述这是什么问题</span>
              </h4>

            <!-- 标题 -->
            <div class="form-row">
              <label class="form-label required">工单标题</label>
              <input
                v-model="form.title"
                type="text"
                class="form-input"
                placeholder="请简要描述问题，例如：生产环境 Redis 主从复制延迟"
                maxlength="120"
              />
            </div>

            <!-- 问题描述 + AI 建议 -->
            <div class="form-row">
              <div class="form-label-row">
                <label class="form-label required">问题描述</label>
                <button type="button" class="ai-btn" :class="{ 'is-loading': aiLoading }" :disabled="aiLoading" @click="aiSuggest">
                  <Loader2 v-if="aiLoading" :size="14" class="ai-spin" />
                  <Sparkles v-else :size="14" />
                  {{ aiLoading ? 'AI 分析中…' : 'AI 自动分类' }}
                </button>
              </div>
              <textarea
                v-model="form.description"
                class="form-textarea"
                rows="4"
                placeholder="请详细描述：现象、影响范围、发生时间、已排查步骤等"
                maxlength="1000"
              ></textarea>
              <div class="char-hint">{{ form.description.length }} / 1000</div>
            </div>

            <!-- 优先级 -->
            <div class="form-row">
              <label class="form-label required">优先级</label>
              <div class="priority-list">
                <button
                  v-for="p in priorityOptions"
                  :key="p.value"
                  type="button"
                  class="priority-btn"
                  :class="[`priority-${p.value}`, { active: form.priority === p.value }]"
                  @click="form.priority = p.value"
                >
                  <span class="priority-label">{{ p.label }}</span>
                  <span class="priority-hint">{{ p.hint }}</span>
                </button>
              </div>
            </div>

            <!-- 分类 / 服务 -->
            </section>

            <section class="form-section">
              <h4 class="form-section-title">
                归类与定级
                <span class="form-section-hint">决定路由与 SLA 时限</span>
              </h4>

            <div class="form-row form-row-2">
              <div>
                <label class="form-label required">工单分类</label>
                <select v-model="form.category" class="form-input">
                  <option v-for="c in CATEGORY_OPTIONS" :key="c" :value="c">{{ c }}</option>
                </select>
              </div>
              <div>
                <label class="form-label required">关联服务</label>
                <select v-model="form.service" class="form-input">
                  <option v-for="s in SERVICE_OPTIONS" :key="s" :value="s">{{ s }}</option>
                </select>
              </div>
            </div>

            <!-- 负责人 / SLA -->
            </section>

            <section class="form-section">
              <h4 class="form-section-title">
                处理安排
                <span class="form-section-hint">可留空，创建后再指派</span>
              </h4>

            <div class="form-row form-row-2">
              <div>
                <label class="form-label">负责人</label>
                <select v-model="form.assignee" class="form-input">
                  <!-- 名单来自后端 sys_team_member（A2），不再硬编码编造姓名。
                       编辑历史工单时，若当前负责人已不在名录，下方 v-if 会补出该选项，
                       否则下拉框选不中会显示为空，用户误以为工单未指派 -->
                  <option
                    v-if="form.assignee && !assigneeOptions.includes(form.assignee)"
                    :value="form.assignee"
                  >
                    {{ form.assignee }}（不在名录）
                  </option>
                  <option v-for="a in assigneeOptions" :key="a" :value="a">
                    {{ a }}{{ workloadOf(a) }}
                  </option>
                </select>
              </div>
              <div>
                <label class="form-label">SLA 约束</label>
                <input
                  v-model="form.sla"
                  type="text"
                  class="form-input"
                  placeholder="例：4h 响应 / 8h 解决"
                />
              </div>
            </div>

            <!-- 标签 -->
            <div class="form-row">
              <label class="form-label">标签</label>
              <div class="tag-input-row">
                <input
                  v-model="form.tagInput"
                  type="text"
                  class="form-input"
                  placeholder="输入标签后按回车"
                  @keydown.enter.prevent="addTag"
                />
                <button type="button" class="btn-add" @click="addTag">
                  <Plus :size="14" />
                  添加
                </button>
              </div>
              <div v-if="form.tags.length" class="tag-list">
                <span v-for="t in form.tags" :key="t" class="tag-chip">
                  {{ t }}
                  <button type="button" class="tag-close" @click="removeTag(t)">
                    <X :size="12" />
                  </button>
                </span>
              </div>
              <div class="preset-list">
                <span class="preset-label">常用：</span>
                <span
                  v-for="t in TAG_OPTIONS"
                  :key="t"
                  class="preset-tag"
                  :class="{ disabled: form.tags.includes(t) }"
                  @click="addPresetTag(t)"
                >{{ t }}</span>
              </div>
            </div>

<!--
              抄送（CC）已移除：后端无此概念，且抄送的意义在于触达通知，
              而通知渠道属 L2 规划。详见 script 中的说明注释。
            -->

            <!--
              附件：改在工单详情页上传。
              创建时工单号尚未生成，无法确定附件归属对象；
              原实现只记文件名不真正上传，属假交互，已移除。
            -->
            <div class="form-row">
              <label class="form-label">附件</label>
              <div class="form-hint">工单创建后，可在详情页上传附件</div>
            </div>
            </section>
          </div>

          <footer class="dialog-footer">
            <button class="btn btn-plain" @click="close" :disabled="submitting">取消</button>
            <button class="btn btn-primary" @click="submit" :disabled="submitting">
              {{ submitting ? '提交中…' : (isEdit ? '保存修改' : '提交工单') }}
            </button>
          </footer>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped lang="scss">
.dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.5);
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.dialog {
  width: 100%;
  max-width: 780px;
  max-height: 90vh;
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid var(--color-border-light);

  h3 {
    font-size: var(--text-lg);
    font-weight: var(--weight-semibold);
    color: var(--color-text-primary);
    margin: 0;
  }
}

.btn-icon {
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  color: var(--color-text-tertiary);
  border-radius: var(--radius-sm);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;

  &:hover {
    background: var(--color-surface-hover);
    color: var(--color-text-primary);
  }
}

.dialog-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

/* ── 表单分组（G3）─────────────────────────────────────────
   用「小标题 + 细分隔线」而非卡片：卡片会在弹窗里套出第二层容器，
   视觉层级过重，反而让弹窗显得拥挤。 */
.form-section {
  padding-bottom: var(--space-4, 16px);
  margin-bottom: var(--space-4, 16px);
  border-bottom: 1px solid var(--border-1);
}
.form-section:last-child {
  padding-bottom: 0;
  margin-bottom: 0;
  border-bottom: none;
}
.form-section-title {
  display: flex;
  align-items: baseline;
  gap: var(--space-2, 8px);
  margin: 0 0 var(--space-3, 12px);
  font-size: var(--text-sm, 0.875rem);
  font-weight: 600;
  color: var(--text-1);
}
.form-section-hint {
  font-size: var(--text-xs, 0.75rem);
  font-weight: 400;
  color: var(--text-3);
}

.form-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-row-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;

  @media (max-width: 640px) {
    grid-template-columns: 1fr;
  }
}

.form-label {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-primary);

  &.required::after {
    content: ' *';
    color: var(--state-error);
  }
}

.form-label-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.ai-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-md);
  background: var(--color-primary-lighter);
  color: var(--color-primary);
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    background: var(--color-primary);
    color: white;
  }

  &.is-loading {
    cursor: progress;
    opacity: 0.85;
  }

  &:disabled {
    cursor: progress;
  }
}

.ai-spin {
  animation: ai-spin 0.8s linear infinite;
}

@keyframes ai-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.form-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  background: var(--color-surface);
  color: var(--color-text-primary);
  outline: none;
  transition: border-color 0.15s ease;
  box-sizing: border-box;

  &:focus {
    border-color: var(--color-primary);
  }
}

.form-textarea {
  @extend .form-input;
  resize: vertical;
  min-height: 80px;
}

.char-hint {
  align-self: flex-end;
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

.priority-list {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;

  @media (max-width: 640px) {
    grid-template-columns: repeat(2, 1fr);
  }
}

.priority-btn {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 2px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  cursor: pointer;
  text-align: left;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary-light);
  }

  &.active {
    background: var(--color-primary-lighter);
    border-color: var(--color-primary);
  }

  .priority-label {
    font-size: var(--text-sm);
    font-weight: var(--weight-semibold);
    color: var(--color-text-primary);
  }

  .priority-hint {
    font-size: 11px;
    color: var(--color-text-tertiary);
  }

  &.priority-urgent.active .priority-label { color: var(--state-error); }
  &.priority-high.active .priority-label { color: #EA580C; }
  &.priority-medium.active .priority-label { color: var(--color-primary); }
  &.priority-low.active .priority-label { color: var(--color-text-secondary); }
}

.tag-input-row {
  display: flex;
  gap: 8px;
}

.btn-add {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 8px 14px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  cursor: pointer;
  white-space: nowrap;

  &:hover {
    border-color: var(--color-primary);
    color: var(--color-primary);
  }
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}

.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px 4px 10px;
  background: var(--color-primary-lighter);
  color: var(--color-primary);
  font-size: var(--text-xs);
  border-radius: var(--radius-full);

  &.cc-chip {
    background: var(--state-info-bg);
    color: var(--state-info);
  }
}

.tag-close {
  border: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
  padding: 2px;
  display: inline-flex;

  &:hover {
    color: var(--state-error);
  }
}

.preset-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 4px;
}

.preset-label {
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}

.preset-tag {
  padding: 3px 8px;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
  background: var(--color-bg-sunken);
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover:not(.disabled) {
    background: var(--color-primary-lighter);
    color: var(--color-primary);
  }

  &.disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
}

.attach-drop {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 12px 16px;
  border: 1px dashed var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-bg-sunken);
  color: var(--color-text-secondary);
  font-size: var(--text-sm);
  font-family: var(--font-body);
  cursor: pointer;
  align-self: flex-start;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--color-primary);
    color: var(--color-primary);
    background: var(--color-primary-lighter);
  }
}

.attach-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 6px;
}

.attach-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--color-bg-sunken);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
}

.attach-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text-primary);
}

.attach-size {
  color: var(--color-text-tertiary);
}

.attach-remove {
  border: none;
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
  padding: 2px;
  display: inline-flex;

  &:hover {
    color: var(--state-error);
  }
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 16px 24px;
  border-top: 1px solid var(--color-border-light);
  background: var(--color-bg-sunken);
}

.btn {
  padding: 8px 20px;
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  font-family: var(--font-body);
  cursor: pointer;
  transition: all 0.15s ease;
  border: 1px solid transparent;

  &:disabled,
  &[disabled] {
    opacity: 0.6;
    cursor: not-allowed;
    pointer-events: none;
  }

  &.btn-plain {
    background: var(--color-surface);
    color: var(--color-text-primary);
    border-color: var(--color-border-light);

    &:hover {
      border-color: var(--color-primary);
      color: var(--color-primary);
    }
  }

  &.btn-primary {
    background: var(--color-primary);
    color: var(--color-text-inverse);
    border-color: var(--color-primary);

    &:hover {
      background: var(--color-primary-light);
      border-color: var(--color-primary-light);
    }
  }
}

.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity 0.15s ease;

  .dialog {
    transition: transform 0.15s ease;
  }
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;

  .dialog {
    transform: translateY(-16px);
  }
}
</style>
