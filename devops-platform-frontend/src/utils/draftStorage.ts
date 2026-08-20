const PREFIX = '__draft__:'

const safeGet = (key: string): string | null => {
  try {
    return sessionStorage.getItem(PREFIX + key)
  } catch {
    return null
  }
}

const safeSet = (key: string, value: string): boolean => {
  try {
    sessionStorage.setItem(PREFIX + key, value)
    return true
  } catch {
    return false
  }
}

const safeRemove = (key: string) => {
  try {
    sessionStorage.removeItem(PREFIX + key)
  } catch {
    /* noop */
  }
}

export interface DraftPayload<T> {
  value: T
  savedAt: number
}

export const loadDraft = <T>(key: string, maxAgeMs = 24 * 60 * 60 * 1000): T | null => {
  const raw = safeGet(key)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as DraftPayload<T>
    if (!parsed || typeof parsed !== 'object' || !('value' in parsed)) return null
    if (typeof parsed.savedAt === 'number' && Date.now() - parsed.savedAt > maxAgeMs) {
      safeRemove(key)
      return null
    }
    return parsed.value
  } catch {
    safeRemove(key)
    return null
  }
}

export const saveDraft = <T>(key: string, value: T): boolean => {
  try {
    const payload: DraftPayload<T> = { value, savedAt: Date.now() }
    return safeSet(key, JSON.stringify(payload))
  } catch {
    return false
  }
}

export const clearDraft = (key: string) => safeRemove(key)
