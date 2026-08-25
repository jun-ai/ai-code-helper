/**
 * localStorage 安全读写封装：失败一律返回 null / 不抛异常。
 * localStorage 容量满 / 隐私模式禁用时 fallback 到内存 Map。
 */
const memoryFallback = new Map()
const KEY_PREFIX = 'aichat:'

export const safeGet = (key, fallback = null) => {
  try {
    const v = localStorage.getItem(key)
    if (v == null) {
      return memoryFallback.get(key) ?? fallback
    }
    return v
  } catch (_) {
    return memoryFallback.get(key) ?? fallback
  }
}

export const safeGetJSON = (key, fallback = null) => {
  const raw = safeGet(key)
  if (raw == null) return fallback
  try {
    return JSON.parse(raw)
  } catch (_) {
    return fallback
  }
}

export const safeSet = (key, value) => {
  try {
    localStorage.setItem(key, value)
    memoryFallback.set(key, value)
  } catch (_) {
    memoryFallback.set(key, value)
  }
}

export const safeRemove = (key) => {
  try { localStorage.removeItem(key) } catch (_) {}
  memoryFallback.delete(key)
}

export const listKeys = (prefix) => {
  const keys = []
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (k && k.startsWith(prefix)) keys.push(k)
    }
  } catch (_) {}
  return keys
}

export const KEY = {
  SESSIONS: `${KEY_PREFIX}sessions`,
  CURRENT_SESSION: `${KEY_PREFIX}currentSession`,
  MESSAGES: (sid) => `${KEY_PREFIX}messages:${sid}`,
  MEMORY_LEGACY: `${KEY_PREFIX}memoryId`,
  SETTINGS: `${KEY_PREFIX}settings`,
  THEME: `${KEY_PREFIX}theme`
}