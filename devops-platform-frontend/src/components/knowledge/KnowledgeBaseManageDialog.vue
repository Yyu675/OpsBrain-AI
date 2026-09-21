<script setup lang="ts">
/**
 * 知识库管理对话框（V2）
 *
 * 管理知识库顶层实体：新建/编辑（含按库切片参数）/启停/重建索引。
 * 用 Dialog 而非抽屉（项目 UI 约定）。
 *
 * 切片参数语义：留空 = 跟随全局默认（2400/600/100）；改动只影响之后
 * 索引的文档，存量生效需显式点「重建索引」（付费 API 成本，ADMIN 二次确认）。
 */
import { ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  createKnowledgeBase,
  fetchKnowledgeBases,
  reindexKnowledgeBase,
  updateKnowledgeBase,
} from '@/api/knowledge'
import type { KnowledgeBaseItem } from '@/api/types'
import { notify, handleServerError } from '@/utils/notify'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  /** 库数据发生变化（新建/编辑/重建完成），父组件刷新库选择器 */
  (e: 'changed'): void
}>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => {
  visible.value = v
  if (v) void loadBases()
})
watch(visible, v => emit('update:modelValue', v))

const bases = ref<KnowledgeBaseItem[]>([])
const loading = ref(false)

const loadBases = async () => {
  loading.value = true
  try {
    bases.value = await fetchKnowledgeBases()
  } catch (error) {
    handleServerError(error, { action: '加载知识库' })
  } finally {
    loading.value = false
  }
}

// ==================== 编辑/新建表单 ====================

interface KbForm {
  id: number | null
  name: string
  code: string
  description: string
  parentChunkSize: number | undefined
  childChunkSize: number | undefined
  chunkOverlap: number | undefined
  status: 'ACTIVE' | 'DISABLED'
  resetParams: boolean
}

const emptyForm = (): KbForm => ({
  id: null, name: '', code: '', description: '',
  parentChunkSize: undefined, childChunkSize: undefined, chunkOverlap: undefined,
  status: 'ACTIVE', resetParams: false,
})

const formOpen = ref(false)
const saving = ref(false)
const form = ref<KbForm>(emptyForm())

const isDefaultKb = (kb: KnowledgeBaseItem) => kb.code.toLowerCase() === 'default'

const openCreate = () => {
  form.value = emptyForm()
  formOpen.value = true
}

const openEdit = (kb: KnowledgeBaseItem) => {
  form.value = {
    id: kb.id,
    name: kb.name,
    code: kb.code,
    description: kb.description ?? '',
    parentChunkSize: kb.parentChunkSize ?? undefined,
    childChunkSize: kb.childChunkSize ?? undefined,
    chunkOverlap: kb.chunkOverlap ?? undefined,
    status: kb.status,
    resetParams: false,
  }
  formOpen.value = true
}

const save = async () => {
  const f = form.value
  if (!f.name.trim() || !f.code.trim()) {
    notify.warning('名称与编码不能为空')
    return
  }
  if (f.resetParams && (f.parentChunkSize != null || f.childChunkSize != null || f.chunkOverlap != null)) {
    notify.warning('「恢复默认参数」与显式参数互斥，请只选一种')
    return
  }
  saving.value = true
  try {
    if (f.id == null) {
      await createKnowledgeBase({
        name: f.name.trim(),
        code: f.code.trim(),
        description: f.description.trim() || undefined,
        parentChunkSize: f.parentChunkSize ?? null,
        childChunkSize: f.childChunkSize ?? null,
        chunkOverlap: f.chunkOverlap ?? null,
      })
      notify.success('知识库已创建')
    } else {
      await updateKnowledgeBase(f.id, {
        name: f.name.trim(),
        code: f.code.trim(),
        description: f.description.trim(),
        parentChunkSize: f.resetParams ? null : (f.parentChunkSize ?? null),
        childChunkSize: f.resetParams ? null : (f.childChunkSize ?? null),
        chunkOverlap: f.resetParams ? null : (f.chunkOverlap ?? null),
        status: f.status,
        clearChunkParams: f.resetParams,
      })
      notify.success('知识库已更新（参数变更只影响之后索引的文档）')
    }
    formOpen.value = false
    await loadBases()
    emit('changed')
  } catch (error) {
    handleServerError(error, { action: f.id == null ? '创建知识库' : '更新知识库' })
  } finally {
    saving.value = false
  }
}

// ==================== 重建索引 ====================

const reindexing = ref<number | null>(null)

