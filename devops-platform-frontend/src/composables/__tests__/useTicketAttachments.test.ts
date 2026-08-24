/**
 * useTicketAttachments —— 抽出后的直接单测。
 *
 * ── 重点覆盖「失败时该不该打扰用户」这条分界 ──────────────────
 * 附件的三类操作对失败的处理刻意不同：
 *   加载失败 -> 只降级清空，不弹错（辅助信息，弹错会让人以为工单坏了）
 *   上传失败 -> 必须弹错（用户主动发起，静默失败会让他以为成功了）
 *   删除失败 -> 必须弹错，且不能把本地列表里的项删掉
 *
 * 这条分界很容易在重构时被「统一成一种处理」而抹平，
 * 抹平的后果是两头都不对：要么满屏红色提示，要么静默丢操作。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

const confirmMock = vi.hoisted(() => vi.fn())
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: confirmMock },
}))

const notifyMock = vi.hoisted(() => ({
  success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
}))
const handleServerErrorMock = vi.hoisted(() => vi.fn())
vi.mock('@/utils/notify', () => ({
  notify: notifyMock,
  handleServerError: handleServerErrorMock,
}))

const api = vi.hoisted(() => ({
  fetchTicketAttachments: vi.fn(),
  uploadTicketAttachment: vi.fn(),
  fetchAttachmentDownloadUrl: vi.fn(),
  deleteTicketAttachment: vi.fn(),
}))
vi.mock('@/api/tickets', () => api)

import { useTicketAttachments } from '../useTicketAttachments'

const meta = (id: number, name = `f${id}.log`) => ({
  id, originalName: name, size: 1024, contentType: 'text/plain',
  uploader: '张明', uploadedAt: '2026-08-25 10:00:00',
})

const setup = (id = 'TKT-1') => {
  const ticketId = ref(id)
  const r = useTicketAttachments({ ticketId, getOperator: () => '张明' })
  return { ticketId, ...r }
}

/** 造一个带文件的 change 事件 */
const changeEvent = (file: File | null) => {
  const input = { files: file ? [file] : [], value: 'C:\\fakepath\\x.log' }
  return { target: input } as unknown as Event
}

beforeEach(() => {
  vi.clearAllMocks()
  confirmMock.mockResolvedValue('confirm')
})

describe('useTicketAttachments — 加载', () => {
  it('成功时填充列表', async () => {
    api.fetchTicketAttachments.mockResolvedValue([meta(1), meta(2)])
    const { attachments, loadAttachments } = setup()

    await loadAttachments()

    expect(attachments.value).toHaveLength(2)
  })

  it('失败时只清空列表，不弹错误提示', async () => {
    api.fetchTicketAttachments.mockRejectedValue(new Error('boom'))
    const { attachments, loadAttachments, attachmentsLoading } = setup()

    await loadAttachments()

    // 附件是辅助信息，拉不到时弹红色提示会让用户以为工单本身出了问题
    expect(attachments.value).toEqual([])
    expect(handleServerErrorMock).not.toHaveBeenCalled()
    expect(attachmentsLoading.value).toBe(false)
  })

  it('工单 ID 为空时不发请求', async () => {
    const { loadAttachments } = setup('')
    await loadAttachments()
    expect(api.fetchTicketAttachments).not.toHaveBeenCalled()
  })
})

