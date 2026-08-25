<template>
  <n-config-provider :theme="null">
    <n-message-provider>
      <n-notification-provider>
        <div class="app-shell" :data-theme="theme">
          <aside class="sidebar">
            <div class="sidebar-header">
              <button class="new-chat-btn" @click="handleNewSession">
                <n-icon size="18"><AddOutline /></n-icon>
                <span>新建会话</span>
              </button>
            </div>
            <nav class="session-list">
              <template v-for="(items, group) in grouped" :key="group">
                <div v-if="items.length" class="session-group">
                  <div class="group-title">{{ group }}</div>
                  <div
                    v-for="s in items"
                    :key="s.id"
                    class="session-item"
                    :class="{ active: s.id === currentSessionId }"
                    @click="handleSwitch(s.id)"
                  >
                    <n-icon size="14" class="session-icon">
                      <ChatbubblesOutline />
                    </n-icon>
                    <div class="session-meta">
                      <div class="session-title">{{ s.title || '新会话' }}</div>
                      <div class="session-preview">{{ s.preview || '还没有消息' }}</div>
                    </div>
                    <button class="del-btn" title="删除" @click.stop="handleDelete(s.id)">
                      <n-icon size="14"><TrashOutline /></n-icon>
                    </button>
                  </div>
                </div>
              </template>
            </nav>
            <div class="sidebar-footer">
              <button class="footer-btn" @click="toggleTheme" :title="theme === 'light' ? '切换深色' : '切换浅色'">
                <n-icon size="16">
                  <SunnyOutline v-if="theme === 'light'" />
                  <MoonOutline v-else />
                </n-icon>
              </button>
              <button class="footer-btn" @click="settingsVisible = true" title="设置">
                <n-icon size="16"><SettingsOutline /></n-icon>
              </button>
            </div>
          </aside>

          <main class="chat-main">
            <header class="chat-header">
              <div class="brand">
                <n-icon size="22" class="brand-icon"><SparklesOutline /></n-icon>
                <span class="brand-title">AI 编程小助手</span>
              </div>
              <div class="chat-subtitle">帮助您解答编程学习和求职面试相关问题</div>
              <button v-if="hasHistory" class="clear-btn" @click="chat.clearChat" title="清空当前会话">清空</button>
            </header>

            <div class="messages-container" ref="messagesContainerRef">
              <div v-if="!hasHistory" class="welcome">
                <div class="welcome-card">
                  <div class="welcome-emoji">🤖</div>
                  <h2>欢迎使用 AI 编程小助手</h2>
                  <p class="welcome-desc">我可以帮您：</p>
                  <ul>
                    <li>解答编程技术问题</li>
                    <li>提供代码示例和解释</li>
                    <li>协助求职面试准备</li>
                    <li>分享编程学习建议</li>
                  </ul>
                  <p>随时向我提问吧！</p>
                </div>
              </div>

              <ChatMessage
                v-for="message in chat.messages.value"
                :key="message.id"
                :message="message.content"
                :is-user="message.isUser"
                :timestamp="message.timestamp"
                :status="message.status"
                :attachment="message.attachment"
                @retry="chat.retryMessage(message)"
              />

              <div v-if="chat.streamingDraft.value" class="chat-message ai-message">
                <div class="message-avatar">
                  <div class="avatar ai-avatar">AI</div>
                </div>
                <div class="message-content">
                  <div class="message-bubble streaming-bubble">
                    <template v-if="chat.streamingDraft.value.status === 'streaming'">
                      <div v-if="chat.streamingDraft.value.retrying" class="retry-indicator">
                        <span class="retry-dot"></span>
                        连接中断，正在重试 ({{ chat.streamingDraft.value.retryAttempt }}/3)...
                      </div>
                      <div
                        v-for="(seg, i) in chat.streamingDraft.value.completedSegments"
                        :key="'seg-' + i"
                        class="message-markdown stream-segment"
                        v-html="chat.renderSegment(seg)"
                      ></div>
                      <pre
                        v-if="chat.streamingDraft.value.content"
                        class="message-text streaming-text"
                        v-text="chat.streamingDraft.value.content"
                      ></pre>
                      <span class="streaming-cursor">▍</span>
                    </template>
                    <template v-else-if="chat.streamingDraft.value.status === 'error'">
                      <div class="error-bubble">
                        <span class="error-icon-inline">⚠️</span>
                        <span class="error-text">{{ chat.streamingDraft.value.error || '生成失败' }}</span>
                      </div>
                    </template>
                  </div>
                  <div class="message-footer">
                    <span class="message-time">
                      {{ chat.streamingDraft.value.status === 'streaming' ? '正在输入...' : '已中断' }}
                    </span>
                    <div class="message-actions">
                      <button
                        v-if="chat.streamingDraft.value.status === 'streaming'"
                        class="action-btn stop-btn"
                        @click="chat.stopGeneration"
                      >⏹ 停止</button>
                      <button
                        v-else-if="chat.streamingDraft.value.status === 'error'"
                        class="action-btn retry-btn"
                        @click="chat.retryLastStream"
                      >⟳ 重试</button>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <ChatInput
              ref="chatInputRef"
              :disabled="!!chat.streamingDraft.value"
              @send-message="onSend"
              placeholder="请输入您的编程问题..."
            />
          </main>

          <SettingsDrawer v-model:show="settingsVisible" />

          <transition name="fade">
            <div v-if="chat.connectionError.value" class="connection-error">
              <span>⚠️</span>
              <span>连接服务器失败，请检查后端服务是否启动</span>
            </div>
          </transition>
        </div>
      </n-notification-provider>
    </n-message-provider>
  </n-config-provider>
