const SECOND = 1000
const MINUTE = 60 * SECOND
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/**
 * 后端时区偏移。
 *
 * 后端用 Java `LocalDateTime` 序列化时间，形如 `2026-08-24T10:30:00`——
 * **不带任何时区信息**。而 `new Date('2026-08-24T10:30:00')` 会按
 * **浏览器本地时区**解析（ES2015+ 对无时区的 date-time 形式如此规定）。
 *
 * 两者不一致时会算出错误的时间差。实测：服务器 Asia/Shanghai、
 * 用户在 America/New_York 时，一张 1 小时前创建的工单会被算成
 * 「11 小时后」——相对时间、SLA 倒计时、超时判定全部失真，
 * 而页面上的绝对时间看起来却是对的，极难察觉。
 *
 * 服务器固定 Asia/Shanghai（见 docker-compose 的 TZ 与 Dockerfile），
 * 故把无时区的字符串按 +08:00 解释。
 */
const SERVER_UTC_OFFSET = '+08:00'

/** 服务器时区标识，与 SERVER_UTC_OFFSET 表达同一事实（供 Intl API 使用） */
const SERVER_TIME_ZONE = 'Asia/Shanghai'

/** 匹配无时区后缀的 ISO date-time（后端 LocalDateTime 的形态） */
const NAIVE_DATETIME = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2})?(\.\d+)?$/

export const parseDate = (input: string | number | Date | null | undefined): Date | null => {
  if (input === null || input === undefined || input === '') return null
  if (input instanceof Date) return isNaN(input.getTime()) ? null : input

  let value: string | number = input
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (NAIVE_DATETIME.test(trimmed)) {
      // 补上服务器时区，避免被当成浏览器本地时间
      value = trimmed.replace(' ', 'T') + SERVER_UTC_OFFSET
    }
  }

  const d = new Date(value)
  return isNaN(d.getTime()) ? null : d
}

export const relativeTime = (input: string | number | Date | null | undefined, now: number = Date.now()): string => {
  const d = parseDate(input)
  if (!d) return '—'
  const diff = now - d.getTime()
  const abs = Math.abs(diff)
  const suffix = diff >= 0 ? '前' : '后'

  if (abs < 30 * SECOND) return '刚刚'
  if (abs < MINUTE) return `${Math.floor(abs / SECOND)} 秒${suffix}`
  if (abs < HOUR) return `${Math.floor(abs / MINUTE)} 分钟${suffix}`
  if (abs < DAY) return `${Math.floor(abs / HOUR)} 小时${suffix}`
  if (abs < 7 * DAY) return `${Math.floor(abs / DAY)} 天${suffix}`
  if (abs < 30 * DAY) return `${Math.floor(abs / (7 * DAY))} 周${suffix}`
  if (abs < 365 * DAY) return `${Math.floor(abs / (30 * DAY))} 个月${suffix}`
  return `${Math.floor(abs / (365 * DAY))} 年${suffix}`
}

const pad = (n: number) => String(n).padStart(2, '0')

export const formatAbsolute = (input: string | number | Date | null | undefined): string => {
  const d = parseDate(input)
  if (!d) return '—'
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export const formatDate = (input: string | number | Date | null | undefined): string => {
  const d = parseDate(input)
  if (!d) return '—'
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * 生成「与后端时间字段同格式」的当前时刻字符串（`YYYY-MM-DD HH:mm`）。
 *
 * ── 为什么必须有这个函数 ──────────────────────────────────────
 * 乐观更新需要先把 `updatedAt` 填成"现在"，等后端返回再校准。
 * 此前写的是：
 *
 *     t.updatedAt = new Date().toISOString().slice(0, 16).replace('T', ' ')
 *
 * `toISOString()` 返回的是 **UTC**，而 `updatedAt` 这个字段的约定格式是
 * 「服务器本地时间（+08:00）且不带时区后缀」——{@link parseDate} 正是按
 * +08:00 解析它的。于是写进去的是 UTC、读出来按 +08:00 算，**整整差 8 小时**。
 *
 * 实测：北京时间 10:30 编辑一张工单，列表「更新时间」立刻显示「8 小时前」。
 * 这个错误只在乐观更新的那个瞬间可见（后端响应回来就被校准了），
 * 但工单编辑恰恰是高频操作，用户会反复看到时间倒流。
 *
 * 本函数按**服务器时区**取当前时刻，与后端字段格式严格对齐。
 */
export const nowAsBackendTime = (): string => {
  // 用 en-CA 是因为它的 date 格式恰好是 YYYY-MM-DD（不是本地化偏好，是格式需要）
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: SERVER_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(new Date())

  const get = (type: string) => parts.find(p => p.type === type)?.value ?? '00'
  // hour 在 hour12:false 下极端情况会给出 '24'（午夜），归一为 '00'
  const hour = get('hour') === '24' ? '00' : get('hour')
  return `${get('year')}-${get('month')}-${get('day')} ${hour}:${get('minute')}`
}
