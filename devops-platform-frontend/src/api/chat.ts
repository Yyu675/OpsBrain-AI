/**
 * 聊天 API - SSE 流式问答接口
 * 对应后端: GET /api/v1/chat/stream
 */

import { fetchEventSource } from '@microsoft/fetch-event-source'
import { API_ENDPOINTS } from '../config/api'
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
      } else {
        // 非 200 或非 SSE 响应
        throw new Error(`SSE连接失败: ${response.status} ${response.statusText}`)
      }
    },

    // 处理错误（throw 阻止无限重连）
    onerror(err) {
      console.error('[ChatStream] SSE 错误:', err)
      throw err  // 阻止 fetchEventSource 自动重连
    },
  })
}