</template>

<script>
import { computed, ref, onMounted, nextTick } from 'vue'
import {
  NConfigProvider, NMessageProvider, NNotificationProvider, NIcon,
  createDiscreteApi
} from 'naive-ui'
import {
  AddOutline, ChatbubblesOutline, TrashOutline,
  SunnyOutline, MoonOutline, SettingsOutline, SparklesOutline
} from '@vicons/ionicons5'
import ChatMessage from './components/ChatMessage.vue'
import ChatInput from './components/ChatInput.vue'
import SettingsDrawer from './components/SettingsDrawer.vue'
import { useTheme } from './composables/useTheme.js'
import { useSessions } from './composables/useSessions.js'
import { useChat } from './composables/useChat.js'
import { useScrollFollow } from './composables/useScrollFollow.js'

export default {
  name: 'App',
  components: {
    NConfigProvider, NMessageProvider, NNotificationProvider, NIcon,
    AddOutline, ChatbubblesOutline, TrashOutline,
    SunnyOutline, MoonOutline, SettingsOutline, SparklesOutline,
    ChatMessage, ChatInput, SettingsDrawer
  },
  setup() {
    const { theme, toggle: toggleTheme } = useTheme()
    const sessionStore = useSessions()
    const { currentSessionId, grouped, ensureCurrent, createSession, switchSession, deleteSession, updateSession, cleanupOldSessions } = sessionStore

    const messagesContainerRef = ref(null)
    const { scrollToBottom } = useScrollFollow(messagesContainerRef)

    const getSid = () => currentSessionId.value
    const getMid = () => {
      const s = sessionStore.sessions.value.find((s) => s.id === currentSessionId.value)
      return s ? s.memoryId : 0
    }
    const onSessionUpdate = (patch) => {
      if (!currentSessionId.value) return
      updateSession(currentSessionId.value, patch)
    }

    const chat = useChat(getSid, getMid, onSessionUpdate)

    const settingsVisible = ref(false)

    const handleNewSession = () => {
      ensureCurrent() // 若当前为空则建一个；先 abort 旧流
      const id = createSession('新会话')
      switchSession(id)
    }

    const handleSwitch = (id) => {
      if (chat.abortHandle.value) chat.abortHandle.value.close()
      switchSession(id)
      nextTick(scrollToBottom)
    }

    const handleDelete = (id) => {
      deleteSession(id)
    }

    const onSend = (payload) => {
      const msg = (payload.message || '').trim()
      chat.sendMessage({ message: msg, attachment: payload.attachment })
      // 自动命名：取首条 user 消息前 24 字
      if (msg && currentSessionId.value) {
        const s = sessionStore.sessions.value.find((s) => s.id === currentSessionId.value)
        if (s && (s.title === '新会话' || !s.title)) {
          updateSession(currentSessionId.value, { title: msg.slice(0, 24) })
        }
      }
      nextTick(() => scrollToBottom(true))
    }

    const hasHistory = computed(() => chat.hasHistory.value)

    onMounted(() => {
      cleanupOldSessions()
      ensureCurrent()
      nextTick(() => scrollToBottom(true))
    })

    return {
      theme, toggleTheme,
      ...sessionStore,
      grouped,
      chat,
      settingsVisible,
      handleNewSession,
      handleSwitch,
      handleDelete,
      onSend,
      hasHistory,
      messagesContainerRef
    }
  }
}
</script>

<style scoped>
.app-shell {
  height: 100vh;
  display: grid;
  grid-template-columns: var(--sidebar-width) 1fr;
  background: var(--color-bg);
  color: var(--color-text);
}

.sidebar {
  display: flex;
  flex-direction: column;
  background: var(--color-bg-sidebar);
  border-right: 1px solid var(--color-border);
  overflow: hidden;
}

.sidebar-header { padding: var(--space-4); }
.new-chat-btn {
  width: 100%;
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: 10px var(--space-3);
  background: var(--color-accent);
  color: white;
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: var(--font-md);
  transition: background 0.15s;
}
.new-chat-btn:hover { background: var(--color-accent-hover); }

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 var(--space-2) var(--space-2);
}
.session-group { margin-bottom: var(--space-4); }
.group-title {
  font-size: var(--font-xs);
  color: var(--color-text-faint);
  padding: var(--space-2) var(--space-3);
  letter-spacing: 0.05em;
}
.session-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 8px var(--space-3);
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: var(--font-md);
  color: var(--color-text);
  transition: background 0.1s;
  position: relative;
}
.session-item:hover { background: var(--color-bg-hover); }
.session-item.active { background: var(--color-accent-soft); color: var(--color-accent); }
.session-icon { flex-shrink: 0; }
.session-meta { flex: 1; min-width: 0; }
.session-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}
.session-preview {
  font-size: var(--font-xs);
  color: var(--color-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.del-btn {
  border: none;
  background: transparent;
  color: var(--color-text-faint);
  cursor: pointer;
  padding: 4px;
  border-radius: var(--radius-sm);
  opacity: 0;
  transition: opacity 0.1s;
}
.session-item:hover .del-btn { opacity: 1; }
.del-btn:hover { color: var(--color-error); background: var(--color-bg-hover); }

.sidebar-footer {
  display: flex;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--color-border);
}
.footer-btn {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 8px;
  background: transparent;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-text-muted);
  cursor: pointer;
  transition: all 0.15s;
}
.footer-btn:hover { background: var(--color-bg-hover); color: var(--color-text); }