describe('useTicketAttachments — 上传', () => {
  it('成功后就地追加，不整表重拉', async () => {
    api.fetchTicketAttachments.mockResolvedValue([meta(1)])
    const { attachments, loadAttachments, onAttachmentSelected } = setup()
    await loadAttachments()
    api.fetchTicketAttachments.mockClear()

    api.uploadTicketAttachment.mockResolvedValue(meta(2, 'new.log'))
    await onAttachmentSelected(changeEvent(new File(['x'], 'new.log')))

    expect(attachments.value.map((a) => a.id)).toEqual([1, 2])
    // 重拉多一次往返，且慢网络下会出现「提示已弹、列表未更新」的空窗
    expect(api.fetchTicketAttachments).not.toHaveBeenCalled()
    expect(notifyMock.success).toHaveBeenCalled()
  })

  it('上传失败必须提示——用户主动发起的动作不能静默失败', async () => {
    api.uploadTicketAttachment.mockRejectedValue(new Error('too large'))
    const { onAttachmentSelected, uploading } = setup()

    await onAttachmentSelected(changeEvent(new File(['x'], 'a.log')))

    expect(handleServerErrorMock).toHaveBeenCalled()
    expect(uploading.value).toBe(false)
  })

  it('立刻清空 input.value——否则选同一文件第二次不触发 change', async () => {
    api.uploadTicketAttachment.mockResolvedValue(meta(1))
    const { onAttachmentSelected } = setup()
    const evt = changeEvent(new File(['x'], 'a.log'))

    await onAttachmentSelected(evt)

    // 不清的话用户会以为「点了没反应」
    expect((evt.target as unknown as { value: string }).value).toBe('')
  })

  it('没选文件时安全返回', async () => {
    const { onAttachmentSelected } = setup()
    await onAttachmentSelected(changeEvent(null))
    expect(api.uploadTicketAttachment).not.toHaveBeenCalled()
  })

  it('上传时带上操作者名字', async () => {
    api.uploadTicketAttachment.mockResolvedValue(meta(1))
    const { onAttachmentSelected } = setup('TKT-9')

    await onAttachmentSelected(changeEvent(new File(['x'], 'a.log')))

    expect(api.uploadTicketAttachment).toHaveBeenCalledWith(
      'TKT-9', expect.any(File), '张明'
    )
  })
})

describe('useTicketAttachments — 下载', () => {
  it('用 noopener 打开新窗口', async () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    api.fetchAttachmentDownloadUrl.mockResolvedValue('https://x/f.log')
    const { downloadAttachment } = setup()

    await downloadAttachment(meta(1))

    // noopener 让新窗口拿不到 window.opener，防止下载页反向操作本页
    expect(openSpy).toHaveBeenCalledWith('https://x/f.log', '_blank', 'noopener')
    openSpy.mockRestore()
  })

  it('取链接失败时提示', async () => {
    api.fetchAttachmentDownloadUrl.mockRejectedValue(new Error('403'))
    const { downloadAttachment } = setup()

    await downloadAttachment(meta(1))

    expect(handleServerErrorMock).toHaveBeenCalled()
  })
})

describe('useTicketAttachments — 删除', () => {
  it('需二次确认，取消时不发请求', async () => {
    confirmMock.mockRejectedValue('cancel')
    const { removeAttachment } = setup()

    await removeAttachment(meta(1))

    expect(api.deleteTicketAttachment).not.toHaveBeenCalled()
  })

  it('确认后从列表移除对应项', async () => {
    api.fetchTicketAttachments.mockResolvedValue([meta(1), meta(2)])
    const { attachments, loadAttachments, removeAttachment } = setup()
    await loadAttachments()

    api.deleteTicketAttachment.mockResolvedValue(undefined)
    await removeAttachment(meta(1))

    expect(attachments.value.map((a) => a.id)).toEqual([2])
  })

  it('删除失败时保留本地项，不能假装删掉了', async () => {
    api.fetchTicketAttachments.mockResolvedValue([meta(1)])
    const { attachments, loadAttachments, removeAttachment } = setup()
    await loadAttachments()

    api.deleteTicketAttachment.mockRejectedValue(new Error('boom'))
    await removeAttachment(meta(1))

    // 失败还从列表里抹掉，用户会以为删成功了，刷新后又冒出来
    expect(attachments.value).toHaveLength(1)
    expect(handleServerErrorMock).toHaveBeenCalled()
  })
})

describe('useTicketAttachments — 切换工单', () => {
  it('工单 ID 变化时先清空再重载', async () => {
    api.fetchTicketAttachments.mockResolvedValue([meta(1)])
    const { ticketId, attachments, loadAttachments } = setup('TKT-1')
    await loadAttachments()
    expect(attachments.value).toHaveLength(1)

    api.fetchTicketAttachments.mockResolvedValue([meta(9)])
    ticketId.value = 'TKT-2'
    // watch 是异步的，等一个微任务队列
    await new Promise((r) => setTimeout(r, 0))

    // 不清空的话，跳转瞬间 B 的附件区会显示 A 的附件，
    // 用户可能正好在那一刻点了下载，拿到另一张单的文件
    expect(api.fetchTicketAttachments).toHaveBeenLastCalledWith('TKT-2')
    expect(attachments.value.map((a) => a.id)).toEqual([9])
  })
})
