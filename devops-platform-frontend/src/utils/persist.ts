const PREFIX = '__store__:'

export interface PersistPayload<T> {
  version: number
  value: T
  savedAt: number
}

const safeGet = (key: string): string | null => {
  try { return localStorage.getItem(PREFIX + key) } catch { return null }
}

const safeSet = (key: string, value: string) => {
  try { localStorage.setItem(PREFIX + key, value) } catch { /* quota / disabled */ }
}

const safeRemove = (key: string) => {
  try { localStorage.removeItem(PREFIX + key) } catch { /* noop */ }
}

export type Migrator = (old: unknown) => unknown

export interface LoadOptions {
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

const CHANNEL_NAME = '__store_sync__'
const TAB_ID = Math.random().toString(36).slice(2) + Date.now().toString(36)

interface SyncMessage {
  key: string
  origin: string
}

type Listener = (key: string) => void
const listeners = new Set<Listener>()

let channel: BroadcastChannel | null = null
let storageListenerBound = false

const ensureChannel = () => {
  if (channel !== null) return channel
  if (typeof BroadcastChannel === 'undefined') return null
  try {
    channel = new BroadcastChannel(CHANNEL_NAME)
    channel.addEventListener('message', (e: MessageEvent<SyncMessage>) => {
      const data = e.data
      if (!data || typeof data !== 'object') return
      if (data.origin === TAB_ID) return
      listeners.forEach(fn => {
        try { fn(data.key) } catch { /* consumer bug */ }
      })
    })
  } catch { channel = null }
  return channel
}

const ensureStorageListener = () => {
  if (storageListenerBound) return
  if (typeof window === 'undefined') return
  window.addEventListener('storage', (e) => {
    if (!e.key || !e.key.startsWith(PREFIX)) return
    const key = e.key.slice(PREFIX.length)
    listeners.forEach(fn => {
      try { fn(key) } catch { /* consumer bug */ }
    })
  })
  storageListenerBound = true
}

export const broadcastPersistChange = (key: string) => {
  const ch = ensureChannel()
  if (ch) {
    try { ch.postMessage({ key, origin: TAB_ID } satisfies SyncMessage) } catch { /* closed */ }
  }
}

export const onPersistedChange = (fn: Listener): (() => void) => {
  ensureChannel()
  ensureStorageListener()
  listeners.add(fn)
  return () => listeners.delete(fn)
}

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