.chat-main {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.chat-header {
  padding: var(--space-4) var(--space-6);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-elev);
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: var(--space-2);
}
.brand { display: flex; align-items: center; gap: var(--space-2); }
.brand-icon { color: var(--color-accent); }
.brand-title { font-size: var(--font-lg); font-weight: 600; }
.chat-subtitle {
  grid-column: 1 / 2;
  font-size: var(--font-sm);
  color: var(--color-text-muted);
}
.clear-btn {
  grid-column: 2 / 3;
  grid-row: 1 / 3;
  align-self: center;
  border: 1px solid var(--color-border);
  background: transparent;
  padding: 6px var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--font-xs);
  color: var(--color-text-muted);
  cursor: pointer;
}
.clear-btn:hover { color: var(--color-error); border-color: var(--color-error); }

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-5) 0;
  background: var(--color-bg);
}

.welcome { display: flex; justify-content: center; padding: 40px var(--space-5); }
.welcome-card {
  max-width: 420px;
  background: var(--color-bg-elev);
  padding: var(--space-6);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-sm);
  text-align: center;
}
.welcome-emoji { font-size: 48px; margin-bottom: var(--space-4); }
.welcome-card h2 { font-size: var(--font-xl); margin-bottom: var(--space-3); }
.welcome-desc { color: var(--color-text-muted); margin-bottom: var(--space-2); }
.welcome-card ul {
  text-align: left;
  margin: var(--space-3) 0;
  padding-left: var(--space-5);
  color: var(--color-text-muted);
}
.welcome-card li { margin: 4px 0; }

.chat-message {
  display: flex;
  margin-bottom: var(--space-5);
  padding: 0 var(--space-5);
}
.ai-message { justify-content: flex-start; flex-direction: row; }

.message-avatar { display: flex; align-items: flex-start; margin: 0 10px; }
.avatar {
  width: 36px; height: 36px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: bold; color: white;
}
.ai-avatar { background: var(--color-text-faint); }

.message-content { max-width: 70%; min-width: 100px; }
.message-bubble {
  padding: 12px 16px;
  border-radius: 14px;
  position: relative;
  word-wrap: break-word;
  word-break: break-word;
  background: var(--color-ai-bubble);
  color: var(--color-ai-bubble-text);
  border-bottom-left-radius: 4px;
}

.message-text { font-family: inherit; font-size: var(--font-md); line-height: 1.5; white-space: pre-wrap; margin: 0; }

.streaming-bubble { display: flex; flex-direction: column; min-height: 32px; }
.streaming-text { display: inline; }
.streaming-cursor {
  display: inline-block;
  width: 8px;
  color: var(--color-text-faint);
  animation: blink 1s step-start infinite;
}

.retry-indicator {
  display: flex; align-items: center; gap: 6px;
  font-size: var(--font-xs); color: var(--color-warn);
}
.retry-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--color-warn);
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse { 0%, 100% { opacity: 0.4; } 50% { opacity: 1; } }
@keyframes blink { 50% { opacity: 0; } }

.error-bubble {
  display: flex; align-items: center; gap: 8px;
  color: var(--color-error);
}

.message-footer {
  display: flex; align-items: center; gap: var(--space-2);
  margin-top: 4px; padding: 0 4px;
}
.message-time { font-size: var(--font-xs); color: var(--color-text-faint); }
.message-actions { display: flex; gap: 4px; }
.action-btn {
  background: none;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  padding: 2px 8px;
  cursor: pointer;
  font-size: var(--font-xs);
  color: var(--color-text-muted);
}
.action-btn:hover { background: var(--color-bg-hover); color: var(--color-text); }

.connection-error {
  position: fixed; top: 20px; left: 50%;
  transform: translateX(-50%);
  background: var(--color-error); color: white;
  padding: 10px var(--space-5);
  border-radius: var(--radius-md);
  z-index: 1000;
  display: flex; align-items: center; gap: 8px;
}
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

@media (max-width: 768px) {
  .app-shell { grid-template-columns: 56px 1fr; }
  .sidebar { width: 56px; }
  .session-list, .new-chat-btn span, .group-title, .session-meta { display: none; }
  .new-chat-btn { justify-content: center; padding: 10px; }
  .message-content { max-width: 85%; }
  .chat-message { padding: 0 var(--space-3); }
}
</style>