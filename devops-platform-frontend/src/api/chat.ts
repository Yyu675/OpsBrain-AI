/**
 * 聊天 API - SSE 流式问答接口
 * 对应后端: GET /api/v1/chat/stream
 */

import { fetchEventSource } from '@microsoft/fetch-event-source'
import { API_ENDPOINTS } from '../config/api'
import { getAuthToken } from '../utils/http'
import type {
  SSEStartEvent,
  SSEToolStatusEvent,
  SSETokenEvent,
  SSECompleteEvent,
  SSEErrorEvent,
} from './types'

/**
 * SSE 事件回调接口
 */
export interface ChatStreamCallbacks {
  onStart?: (data: SSEStartEvent) => void
  onToolStatus?: (data: SSEToolStatusEvent) => void
  onToken?: (data: SSETokenEvent) => void
  onComplete?: (data: SSECompleteEvent) => void
  onError?: (data: SSEErrorEvent) => void
  /**
   * 流关闭时触发（无论是否收到过 complete）。
   *
   * **必须提供兜底**：服务端在未发 complete 就关流时（超时切断、网关 502、
   * Nginx proxy_read_timeout 到期），fetchEventSource 会**正常 resolve**——
   * 不抛错、不进 catch、不触发 onError。调用方若只在 complete/error 里
   * 复位「生成中」状态，就会永远停在生成中，输入框禁用、对话框卡死。
   */
  onClose?: () => void
}

/**
 * SSE 流式问答
 * @param query 用户提问（1~1500字）
 * @param callbacks 事件回调
 * @param abortController 用于取消请求的 AbortController
 */
export async function chatStream(
  query: string,
  callbacks: ChatStreamCallbacks,
  abortController?: AbortController,
  sessionId?: string
): Promise<void> {
  // 参数校验
  if (!query || query.trim().length === 0) {
    throw new Error('查询内容不能为空')
  }
  if (query.length > 1500) {
    throw new Error('查询内容不能超过1500字')
  }

  // 改用 POST：查询走请求体，规避 URL 长度限制。
  // 运维场景常需粘贴长日志/堆栈，GET 方式中文约 300 字即触达
  // Tomcat 8KB 请求头上限返回 400。后端 GET 端点仍保留兼容。
  await fetchEventSource(API_ENDPOINTS.CHAT_STREAM, {
    method: 'POST',
    headers: {
      'Accept': 'text/event-stream',
      'Content-Type': 'application/json',
      // 方向三鉴权：SSE 也需带 token。fetchEventSource 基于 fetch，支持自定义头，
      // 故用 satoken 头即可（无需退化到 ?satoken= query）。
      ...(getAuthToken() ? { satoken: getAuthToken() as string } : {}),
    },
    // sessionId 用于三层记忆的多轮关联，省略则后端退化为单轮无记忆
    body: JSON.stringify(sessionId ? { query, sessionId } : { query }),
    signal: abortController?.signal,

    // 处理消息
    onmessage(ev) {
      try {
        const data = JSON.parse(ev.data || '{}')

        switch (ev.event) {
          case 'start':
            callbacks.onStart?.(data as SSEStartEvent)
            break

          case 'tool_status':
            callbacks.onToolStatus?.(data as SSEToolStatusEvent)
            break

          case 'token':
            callbacks.onToken?.(data as SSETokenEvent)
            break

          case 'complete':
            callbacks.onComplete?.(data as SSECompleteEvent)
            break

          case 'error':
            callbacks.onError?.(data as SSEErrorEvent)
            break

          default:
            console.warn('[ChatStream] 未知事件类型:', ev.event)
        }
      } catch (err) {
        console.error('[ChatStream] 解析事件数据失败:', err)
        // 解析失败不能静默——否则 isStreaming 会永远为 true
        callbacks.onError?.({
          traceId: '',
          code: 50001,
          message: '数据解析失败，请稍后重试'
        } as SSEErrorEvent)
      }
    },

    // 处理打开连接
    async onopen(response) {
      if (response.ok && response.headers.get('content-type')?.includes('text/event-stream')) {
        return // 连接成功
      }

      // 非 SSE 响应：多半是请求在进入流式链路**之前**就被拒了
      // （参数校验失败 / 限流 / 未登录）。这类响应是 ApiResponse JSON，
      // 里面有可读的 message —— 原实现只抛「SSE连接失败: 400」，
      // 把「提问超过 1500 字」这种用户能自己解决的问题变成了无从下手的报错。
      let detail = `${response.status} ${response.statusText}`
      try {
        const body = await response.clone().json()
        if (body?.message) {
          detail = body.message
        }
      } catch {
        // 响应体非 JSON（如网关返回的 HTML 错误页），保留状态码描述
      }
      throw new Error(detail)
    },

    // 流关闭：转交调用方收尾（见 onClose 的契约说明）
    onclose() {
      callbacks.onClose?.()
    },

    // 处理错误（throw 阻止无限重连）
    onerror(err) {
      console.error('[ChatStream] SSE 错误:', err)
      throw err  // 阻止 fetchEventSource 自动重连
    },
  })
}
