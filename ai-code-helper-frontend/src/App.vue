<template>
  <div class="app">
    <div class="app-header">
      <h1 class="app-title">AI 编程小助手</h1>
      <div class="app-subtitle">帮助您解答编程学习和求职面试相关问题</div>
      <button v-if="hasHistory" class="clear-btn" @click="clearChat" title="清空对话">🗑️ 清空</button>
    </div>

    <div class="chat-container">
      <div class="messages-container" ref="messagesContainer">
        <div v-if="messages.length === 0 && !streamingDraft" class="welcome-message">
          <div class="welcome-content">
            <div class="welcome-icon">🤖</div>
            <h2>欢迎使用 AI 编程小助手</h2>
            <p>我可以帮助您：</p>
            <ul>
              <li>解答编程技术问题</li>
              <li>提供代码示例和解释</li>
              <li>协助求职面试准备</li>
              <li>分享编程学习建议</li>
            </ul>
            <p>请随时向我提问吧！</p>
          </div>
        </div>

        <!-- 已完成的历史消息 -->
        <ChatMessage
          v-for="message in messages"
          :key="message.id"
          :message="message.content"
          :is-user="message.isUser"
          :timestamp="message.timestamp"
          :status="message.status"
          :attachment="message.attachment"
          @retry="retryMessage(message)"
        />

        <!-- 流式期独立气泡：每 \n\n 闭合段落渲染 Markdown，最后未闭合段保留纯文本+光标 -->
        <div v-if="streamingDraft" class="chat-message ai-message">
          <div class="message-avatar">
            <div class="avatar ai-avatar">AI</div>
          </div>
          <div class="message-content">
            <div class="message-bubble streaming-bubble">
              <template v-if="streamingDraft.status === 'streaming'">
                <template v-if="streamingDraft.retrying">
                  <div class="retry-indicator">
                    <span class="retry-dot"></span>
                    连接中断，正在重试 ({{ streamingDraft.retryAttempt }}/3)...
                  </div>
                </template>
                <template v-if="streamingDraft.completedSegments.length">
                  <div
                    v-for="(seg, i) in streamingDraft.completedSegments"
                    :key="'seg-' + i"
                    class="message-markdown stream-segment"
                    v-html="renderSegment(seg)"
                  ></div>
                </template>
                <pre
                  v-if="streamingDraft.content"
                  class="message-text streaming-text"
                  v-text="streamingDraft.content"
                ></pre>
                <span class="streaming-cursor">▍</span>
              </template>
              <template v-else-if="streamingDraft.status === 'error'">
                <div class="error-bubble">
                  <span class="error-icon-inline">⚠️</span>
                  <span class="error-text">{{ streamingDraft.error || '生成失败' }}</span>
                </div>
              </template>
            </div>
            <div class="message-footer">
              <span class="message-time">{{ streamingDraft.status === 'streaming' ? '正在输入...' : '已中断' }}</span>
              <div class="message-actions">
                <button
                  v-if="streamingDraft.status === 'streaming'"
                  class="action-btn stop-btn"
                  @click="stopGeneration"
                  title="停止生成"
                >⏹ 停止</button>
                <button
                  v-else-if="streamingDraft.status === 'error'"
                  class="action-btn retry-btn"
                  @click="retryLastStream"
                  title="重新生成"
                >⟳ 重试</button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <ChatInput
        ref="chatInputRef"
        :disabled="!!streamingDraft"
        @send-message="sendMessage"
        placeholder="请输入您的编程问题..."
      />
    </div>

    <div v-if="connectionError" class="connection-error">
      <div class="error-content">
        <span class="error-icon">⚠️</span>
        <span>连接服务器失败，请检查后端服务是否启动</span>
      </div>
    </div>
  </div>
</template>

<script>
import ChatMessage from './components/ChatMessage.vue'
import ChatInput from './components/ChatInput.vue'
import LoadingDots from './components/LoadingDots.vue'
import { chatWithSSE, chatWithFile, uploadFile } from './api/chatApi.js'
import { generateMemoryId } from './utils/index.js'
import { renderMarkdown } from './utils/markdown.js'

