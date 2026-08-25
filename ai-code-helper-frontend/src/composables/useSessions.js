import { ref, computed } from 'vue'
import { safeGet, safeGetJSON, safeSet, safeRemove, KEY } from './storage.js'
import { generateMemoryId } from '../utils/index.js'

const SESSIONS_KEY = KEY.SESSIONS
const CURRENT_KEY = KEY.CURRENT_SESSION

/** Session = { id, title, createdAt, updatedAt, preview, memoryId } */
const sessions = ref(safeGetJSON(SESSIONS_KEY, []) ?? [])
const currentSessionId = ref(safeGet(CURRENT_KEY, null))

const persistSessions = () => safeSet(SESSIONS_KEY, JSON.stringify(sessions.value))
const persistCurrent = () => {
  if (currentSessionId.value) {
    safeSet(CURRENT_KEY, currentSessionId.value)
  } else {
    safeRemove(CURRENT_KEY)
  }
}

const newId = () => `s_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

export function useSessions() {
  const ensureCurrent = () => {
    if (currentSessionId.value && sessions.value.some((s) => s.id === currentSessionId.value)) {
      return currentSessionId.value
    }
    const id = newId()
    const now = Date.now()
    sessions.value = [
      { id, title: '新会话', createdAt: now, updatedAt: now, preview: '', memoryId: generateMemoryId() },
      ...sessions.value
    ]
    currentSessionId.value = id
    persistSessions()
    persistCurrent()
    return id
  }

  const createSession = (initTitle = '新会话') => {
    const id = newId()
    const now = Date.now()
    sessions.value = [
      { id, title: initTitle, createdAt: now, updatedAt: now, preview: '', memoryId: generateMemoryId() },
      ...sessions.value
    ]
    currentSessionId.value = id
    persistSessions()
    persistCurrent()
    return id
  }

  const switchSession = (id) => {
    if (!sessions.value.some((s) => s.id === id)) return false
    currentSessionId.value = id
    persistCurrent()
    return true
  }

  const deleteSession = (id) => {
    sessions.value = sessions.value.filter((s) => s.id !== id)
    safeRemove(KEY.MESSAGES(id))
    if (currentSessionId.value === id) {
      currentSessionId.value = sessions.value[0]?.id ?? null
      persistCurrent()
    }
    persistSessions()
  }

  const renameSession = (id, title) => {
    const s = sessions.value.find((s) => s.id === id)
    if (s) {
      s.title = title || '新会话'
      persistSessions()
    }
  }

  const updateSession = (id, patch) => {
    const s = sessions.value.find((s) => s.id === id)
    if (s) {
      Object.assign(s, patch, { updatedAt: Date.now() })
      persistSessions()
    }
  }

  const exportSession = (id) => {
    const s = sessions.value.find((s) => s.id === id)
    if (!s) return
    const messages = safeGetJSON(KEY.MESSAGES(id), []) ?? []
    const blob = new Blob(
      [JSON.stringify({ session: s, messages }, null, 2)],
      { type: 'application/json' }
    )
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `aichat-${s.title.replace(/[\\/:*?"<>|]/g, '_')}-${id}.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  const cleanupOldSessions = () => {
    const SEVEN_DAYS = 7 * 24 * 60 * 60 * 1000
    const now = Date.now()
    const kept = []
    for (const s of sessions.value) {
      if (now - (s.updatedAt || s.createdAt) <= SEVEN_DAYS) {
        kept.push(s)
      } else {
        safeRemove(KEY.MESSAGES(s.id))
      }
    }
    if (kept.length !== sessions.value.length) {
      sessions.value = kept
      persistSessions()
    }
  }

  const grouped = computed(() => {
    const now = new Date()
    const startOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
    const startOfYesterday = startOfDay - 24 * 60 * 60 * 1000
    const startOfWeek = startOfDay - 7 * 24 * 60 * 60 * 1000
    const today = [], yesterday = [], earlier = []
    for (const s of [...sessions.value].sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))) {
      const t = s.updatedAt || s.createdAt || 0
      if (t >= startOfDay) today.push(s)
      else if (t >= startOfYesterday) yesterday.push(s)
      else if (t >= startOfWeek) earlier.push(s)
      else earlier.push(s) // 一周以前也归到「更早」分组
    }
    return { 今天: today, 昨天: yesterday, 更早: earlier }
  })

  return {
    sessions,
    currentSessionId,
    grouped,
    ensureCurrent,
    createSession,
    switchSession,
    deleteSession,
    renameSession,
    updateSession,
    exportSession,
    cleanupOldSessions
  }
}