const reindexAll = async (kb: KnowledgeBaseItem) => {
  try {
    await ElMessageBox.confirm(
      `将重建「${kb.name}」下全部已发布文档的索引（共 ${kb.docCount} 篇）。`
      + '每篇都会调用远程向量化接口产生费用，且过程不可逆。确认继续？',
      '重建库索引',
      { type: 'warning', confirmButtonText: '确认重建', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  reindexing.value = kb.id
  try {
    const result = await reindexKnowledgeBase(kb.id)
    if (result.failed > 0) {
      notify.warning(`重建完成：成功 ${result.success} 篇，失败 ${result.failed} 篇（可在文档列表重试向量化）`)
    } else {
      notify.success(`重建完成：${result.success} 篇文档全部重建成功`)
    }
    await loadBases()
    emit('changed')
  } catch (error) {
    handleServerError(error, { action: '重建库索引' })
  } finally {
    reindexing.value = null
  }
}

/** 切片参数展示：显式配置加粗语义由模板处理，这里只拼文本 */
const paramsText = (kb: KnowledgeBaseItem) => {
  const custom = kb.parentChunkSize != null || kb.childChunkSize != null || kb.chunkOverlap != null
  const text = `${kb.effectiveParentChunkSize} / ${kb.effectiveChildChunkSize} / ${kb.effectiveChunkOverlap}`
  return custom ? text : `${text}（默认）`
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="知识库管理"
    width="760px"
    :close-on-click-modal="false"
  >
    <!-- v-if 懒渲染：el-dialog 未注册为组件的测试环境中 slot 会被当作
         普通子节点立即渲染，el-table 作用域插槽拿不到 row 会抛错 -->
    <div v-if="visible" class="kb-dialog-body">
    <div v-if="!formOpen" class="kb-list">
      <div class="kb-list-toolbar">
        <span class="kb-hint">切片参数格式：父段落 / 子段落 / 重叠（字符），「默认」= 跟随全局 2400/600/100</span>
        <el-button type="primary" size="small" @click="openCreate">新建知识库</el-button>
      </div>
      <el-table :data="bases" v-loading="loading" size="small">
        <el-table-column prop="name" label="名称" min-width="140">
          <template #default="{ row }">
            <span class="kb-name">{{ row.name }}</span>
            <span v-if="isDefaultKb(row)" class="kb-default-badge">默认</span>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="编码" width="110" />
        <el-table-column label="文档数" width="110" align="right">
          <template #default="{ row }">
            <span>{{ row.docCount }}</span>
            <!-- 索引失败计数是发现「文档在库里但检索不到」空洞的入口，必须透出 -->
            <span
              v-if="row.failedCount > 0"
              class="kb-failed-count"
              title="有文档向量化失败：AI 检索命中不到它们，可在文档列表重试"
            >（失败 {{ row.failedCount }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="切片参数" min-width="170">
          <template #default="{ row }">{{ paramsText(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button
              link type="danger" size="small"
              :loading="reindexing === row.id"
              :disabled="row.docCount === 0"
              @click="reindexAll(row)"
            >重建索引</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-else class="kb-form">
      <el-form label-width="110px" label-position="right">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="64" placeholder="如：故障 FAQ 库" />
        </el-form-item>
        <el-form-item label="编码" required>
          <el-input
            v-model="form.code" maxlength="64"
            :disabled="form.id != null && form.code.toLowerCase() === 'default'"
            placeholder="小写字母/数字/连字符，创建后用于 API 引用"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" maxlength="512" placeholder="这个库收什么文档" />
        </el-form-item>

        <el-divider content-position="left">切片参数（留空 = 跟随全局默认）</el-divider>
        <el-form-item v-if="form.id != null" label="恢复默认参数">
          <el-switch v-model="form.resetParams" />
          <span class="kb-form-tip">开启后清空本库全部自定义参数</span>
        </el-form-item>
        <template v-if="!form.resetParams">
          <el-form-item label="父段落大小">
            <el-input-number v-model="form.parentChunkSize" :min="100" :max="20000" :step="100"
                             placeholder="默认 2400" controls-position="right" />
            <span class="kb-form-tip">字符数，默认 2400（约 800 token）</span>
          </el-form-item>
          <el-form-item label="子段落大小">
            <el-input-number v-model="form.childChunkSize" :min="50" :max="5000" :step="50"
                             placeholder="默认 600" controls-position="right" />
            <span class="kb-form-tip">字符数，默认 600，须小于父段落</span>
          </el-form-item>
          <el-form-item label="重叠大小">
            <el-input-number v-model="form.chunkOverlap" :min="0" :max="1000" :step="10"
                             placeholder="默认 100" controls-position="right" />
            <span class="kb-form-tip">字符数，默认 100，须小于子段落</span>
          </el-form-item>
        </template>

        <el-form-item v-if="form.id != null && form.code.toLowerCase() !== 'default'" label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="ACTIVE">启用</el-radio>
            <el-radio value="DISABLED">停用（拒收新文档，存量仍可检索）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div class="kb-form-actions">
        <el-button @click="formOpen = false">返回列表</el-button>
        <el-button type="primary" :loading="saving" @click="save">
          {{ form.id == null ? '创建' : '保存' }}
        </el-button>
      </div>
    </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.kb-list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.kb-hint { font-size: 12px; color: var(--el-text-color-secondary); }
.kb-name { font-weight: 500; }
.kb-failed-count {
  font-size: 11px;
  color: var(--el-color-danger);
}
.kb-default-badge {
  margin-left: 6px;
  font-size: 11px;
  color: var(--el-color-primary);
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 4px;
  padding: 0 4px;
}
.kb-form-tip {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.kb-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
</style>