const STORAGE_KEY_MEMORY = 'aichat:memoryId'
const messagesStorageKey = (memoryId) => `aichat:messages:${memoryId}`

export default {
  name: 'App',
  components: {
    ChatMessage,
    ChatInput,
    LoadingDots
  },
  data() {
    return {
      messages: [],
      memoryId: null,
      streamingDraft: null,
      abortHandle: null,
      connectionError: false,
      // 用户是否向上滚动了；true 时不再强制跟随
      userScrolledUp: false
    }
  },
  computed: {
    hasHistory() {
      return this.messages.length > 0 || !!this.streamingDraft
    }
  },
  watch: {
    messages: {
      deep: true,
      handler() {
        this.persistMessages()
      }
    },
    memoryId(newVal) {
      if (newVal != null) {
        try {
          localStorage.setItem(STORAGE_KEY_MEMORY, String(newVal))
        } catch (_) {
          // 忽略存储失败
        }
      }
    }
  },
  mounted() {
    this.cleanupOldSessions()
    this.initializeChat()
    // 监听滚动：距离底部 > 60px 视为用户已上翻，停止强制跟随
    this.$nextTick(() => {
      const container = this.$refs.messagesContainer
      if (container) {
        container.addEventListener('scroll', this.handleScroll)
      }
    })
  },
  beforeUnmount() {
    if (this.abortHandle && this.abortHandle.close) {
      this.abortHandle.close()
    }
    const container = this.$refs.messagesContainer
    if (container) {
      container.removeEventListener('scroll', this.handleScroll)
    }
  },
  methods: {
    initializeChat() {
      const savedId = localStorage.getItem(STORAGE_KEY_MEMORY)
      if (savedId && /^\d{1,10}$/.test(savedId)) {
        this.memoryId = Number(savedId)
        this.loadMessages()
      } else {
        this.memoryId = generateMemoryId()
        try {
          localStorage.setItem(STORAGE_KEY_MEMORY, String(this.memoryId))
        } catch (_) {
          // 忽略
        }
      }
    },
    loadMessages() {
      try {
        const raw = localStorage.getItem(messagesStorageKey(this.memoryId))
        if (!raw) {
          return
        }
        const arr = JSON.parse(raw)
        if (Array.isArray(arr)) {
          this.messages = arr.map((m) => ({
            id: m.id,
            content: m.content,
            isUser: !!m.isUser,
            timestamp: new Date(m.timestamp),
            status: m.status || 'done',
            attachment: m.attachment || null
          }))
          this.$nextTick(this.scrollToBottom)
        }
      } catch (_) {
        // 忽略
      }
    },
    persistMessages() {
      if (this.memoryId == null) {
        return
      }
      try {
        // 仅持久化消息内容；流式期不算历史
        const serializable = this.messages.map((m) => ({
          id: m.id,
          content: m.content,
          isUser: m.isUser,
          timestamp: m.timestamp instanceof Date ? m.timestamp.toISOString() : m.timestamp,
          status: m.status,
          attachment: m.attachment || null
        }))
        localStorage.setItem(messagesStorageKey(this.memoryId), JSON.stringify(serializable))
      } catch (_) {
        // 忽略
      }
    },
    sendMessage(payload) {
      // payload = { message: string, attachment?: File }
      const { message, attachment } = payload
      const id = Date.now() + Math.random()
      const userMsg = {
        id,
        content: message || '',
        isUser: true,
        timestamp: new Date(),
        status: 'done',
        attachment: null
      }
      // 先把附件传到 /upload，拿到 URL 后再展示给用户看（同时让 RAG 自动入库）
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
        this.messages.push(userMsg)
        this.scrollToBottom()
        // 异步上传，不阻塞用户问下一个问题（这里要先拿到 URL 给 AI 看）
        uploadFile(attachment)
          .then((res) => {
            const target = this.messages.find((m) => m.id === id)
            if (target && target.attachment) {
              target.attachment.remoteUrl = res.url
              target.attachment.indexed = !!res.indexed
              target.attachment.chunks = res.chunks || 0
              // 释放本地预览 blob URL，改用后端 URL
              URL.revokeObjectURL(localUrl)
            }
          })
          .catch((err) => {
            console.error('上传失败', err)
            const target = this.messages.find((m) => m.id === id)
            if (target && target.attachment) {
              target.attachment.uploadError = err.message || '上传失败'
            }
          })
      } else {
        this.messages.push(userMsg)
        this.scrollToBottom()
      }
      this.startStream({ message, attachment })
    },
    startStream(payload) {
      // payload = { message: string, attachment?: File }
      // 启动前确保老连接已关
      if (this.abortHandle) {
        this.abortHandle.close()
        this.abortHandle = null
      }
      this.streamingDraft = {
        content: '',
        completedSegments: [],
        sentMessage: payload.message || '',
        attachment: payload.attachment || null,
        status: 'streaming',
        retrying: false,
        retryAttempt: 0
      }
      this.userScrolledUp = false
      this.connectionError = false
      const streamFn = payload.attachment ? chatWithFile : chatWithSSE
      this.abortHandle = streamFn(
        this.memoryId,
        payload.message || '',
        payload.attachment || null,
        this.handleChunk,
        this.handleError,
        this.handleClose,
        this.handleStreamRetry
      )
    },
    handleStreamRetry() {
      // chatApi 触发新一轮连接：清空已收到的片段避免重复展示
      if (!this.streamingDraft) return
      this.streamingDraft.content = ''
      this.streamingDraft.completedSegments = []
      this.streamingDraft.retrying = true
      this.streamingDraft.retryAttempt = (this.streamingDraft.retryAttempt || 0) + 1
      this.scrollToBottom()
    },
    handleChunk(chunk) {
      if (!this.streamingDraft || this.streamingDraft.status !== 'streaming') {
        return
      }
      // 首批 chunk 到达说明重连成功，清掉 retrying 指示
      if (this.streamingDraft.retrying) {
        this.streamingDraft.retrying = false
      }
      this.streamingDraft.content += chunk
      // 已闭合段落（以 \n\n 结尾）切到 completedSegments，由模板按 Markdown 渲染
      let sepIndex
      // eslint-disable-next-line no-constant-condition
      while (true) {
        sepIndex = this.streamingDraft.content.indexOf('\n\n')
        if (sepIndex < 0) {
          break
        }
        const segment = this.streamingDraft.content.slice(0, sepIndex)
        this.streamingDraft.completedSegments.push(segment)
        this.streamingDraft.content = this.streamingDraft.content.slice(sepIndex + 2)
      }
      this.scrollToBottom()
    },
    handleError(error) {
      // 已有流式气泡 → 切到错误态，由前端 ⟳ 重试
      if (this.streamingDraft) {
        this.streamingDraft.status = 'error'
        this.streamingDraft.error = (error && error.message) || '生成失败'
        this.abortHandle = null
      } else {
        this.connectionError = true
        setTimeout(() => {
          this.connectionError = false
        }, 5000)
      }
    },
    handleClose() {
      if (this.streamingDraft && this.streamingDraft.status === 'streaming') {
        // 拼接完整内容 = 已闭合段落 + 当前未闭合段
        const fullContent = [
          ...this.streamingDraft.completedSegments,
          this.streamingDraft.content
        ]
          .filter((s) => s.trim().length > 0)
          .join('\n\n')
          .trim()
        if (fullContent) {
          this.messages.push({
            id: Date.now() + Math.random(),
            content: fullContent,
            isUser: false,
            timestamp: new Date(),
            status: 'done'
          })
        }
      }
      this.streamingDraft = null
      this.abortHandle = null
      this.scrollToBottom()
    },
    renderSegment(segment) {
      // 流式期按段渲染：与 ChatMessage 共享 markdown.js（marked + DOMPurify + hljs 一次性配置）
      return renderMarkdown(segment)
    },
    stopGeneration() {
      if (this.abortHandle && this.abortHandle.close) {
        this.abortHandle.close()
      }
    },
    retryLastStream() {
      if (this.streamingDraft) {
        const sent = this.streamingDraft.sentMessage
        const attachment = this.streamingDraft.attachment
        this.streamingDraft = null
        this.startStream({ message: sent, attachment })
      }
    },
    retryMessage(message) {
      // 找到该 AI 消息前最近一条 user 消息来重生成
      const idx = this.messages.findIndex((m) => m.id === message.id)
      if (idx < 1) {
        return
      }
      let userContent = null
      for (let i = idx - 1; i >= 0; i--) {
        if (this.messages[i].isUser) {
          userContent = this.messages[i].content
          break
        }
      }
      if (userContent == null) {
        return
      }
      // 删掉当前 AI 消息（失败的或想换结果的）
      // 注意：附件是 File 对象，刷新后丢失，重试只能重发文本
      this.messages.splice(idx, 1)
      this.startStream({ message: userContent, attachment: null })
    },
    clearChat() {
      if (this.abortHandle && this.abortHandle.close) {
        this.abortHandle.close()
      }
      this.streamingDraft = null
      this.abortHandle = null
      this.messages = []
    },
    scrollToBottom() {
      // 贴底跟随：用户已上翻时不再强制拉回，避免打断阅读
      if (this.userScrolledUp) {
        return
      }
      this.$nextTick(() => {
        const container = this.$refs.messagesContainer
        if (container) {
          container.scrollTop = container.scrollHeight
        }
      })
    },
    handleScroll() {
      const container = this.$refs.messagesContainer
      if (!container) {
        return
      }
      // 距底部 > 60px 视为上翻；用户滚回贴底后恢复自动跟随
      const distanceFromBottom = container.scrollHeight - container.scrollTop - container.clientHeight
      this.userScrolledUp = distanceFromBottom > 60
    },
    cleanupOldSessions() {
      // 清理 7 天前的本地会话（按消息最新时间戳判断）
      const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000
      const now = Date.now()
      const keysToRemove = []
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i)
        if (!key || !key.startsWith('aichat:messages:')) {
          continue
        }
        try {
          const arr = JSON.parse(localStorage.getItem(key) || '[]')
          if (!Array.isArray(arr) || arr.length === 0) {
            keysToRemove.push(key)
            continue
          }
          // 取最新一条消息的时间
          const latest = arr.reduce((max, m) => {
            const t = m.timestamp ? new Date(m.timestamp).getTime() : 0
            return t > max ? t : max
          }, 0)
          if (latest === 0 || now - latest > SEVEN_DAYS_MS) {
            keysToRemove.push(key)
          }
        } catch (_) {
          // 解析失败的也清掉
          keysToRemove.push(key)
        }
      }
      keysToRemove.forEach((k) => localStorage.removeItem(k))
      // 顺带清掉指向已删会话的 memoryId（防止加载到空记忆体）
      const currentMemoryId = localStorage.getItem(STORAGE_KEY_MEMORY)
      if (currentMemoryId && keysToRemove.includes(messagesStorageKey(currentMemoryId))) {
        localStorage.removeItem(STORAGE_KEY_MEMORY)
      }
    }
  }
}
</script>

