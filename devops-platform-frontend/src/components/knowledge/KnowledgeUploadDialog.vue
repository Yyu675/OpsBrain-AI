<script setup lang="ts">
/**
 * 知识文档上传对话框（V2）
 *
 * PDF/Word/Excel/PPT/TXT/Markdown → 后端 Tika 解析 → 标准入库链路
 * （清洗/去重/向量化）。原件由后端留存 MinIO。
 *
 * 上传属可逆操作（ADMIN+OPS），权限由后端 KnowledgeWriteGuard 把守；
 * 前端只负责把 403/重复/解析失败如实呈现。
 */
import { ref, computed, watch } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'
import type { UploadFile, UploadRawFile } from 'element-plus'
import {
  uploadKnowledgeDocument,
  fetchKnowledgeBases,
  DuplicateContentError,
} from '@/api/knowledge'
import type { KnowledgeBaseItem } from '@/api/types'
import { notify, handleServerError } from '@/utils/notify'

const props = defineProps<{
  modelValue: boolean
  /** 当前列表正在筛选的知识库（作为上传默认归属） */
  activeKbId?: number | null
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  /** 入库成功（父组件刷新文档列表） */
  (e: 'uploaded', docId: number): void
}>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => {
  visible.value = v
  if (v) resetForm()
})
watch(visible, v => emit('update:modelValue', v))

const ACCEPT = '.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md,.html,.htm'
const MAX_SIZE_MB = 20

const bases = ref<KnowledgeBaseItem[]>([])
const basesLoading = ref(false)
const pickedFile = ref<UploadRawFile | null>(null)
const title = ref('')
const kbId = ref<number | null>(null)
const publish = ref(true)
const uploading = ref(false)

const activeBases = computed(() => bases.value.filter(b => b.status === 'ACTIVE'))

const loadBases = async () => {
  basesLoading.value = true
  try {
    bases.value = await fetchKnowledgeBases()
    // 默认归属：优先跟随当前列表筛选的库，否则默认库
    if (kbId.value == null) {
      kbId.value = props.activeKbId
          ?? bases.value.find(b => b.code.toLowerCase() === 'default')?.id
          ?? null
    }
  } catch (error) {
    handleServerError(error, { action: '加载知识库列表' })
  } finally {
    basesLoading.value = false
  }
}

const resetForm = () => {
  pickedFile.value = null
  title.value = ''
  publish.value = true
  kbId.value = props.activeKbId ?? null
  void loadBases()
}

const onFileChange = (uploadFile: UploadFile) => {
  const raw = uploadFile.raw
  if (!raw) return
  if (raw.size > MAX_SIZE_MB * 1024 * 1024) {
    notify.warning(`文件超过 ${MAX_SIZE_MB}MB 上限，请拆分后上传`)
    pickedFile.value = null
    return
  }
  pickedFile.value = raw
  // 标题缺省取文件名（去扩展名），用户可改
  if (!title.value.trim()) {
    const name = raw.name.replace(/\.[^.]+$/, '')
    title.value = name
  }
}

const removeFile = () => {
  pickedFile.value = null
}

const submit = async () => {
  if (!pickedFile.value) {
    notify.warning('请先选择要上传的文件')
    return
  }
  uploading.value = true
  try {
    const result = await uploadKnowledgeDocument({
      file: pickedFile.value,
      title: title.value.trim() || undefined,
      kbId: kbId.value,
      publish: publish.value,
    })
    if (result.nearDuplicates?.length) {
      notify.warning(
        `已入库，但与《${result.nearDuplicates[0].title}》内容近似（汉明距离 ${result.nearDuplicates[0].distance}），请确认是否重复`
      )
    } else if (result.indexStatus === 'FAILED') {
      notify.warning(`文档已保存但向量化失败：${result.indexError ?? '未知原因'}，可在列表重试`)
    } else {
      notify.success(
        publish.value
          ? `已入库并建立索引（解析出 ${result.parsedLength} 字符）`
          : '已存为草稿'
      )
    }
    emit('uploaded', result.id)
    visible.value = false
  } catch (error) {
    if (error instanceof DuplicateContentError) {
      notify.warning(`内容与已有文档《${error.duplicateTitle ?? '未知'}》完全相同，未重复入库`)
    } else {
      handleServerError(error, { action: '上传文件' })
    }
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="上传文档入库"
    width="560px"
    :close-on-click-modal="false"
    :close-on-press-escape="!uploading"
    :show-close="!uploading"
  >
    <el-form v-if="visible" label-width="90px" label-position="right">
      <el-form-item label="文件" required>
        <el-upload
          v-if="!pickedFile"
          drag
          :auto-upload="false"
          :limit="1"
          :accept="ACCEPT"
          :show-file-list="false"
          :on-change="onFileChange"
          class="upload-dragger"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">
            拖拽文件到此处，或 <em>点击选择</em>
          </div>
          <template #tip>
            <div class="el-upload__tip">
              支持 PDF / Word / Excel / PPT / TXT / Markdown / HTML，单文件 ≤ {{ MAX_SIZE_MB }}MB。
              扫描件（纯图片 PDF）无法提取文本，会被拒绝。
            </div>
          </template>
        </el-upload>
        <div v-else class="picked-file">
          <span class="picked-name">{{ pickedFile.name }}</span>
          <span class="picked-size">{{ (pickedFile.size / 1024 / 1024).toFixed(2) }} MB</span>
          <el-button link type="danger" size="small" :disabled="uploading" @click="removeFile">
            移除
          </el-button>
        </div>
      </el-form-item>

      <el-form-item label="标题">
        <el-input v-model="title" maxlength="255" placeholder="缺省取文件名（去扩展名）" />
      </el-form-item>

      <el-form-item label="知识库">
        <el-select
          v-model="kbId"
          :loading="basesLoading"
          placeholder="选择所属知识库（决定切片参数）"
          style="width: 100%"
        >
          <el-option
            v-for="kb in activeBases"
            :key="kb.id"
            :value="kb.id"
            :label="`${kb.name}（${kb.effectiveChildChunkSize} 字/片）`"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="发布">
        <el-switch v-model="publish" />
        <span class="form-tip">{{ publish ? '发布并立即向量化（可被 AI 检索）' : '存为草稿，暂不向量化' }}</span>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button :disabled="uploading" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="uploading" :disabled="!pickedFile" @click="submit">
        {{ uploading ? '解析并入库中…' : '开始入库' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.upload-dragger { width: 100%; }
.picked-file {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  width: 100%;
}
.picked-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.picked-size { color: var(--el-text-color-secondary); font-size: 12px; flex-shrink: 0; }
.form-tip { margin-left: 10px; font-size: 12px; color: var(--el-text-color-secondary); }
</style>
