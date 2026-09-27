<script setup lang="ts">
/**
 * 工单详情的三个表单弹窗：记录处置动作 / 修复验证 / 确认根因。
 *
 * 从 TicketDetail 拆出（AGENTS.md 约定超标文件改动时只减不增）。
 * 表单状态仍由父组件持有——提交、校验、写后端都在那里，与工单 store
 * 强耦合；组件内再复制一份会与之漂移。这里只负责画弹窗并把表单双向绑定回去。
 */

export interface ActionFormState {
  actionType: string
  summary: string
  detail: string
  effective: boolean | null
}

export interface VerifyFormState {
  method: string
  conclusion: string
  skip: boolean
  skipReason: string
}

export interface RootCauseFormState {
  rootCause: string
  category: string
}

const actionDialogVisible = defineModel<boolean>('actionDialogVisible', { required: true })
const actionForm = defineModel<ActionFormState>('actionForm', { required: true })
const verifyDialogVisible = defineModel<boolean>('verifyDialogVisible', { required: true })
const verifyForm = defineModel<VerifyFormState>('verifyForm', { required: true })
const rootCauseDialogVisible = defineModel<boolean>('rootCauseDialogVisible', { required: true })
const rootCauseForm = defineModel<RootCauseFormState>('rootCauseForm', { required: true })

defineProps<{
  actionSubmitting: boolean
  verifySubmitting: boolean
  rootCauseSubmitting: boolean
}>()

defineEmits<{
  'add-action': []
  'submit-verification': []
  'confirm-root-cause': []
}>()

const ACTION_TYPES = [
  { value: 'MITIGATE', label: '止损' },
  { value: 'INVESTIGATE', label: '排查' },
  { value: 'FIX', label: '修复' },
  { value: 'ROLLBACK', label: '回滚' },
  { value: 'VERIFY', label: '验证' }
]

const RC_CATEGORIES = [
  { value: 'CONFIG', label: '配置错误' },
  { value: 'CAPACITY', label: '容量不足' },
  { value: 'CODE', label: '代码缺陷' },
  { value: 'DEPENDENCY', label: '依赖故障' },
  { value: 'NETWORK', label: '网络问题' },
  { value: 'DATA', label: '数据异常' },
  { value: 'HUMAN', label: '人为操作' },
  { value: 'EXTERNAL', label: '外部服务' },
  { value: 'UNKNOWN', label: '未定位' }
]
</script>

<template>
  <!-- ========== B2 处置动作记录弹窗 ========== -->
  <el-dialog v-model="actionDialogVisible" title="记录处置动作" width="560px" :close-on-click-modal="false">
    <div class="dialog-form">
      <div class="form-row">
        <label>动作类型</label>
        <select v-model="actionForm.actionType" class="form-input">
          <option v-for="t in ACTION_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
        </select>
      </div>
      <div class="form-row">
        <label>摘要</label>
        <input v-model="actionForm.summary" type="text" class="form-input" placeholder="一句话：做了什么" maxlength="255" />
      </div>
      <div class="form-row">
        <label>详情</label>
        <textarea v-model="actionForm.detail" class="form-input" rows="4" placeholder="命令/配置/日志片段（可选）"></textarea>
      </div>
      <div class="form-row">
        <label>是否有效</label>
        <select v-model="actionForm.effective" class="form-input">
          <option :value="null">未判定</option>
          <option :value="true">有效</option>
          <option :value="false">无效（失败尝试同样记录）</option>
        </select>
      </div>
    </div>
    <template #footer>
      <el-button @click="actionDialogVisible = false">取消</el-button>
      <el-button type="primary" :disabled="actionSubmitting" @click="$emit('add-action')">提交</el-button>
    </template>
  </el-dialog>

  <!-- ========== B3 验证弹窗 ========== -->
  <el-dialog v-model="verifyDialogVisible" title="修复验证" width="560px" :close-on-click-modal="false">
    <div class="dialog-form">
      <template v-if="!verifyForm.skip">
        <div class="form-row">
          <label>验证方式</label>
          <select v-model="verifyForm.method" class="form-input">
            <option value="MONITOR">监控确认</option>
            <option value="LOG">日志确认</option>
            <option value="BUSINESS">业务确认</option>
            <option value="MANUAL">人工确认</option>
          </select>
        </div>
        <div class="form-row">
          <label>验证结论</label>
          <textarea v-model="verifyForm.conclusion" class="form-input" rows="4" placeholder="确认业务已恢复、指标回到基线等"></textarea>
        </div>
      </template>
      <template v-else>
        <div class="form-row">
          <label>跳过理由</label>
          <textarea v-model="verifyForm.skipReason" class="form-input" rows="3" placeholder="跳过验证的理由（必填，将记入审计）"></textarea>
        </div>
      </template>
      <label class="skip-toggle">
        <input type="checkbox" v-model="verifyForm.skip" />
        <span>跳过验证（MTTR 统计时将排除此工单）</span>
      </label>
    </div>
    <template #footer>
      <el-button @click="verifyDialogVisible = false">取消</el-button>
      <el-button type="primary" :disabled="verifySubmitting" @click="$emit('submit-verification')">
        {{ verifyForm.skip ? '跳过并解决' : '验证通过' }}
      </el-button>
    </template>
  </el-dialog>

  <!-- ========== B3 根因确认弹窗 ========== -->
  <el-dialog v-model="rootCauseDialogVisible" title="确认根因" width="600px" :close-on-click-modal="false">
    <div class="dialog-form">
      <div class="form-row">
        <label>根因分类</label>
        <select v-model="rootCauseForm.category" class="form-input">
          <option v-for="c in RC_CATEGORIES" :key="c.value" :value="c.value">{{ c.label }}</option>
        </select>
      </div>
      <div class="form-row">
        <label>根因描述</label>
        <textarea v-model="rootCauseForm.rootCause" class="form-input" rows="8" placeholder="人工确认的根因。可一键采纳 AI 分析内容后编辑。"></textarea>
      </div>
      <p class="form-hint">AI 建议已自动填入，供参考后编辑。人工确认的根因 ≠ AI 建议。</p>
    </div>
    <template #footer>
      <el-button @click="rootCauseDialogVisible = false">取消</el-button>
      <el-button type="primary" :disabled="rootCauseSubmitting" @click="$emit('confirm-root-cause')">确认根因</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
/* ===== B2~B4 弹窗表单 =====
   原样式定义在 TicketDetail 的 .dialog-form 之下，搬出来后自带，
   不再依赖父作用域（scoped 样式不穿透组件边界）。 */
.dialog-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.dialog-form .form-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dialog-form .form-row label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2, var(--text-2));
}

.dialog-form .form-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--border-2, var(--border-1));
  border-radius: 6px;
  font-size: 14px;
  font-family: inherit;
  box-sizing: border-box;
}

.form-hint {
  font-size: 12px;
  color: var(--text-3, var(--text-3));
  margin: 4px 0 0 0;
}

.skip-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-2, var(--text-2));
  cursor: pointer;
}
</style>