<style scoped>
.app {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: #f0f0f0;
}

.app-header {
  background-color: #fff;
  padding: 20px;
  border-bottom: 1px solid #e1e5e9;
  text-align: center;
  position: relative;
}

.app-title {
  font-size: 24px;
  font-weight: bold;
  color: #333;
  margin: 0;
}

.app-subtitle {
  font-size: 14px;
  color: #666;
  margin-top: 5px;
}

.clear-btn {
  position: absolute;
  top: 50%;
  right: 20px;
  transform: translateY(-50%);
  background: none;
  border: 1px solid #ddd;
  border-radius: 4px;
  padding: 4px 10px;
  font-size: 12px;
  color: #666;
  cursor: pointer;
  transition: all 0.15s;
}

.clear-btn:hover {
  background-color: #f8d7da;
  border-color: #f5c2c7;
  color: #842029;
}

.chat-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 20px 0;
}

.welcome-message {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  padding: 0 20px;
}

.welcome-content {
  text-align: center;
  max-width: 400px;
  color: #666;
}

.welcome-icon {
  font-size: 48px;
  margin-bottom: 20px;
}

.welcome-content h2 {
  font-size: 20px;
  margin-bottom: 15px;
  color: #333;
}

.welcome-content p {
  margin-bottom: 10px;
  line-height: 1.5;
}

