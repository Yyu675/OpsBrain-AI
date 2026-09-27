const PREFIX = '__store__:'

interface PersistPayload<T> {
  version: number
  value: T
  savedAt: number
}

const safeGet = (key: string): string | null => {
  try { return localStorage.getItem(PREFIX + key) } catch { return null }
}

/**
 * 写入失败的监听器。
 *
 * 为什么需要：`safeSet` 原本把异常整个吞掉。隐私模式或配额撑满时，
 * 保存偏好（列宽、主题、通知已读）会**看似成功、刷新后复原**，
 * 控制台没有任何线索，用户只会以为「这个功能坏了」而无从反馈。
 *
 * 这里不直接弹提示——persist 是底层工具，不该依赖 UI 层
 * （会造成 utils → element-plus 的反向依赖，且测试环境要额外 mock）。
 * 改为暴露事件，由 main.ts 接上 notify。
 */
type WriteFailureListener = (info: { key: string; error: unknown; quotaExceeded: boolean }) => void
const writeFailureListeners = new Set<WriteFailureListener>()

/** 订阅持久化写入失败。返回取消订阅函数。 */
export const onPersistWriteFailure = (fn: WriteFailureListener): (() => void) => {
  writeFailureListeners.add(fn)
  return () => writeFailureListeners.delete(fn)
}

/** 判定是否为配额超限（各浏览器 name/code 不统一，需多路识别） */
const isQuotaExceeded = (e: unknown): boolean => {
  if (!e || typeof e !== 'object') return false
  const name = (e as { name?: string }).name
  const code = (e as { code?: number }).code
  return (
    name === 'QuotaExceededError' ||
    name === 'NS_ERROR_DOM_QUOTA_REACHED' || // Firefox
    code === 22 ||
    code === 1014
  )
}

const safeSet = (key: string, value: string) => {
  try {
    localStorage.setItem(PREFIX + key, value)
  } catch (e) {
    const quotaExceeded = isQuotaExceeded(e)
    // 至少留下控制台线索——此前连这个都没有
    console.warn(
      `[persist] 写入 "${key}" 失败${quotaExceeded ? '（本地存储已满）' : ''}，该项偏好本次不会保留:`,
      e
    )
    writeFailureListeners.forEach(fn => {
      try { fn({ key, error: e, quotaExceeded }) } catch { /* consumer bug */ }
    })
  }
}

const safeRemove = (key: string) => {
  try { localStorage.removeItem(PREFIX + key) } catch { /* noop */ }
}

export type Migrator = (old: unknown) => unknown

interface LoadOptions {
  migrations?: Record<number, Migrator>
}

const BACKUP_SUFFIX = '__backup'

export const loadPersisted = <T>(
  key: string,
  version: number,
  options: LoadOptions = {}
): T | null => {
  const raw = safeGet(key)
  if (!raw) return null
  let parsed: PersistPayload<T> | null
  try {
    parsed = JSON.parse(raw) as PersistPayload<T>
  } catch {
    safeRemove(key)
    return null
  }
  if (!parsed || typeof parsed !== 'object' || !('value' in parsed)) {
    safeRemove(key)
    return null
  }

  if (parsed.version === version) return parsed.value

  const migrations = options.migrations
  if (parsed.version < version && migrations) {
    try {
      safeSet(key + BACKUP_SUFFIX, raw)
      let current: unknown = parsed.value
      for (let v = parsed.version + 1; v <= version; v++) {
        const step = migrations[v]
        if (!step) continue
        current = step(current)
      }
      const migrated: PersistPayload<T> = { version, value: current as T, savedAt: Date.now() }
      safeSet(key, JSON.stringify(migrated))
      return migrated.value
    } catch {
      safeRemove(key)
      return null
    }
  }

  safeSet(key + BACKUP_SUFFIX, raw)
  safeRemove(key)
  return null
}

export const savePersisted = <T>(key: string, value: T, version: number) => {
  try {
    const payload: PersistPayload<T> = { version, value, savedAt: Date.now() }
    safeSet(key, JSON.stringify(payload))
  } catch { /* circular / oversized */ }
}

export const clearPersisted = (key: string) => {
  safeRemove(key)
  safeRemove(key + BACKUP_SUFFIX)
}

export const getBackup = (key: string): string | null => safeGet(key + BACKUP_SUFFIX)

export const readBackupPayload = <T>(key: string): PersistPayload<T> | null => {
  const raw = safeGet(key + BACKUP_SUFFIX)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as PersistPayload<T>
    if (!parsed || typeof parsed !== 'object' || !('value' in parsed)) return null
    return parsed
  } catch {
    return null
  }
}

export const clearBackup = (key: string) => safeRemove(key + BACKUP_SUFFIX)

type Debounced<A extends unknown[]> = ((...args: A) => void) & {
  flush: () => void
  cancel: () => void
}
export const debounce = <A extends unknown[]>(fn: (...args: A) => void, wait = 300): Debounced<A> => {
  let timer: ReturnType<typeof setTimeout> | null = null
  let lastArgs: A | null = null
  const wrapped = ((...args: A) => {
    lastArgs = args
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      if (lastArgs) fn(...lastArgs)
    }, wait)
  }) as Debounced<A>
  wrapped.flush = () => {
    if (timer && lastArgs) {
      clearTimeout(timer)
      timer = null
      fn(...lastArgs)
    }
  }
  wrapped.cancel = () => {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    lastArgs = null
  }
  return wrapped
}
