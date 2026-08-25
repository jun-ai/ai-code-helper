import { ref, computed, watch } from 'vue'
import { chatWithSSE, chatWithFile, uploadFile } from '../api/chatApi.js'
import { renderMarkdown } from '../utils/markdown.js'
import { safeGetJSON, safeSet, KEY } from './storage.js'

/**
 * useChat：消息 / 流式 / 附件 / 重试 全部封装在一个 composable 里。
 * 切换 session 时外部传入新 sessionId，状态机复位但保留各 session 历史。
 */
export function useChat(getSessionId, getMemoryId, onSessionUpdate) {
  const messages = ref([])
  const streamingDraft = ref(null)
  const abortHandle = ref(null)
  const connectionError = ref(false)
  const pendingSummary = ref('')

  const loadMessages = (sid) => {
    const raw = safeGetJSON(KEY.MESSAGES(sid), [])
    if (Array.isArray(raw)) {
      messages.value = raw.map((m) => ({
        id: m.id,
        content: m.content,
        isUser: !!m.isUser,
        timestamp: new Date(m.timestamp),
        status: m.status || 'done',
        attachment: m.attachment || null
      }))
    } else {
      messages.value = []
    }
  }

  watch(getSessionId, (sid) => {
    if (!sid) {
      messages.value = []
      return
    }
    if (abortHandle.value) {
      abortHandle.value.close()
      abortHandle.value = null
      streamingDraft.value = null
    }
    loadMessages(sid)
  }, { immediate: true })

  watch(messages, () => {
    const sid = getSessionId()
    if (!sid) return
    safeSet(KEY.MESSAGES(sid), JSON.stringify(messages.value.map((m) => ({
      id: m.id,
      content: m.content,
      isUser: m.isUser,
      timestamp: m.timestamp instanceof Date ? m.timestamp.toISOString() : m.timestamp,
      status: m.status,
      attachment: m.attachment || null
    }))))
    if (onSessionUpdate) {
      const first = messages.value.find((m) => m.isUser)
      const preview = (first?.content || '').slice(0, 24)
      onSessionUpdate({ preview })
    }
  }, { deep: true })

  const persistCurrentSession = () => {
    const sid = getSessionId()
    if (!sid) return
    safeSet(KEY.MESSAGES(sid), JSON.stringify(messages.value.map((m) => ({
      id: m.id,
      content: m.content,
      isUser: m.isUser,
      timestamp: m.timestamp instanceof Date ? m.timestamp.toISOString() : m.timestamp,
      status: m.status,
      attachment: m.attachment || null
    }))))
  }

  const sendMessage = async ({ message, attachment }) => {
    const id = Date.now() + Math.random()
    const userMsg = {
      id,
      content: message || '',
      isUser: true,
      timestamp: new Date(),
      status: 'done',
      attachment: null
    }
    let pendingAttachment = attachment
    if (attachment) {
      const localUrl = URL.createObjectURL(attachment)
      userMsg.attachment = {
        name: attachment.name,
        size: attachment.size,
        mimeType: attachment.type,
        localUrl,
        remoteUrl: null,
        indexed: false,
        chunks: 0
      }
      messages.value.push(userMsg)
      // 异步上传，不阻塞流式起跑
      uploadFile(attachment).then((res) => {
        const target = messages.value.find((m) => m.id === id)
        if (target && target.attachment) {
          target.attachment.remoteUrl = res.url
          target.attachment.indexed = !!res.indexed
          target.attachment.chunks = res.chunks || 0
          URL.revokeObjectURL(localUrl)
          // 文档类上传成功 → 显示「让 AI 总结」chip
          if (res.summary) {
            pendingSummary.value = res.summary
          }
        }
      }).catch((err) => {
        const target = messages.value.find((m) => m.id === id)
        if (target && target.attachment) {
          target.attachment.uploadError = err.message || '上传失败'
        }
      })
    } else {
      messages.value.push(userMsg)
    }

    if (abortHandle.value) {
      abortHandle.value.close()
      abortHandle.value = null
    }
    streamingDraft.value = {
      content: '',
      completedSegments: [],
      sentMessage: message || '',
      attachment: pendingAttachment || null,
      status: 'streaming',
      retrying: false,
      retryAttempt: 0
    }
    connectionError.value = false

    // chatWithSSE 和 chatWithFile 签名不同：前者无 attachment 参数；后者 attachment 占第三位。
    // 用同一个 streamFn 调会让 SSE 路径把 null 当 onMessage 传，触发 onMessage is not a function。
    if (pendingAttachment) {
      abortHandle.value = chatWithFile(
        getMemoryId(),
        message || '',
        pendingAttachment,
        handleChunk,
        handleError,
        handleClose,
        handleStreamRetry
      )
    } else {
      abortHandle.value = chatWithSSE(
        getMemoryId(),
        message || '',
        handleChunk,
        handleError,
        handleClose,
        handleStreamRetry
      )
    }
  }

  const handleStreamRetry = () => {
    if (!streamingDraft.value) return
    streamingDraft.value.content = ''
    streamingDraft.value.completedSegments = []
    streamingDraft.value.retrying = true
    streamingDraft.value.retryAttempt = (streamingDraft.value.retryAttempt || 0) + 1
  }

  const handleChunk = (chunk) => {
    if (!streamingDraft.value || streamingDraft.value.status !== 'streaming') return
    if (streamingDraft.value.retrying) streamingDraft.value.retrying = false
    streamingDraft.value.content += chunk
    let sepIndex
    while (true) {
      sepIndex = streamingDraft.value.content.indexOf('\n\n')
      if (sepIndex < 0) break
      const segment = streamingDraft.value.content.slice(0, sepIndex)
      streamingDraft.value.completedSegments.push(segment)
      streamingDraft.value.content = streamingDraft.value.content.slice(sepIndex + 2)
    }
  }

  const handleError = (err) => {
    if (streamingDraft.value) {
      streamingDraft.value.status = 'error'
      streamingDraft.value.error = (err && err.message) || '生成失败'
      abortHandle.value = null
    } else {
      connectionError.value = true
      setTimeout(() => { connectionError.value = false }, 5000)
    }
  }

  const handleClose = () => {
    if (streamingDraft.value && streamingDraft.value.status === 'streaming') {
      const fullContent = [
        ...streamingDraft.value.completedSegments,
        streamingDraft.value.content
      ].filter((s) => s.trim().length > 0).join('\n\n').trim()
      if (fullContent) {
        messages.value.push({
          id: Date.now() + Math.random(),
          content: fullContent,
          isUser: false,
          timestamp: new Date(),
          status: 'done'
        })
      }
    }
    streamingDraft.value = null
    abortHandle.value = null
  }

  const stopGeneration = () => {
    if (abortHandle.value) abortHandle.value.close()
  }

  const retryLastStream = () => {
    if (!streamingDraft.value) return
    const sent = streamingDraft.value.sentMessage
    const attachment = streamingDraft.value.attachment
    streamingDraft.value = null
    sendMessage({ message: sent, attachment })
  }

  const retryMessage = (msg) => {
    const idx = messages.value.findIndex((m) => m.id === msg.id)
    if (idx < 1) return
    let userContent = null
    for (let i = idx - 1; i >= 0; i--) {
      if (messages.value[i].isUser) {
        userContent = messages.value[i].content
        break
      }
    }
    if (userContent == null) return
    messages.value.splice(idx, 1)
    sendMessage({ message: userContent, attachment: null })
  }

  const clearChat = () => {
    if (abortHandle.value) abortHandle.value.close()
    streamingDraft.value = null
    abortHandle.value = null
    messages.value = []
  }

  const hasHistory = computed(() => messages.value.length > 0 || !!streamingDraft.value)
  const renderSegment = (seg) => renderMarkdown(seg)

  const dismissSummary = () => { pendingSummary.value = '' }
  const askSummary = (text) => {
    pendingSummary.value = ''
    sendMessage({ message: text, attachment: null })
  }

  return {
    messages,
    streamingDraft,
    connectionError,
    pendingSummary,
    hasHistory,
    sendMessage,
    stopGeneration,
    retryLastStream,
    retryMessage,
    clearChat,
    persistCurrentSession,
    renderSegment,
    dismissSummary,
    askSummary
  }
}