.welcome-content ul {
  text-align: left;
  margin: 15px 0;
}

.welcome-content li {
  margin-bottom: 5px;
}

.chat-message {
  display: flex;
  margin-bottom: 20px;
  padding: 0 20px;
}

.ai-message {
  justify-content: flex-start;
  flex-direction: row;
}

.message-avatar {
  display: flex;
  align-items: flex-start;
  margin: 0 10px;
}

.avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: bold;
  color: white;
}

.ai-avatar {
  background-color: #6c757d;
}

.message-content {
  max-width: 70%;
  min-width: 100px;
}

.message-bubble {
  padding: 12px 16px;
  border-radius: 18px;
  position: relative;
  word-wrap: break-word;
  word-break: break-word;
  background-color: #f1f3f4;
  color: #333;
  border-bottom-left-radius: 4px;
}

.message-text {
  font-family: inherit;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
}

.streaming-bubble {
  display: flex;
  flex-direction: column;
  min-height: 32px;
}

.streaming-text {
  margin: 0;
  display: inline;
}

.streaming-cursor {
  display: inline-block;
  width: 8px;
  color: #6c757d;
  animation: blink 1s step-start infinite;
  margin-left: 2px;
}

.retry-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #856404;
  padding: 4px 0;
}

.retry-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #ffc107;
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.error-bubble {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #842029;
}

