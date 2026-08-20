const SECOND = 1000
const MINUTE = 60 * SECOND
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

export const parseDate = (input: string | number | Date | null | undefined): Date | null => {
  if (input === null || input === undefined || input === '') return null
  if (input instanceof Date) return isNaN(input.getTime()) ? null : input
  const d = new Date(input)
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