.error-icon-inline {
  font-size: 16px;
}

.error-text {
  font-size: 14px;
}

.message-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  padding: 0 4px;
}

.message-time {
  font-size: 12px;
  color: #888;
}

.message-actions {
  display: flex;
  gap: 4px;
}

.action-btn {
  background: none;
  border: 1px solid transparent;
  border-radius: 4px;
  padding: 2px 8px;
  cursor: pointer;
  font-size: 12px;
  color: #888;
  transition: all 0.15s;
}

.action-btn:hover {
  background-color: rgba(0, 0, 0, 0.05);
  color: #333;
}

.action-btn.stop-btn {
  color: #d33;
}

.action-btn.retry-btn {
  color: #0d6efd;
}

.connection-error {
  position: fixed;
  top: 20px;
  left: 50%;
  transform: translateX(-50%);
  background-color: #ff4444;
  color: white;
  padding: 10px 20px;
  border-radius: 5px;
  z-index: 1000;
  animation: slideDown 0.3s ease-out;
}

.error-content {
  display: flex;
  align-items: center;
  gap: 8px;
}

.error-icon {
  font-size: 16px;
}

@keyframes slideDown {
  from {
    transform: translateX(-50%) translateY(-100%);
    opacity: 0;
  }
  to {
    transform: translateX(-50%) translateY(0);
    opacity: 1;
  }
}

.messages-container::-webkit-scrollbar {
  width: 6px;
}

.messages-container::-webkit-scrollbar-track {
  background: #f1f1f1;
}

.messages-container::-webkit-scrollbar-thumb {
  background: #c1c1c1;
  border-radius: 3px;
}

.messages-container::-webkit-scrollbar-thumb:hover {
  background: #a8a8a8;
}

@media (max-width: 768px) {
  .app-header {
    padding: 15px;
  }
  .app-title {
    font-size: 20px;
  }
  .messages-container {
    padding: 15px 0;
  }
  .welcome-content {
    padding: 0 10px;
  }
  .message-content {
    max-width: 85%;
  }
  .chat-message {
    padding: 0 10px;
  }
  .clear-btn {
    right: 10px;
  }
}
</style>
