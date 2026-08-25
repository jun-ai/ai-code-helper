<template>
  <div class="chat-message" :class="{ 'user-message': isUser, 'ai-message': !isUser }">
    <div class="message-avatar">
      <div class="avatar" :class="{ 'user-avatar': isUser, 'ai-avatar': !isUser }">
        {{ isUser ? '我' : 'AI' }}
      </div>
    </div>
    <div class="message-content">
      <div class="message-bubble">
        <!-- 用户消息：附件缩略图 + 文本 -->
        <template v-if="isUser">
          <div v-if="attachment" class="user-attachment">
            <a
              v-if="attachment.remoteUrl || attachment.localUrl"
              :href="attachment.remoteUrl || attachment.localUrl"
              target="_blank"
              rel="noopener"
              class="attachment-thumb-link"
            >
              <img
                v-if="isImageAttachment"
                :src="attachment.remoteUrl || attachment.localUrl"
                class="attachment-thumb"
                :alt="attachment.name"
              />
              <video
                v-else-if="isVideoAttachment"
                :src="attachment.remoteUrl || attachment.localUrl"
                class="attachment-thumb"
                muted
              />
              <div v-else class="attachment-icon">
                <span class="attachment-emoji">{{ attachmentEmoji }}</span>
              </div>
            </a>
            <div v-else-if="isImageAttachment" class="attachment-thumb-placeholder">🖼️</div>
            <div class="attachment-info">
              <span class="attachment-name" :title="attachment.name">{{ attachment.name }}</span>
              <span class="attachment-meta">
                {{ formattedSize }}
                <span v-if="attachment.indexed" class="indexed-tag">已入知识库</span>
                <span v-else-if="attachment.remoteUrl" class="uploaded-tag">已上传</span>
                <span v-else class="uploading-tag">上传中…</span>
                <span v-if="attachment.uploadError" class="upload-error">{{ attachment.uploadError }}</span>
              </span>
            </div>
          </div>
          <pre v-if="message" class="message-text">{{ message }}</pre>
        </template>
        <!-- AI回复：拆分来源 + 内容；来源末尾渲染为 chip，避免与正文堆在一起 -->
        <template v-else>
          <div class="message-markdown" v-html="renderedContent"></div>
          <div v-if="sources.length" class="source-chips">
            <span
              v-for="(src, idx) in sources"
              :key="idx"
              class="source-chip"
              :title="src.file"
            >
              📄 {{ src.file }}
              <span v-if="src.title" class="source-chip-title">· {{ src.title }}</span>
            </span>
          </div>
        </template>
      </div>
      <div class="message-footer">
        <div class="message-time">{{ formatTime(timestamp) }}</div>
        <!-- AI 消息支持复制 + 失败重试 -->
        <div v-if="!isUser && message" class="message-actions">
          <button class="action-btn" @click="copyMessage" title="复制">📋</button>
          <button v-if="status === 'error'" class="action-btn retry-btn" @click="$emit('retry')" title="重新生成">⟳</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { formatTime } from '../utils/index.js'
import { renderMarkdown } from '../utils/markdown.js'

// 匹配 "【来源：文件名 - 标题】" 或 "【来源：文件名】"；文件名/标题可含中文、字母、数字、点
const SOURCE_PATTERN = /【来源：([^】]+?)】/g

export default {
  name: 'ChatMessage',
  props: {
    message: {
      type: String,
      required: true
    },
    isUser: {
      type: Boolean,
      default: false
    },
    timestamp: {
      type: Date,
      default: () => new Date()
    },
    status: {
      type: String,
      default: 'done' // done / error / streaming
    },
    attachment: {
      type: Object,
      default: null
      // { name, size, mimeType, localUrl, remoteUrl, indexed, chunks, uploadError }
    }
  },
  emits: ['retry'],
  computed: {
    isImageAttachment() {
      return this.attachment && this.attachment.mimeType && this.attachment.mimeType.startsWith('image/')
    },
    isVideoAttachment() {
      return this.attachment && this.attachment.mimeType && this.attachment.mimeType.startsWith('video/')
    },
    attachmentEmoji() {
      if (!this.attachment) return '📄'
      const t = this.attachment.mimeType || ''
      if (t.includes('pdf')) return '📕'
      if (t.includes('word') || t.includes('document')) return '📘'
      if (t.startsWith('video/')) return '🎬'
      if (t.startsWith('image/')) return '🖼️'
      if (t.includes('text') || t.includes('markdown')) return '📝'
      return '📄'
    },
    formattedSize() {
      if (!this.attachment || !this.attachment.size) return ''
      const kb = this.attachment.size / 1024
      if (kb < 1024) return `${kb.toFixed(1)} KB`
      return `${(kb / 1024).toFixed(2)} MB`
    },
    sources() {
      if (this.isUser) return []
      const seen = new Set()
      const result = []
      let match
      SOURCE_PATTERN.lastIndex = 0
      while ((match = SOURCE_PATTERN.exec(this.message)) !== null) {
        const raw = match[1].trim()
        // 形如 "Java 编程学习路线.md - 学习建议" 或 "Java 编程学习路线.md"
        const dashIdx = raw.indexOf(' - ')
        const file = (dashIdx >= 0 ? raw.slice(0, dashIdx) : raw).trim()
        const title = dashIdx >= 0 ? raw.slice(dashIdx + 3).trim() : ''
        const key = `${file}::${title}`
        if (!seen.has(key)) {
          seen.add(key)
          result.push({ file, title })
        }
      }
      return result
    },
    contentWithoutSources() {
      if (this.isUser) return this.message
      // 把【来源：xxx】及其之间的空白从正文里剔除
      return this.message.replace(SOURCE_PATTERN, '').replace(/(\n\s*){2,}$/g, '').trim()
    },
    renderedContent() {
      if (this.isUser) return ''
      return renderMarkdown(this.contentWithoutSources)
    }
  },
  methods: {
    formatTime,
    async copyMessage() {
      try {
        await navigator.clipboard.writeText(this.message)
      } catch (e) {
        // 降级用 textarea + execCommand
        const ta = document.createElement('textarea')
        ta.value = this.message
        document.body.appendChild(ta)
        ta.select()
        document.execCommand('copy')
        document.body.removeChild(ta)
      }
    }
  }
}
</script>

<style scoped>
.chat-message {
  display: flex;
  margin-bottom: 20px;
  padding: 0 20px;
}

.user-message {
  justify-content: flex-end;
  flex-direction: row;
}

.user-message .message-avatar {
  order: 2;
}

.user-message .message-content {
  order: 1;
}

.ai-message {
  justify-content: flex-start;
  flex-direction: row;
}

.ai-message .message-avatar {
  order: 1;
}

.ai-message .message-content {
  order: 2;
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

.user-avatar {
  background-color: #007bff;
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
}

.user-message .message-bubble {
  background-color: #007bff;
  color: white;
  border-bottom-right-radius: 4px;
}

.ai-message .message-bubble {
  background-color: #f1f3f4;
  color: #333;
  border-bottom-left-radius: 4px;
}

.message-text {
  font-family: inherit;
  font-size: 14px;
  line-height: 1.4;
  white-space: pre-wrap;
  margin: 0;
}

/* Markdown样式 */
.message-markdown {
  font-family: inherit;
  font-size: 14px;
  line-height: 1.5;
}

.message-markdown h1,
.message-markdown h2,
.message-markdown h3,
.message-markdown h4,
.message-markdown h5,
.message-markdown h6 {
  margin: 0.5em 0;
  font-weight: bold;
}

.message-markdown h1 { font-size: 1.5em; }
.message-markdown h2 { font-size: 1.3em; }
.message-markdown h3 { font-size: 1.2em; }
.message-markdown h4 { font-size: 1.1em; }
.message-markdown h5 { font-size: 1em; }
.message-markdown h6 { font-size: 0.9em; }

.message-markdown p {
  margin: 0.5em 0;
}

.message-markdown ul,
.message-markdown ol {
  margin: 0.5em 0;
  padding-left: 1.5em;
}

.message-markdown li {
  margin: 0.2em 0;
}

.message-markdown code {
  background-color: rgba(0, 0, 0, 0.1);
  padding: 0.2em 0.4em;
  border-radius: 3px;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 0.9em;
}

.user-message .message-markdown code {
  background-color: rgba(255, 255, 255, 0.2);
}

.message-markdown pre {
  background-color: rgba(0, 0, 0, 0.1);
  padding: 1em;
  border-radius: 5px;
  overflow-x: auto;
  margin: 0.5em 0;
}

.user-message .message-markdown pre {
  background-color: rgba(255, 255, 255, 0.2);
}

.message-markdown pre code {
  background-color: transparent;
  padding: 0;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 0.9em;
}

.message-markdown blockquote {
  border-left: 4px solid #ccc;
  padding-left: 1em;
  margin: 0.5em 0;
  font-style: italic;
  color: #666;
}

.user-message .message-markdown blockquote {
  border-left-color: rgba(255, 255, 255, 0.5);
  color: rgba(255, 255, 255, 0.8);
}

.message-markdown a {
  color: #007bff;
  text-decoration: underline;
}

.user-message .message-markdown a {
  color: #b3d9ff;
}

.message-markdown table {
  border-collapse: collapse;
  width: 100%;
  margin: 0.5em 0;
}

.message-markdown th,
.message-markdown td {
  border: 1px solid #ddd;
  padding: 0.5em;
  text-align: left;
}

.message-markdown th {
  background-color: #f2f2f2;
  font-weight: bold;
}

.user-message .message-markdown th {
  background-color: rgba(255, 255, 255, 0.2);
}

.message-markdown hr {
  border: none;
  border-top: 1px solid #ddd;
  margin: 1em 0;
}

.user-message .message-markdown hr {
  border-top-color: rgba(255, 255, 255, 0.3);
}

.message-time {
  font-size: 12px;
  color: #666;
  margin-top: 4px;
  padding: 0 4px;
}

.user-message .message-time {
  text-align: right;
}

.ai-message .message-time {
  text-align: left;
}

.message-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  padding: 0 4px;
}

.user-message .message-footer {
  justify-content: flex-end;
}

.message-actions {
  display: flex;
  gap: 4px;
  opacity: 0;
  transition: opacity 0.2s;
}

.chat-message:hover .message-actions {
  opacity: 1;
}

.action-btn {
  background: none;
  border: 1px solid transparent;
  border-radius: 4px;
  padding: 2px 6px;
  cursor: pointer;
  font-size: 14px;
  color: #888;
  transition: all 0.15s;
}

.action-btn:hover {
  background-color: rgba(0, 0, 0, 0.05);
  color: #333;
}

.action-btn.retry-btn {
  color: #d33;
}

.source-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed rgba(0, 0, 0, 0.08);
}

.source-chip {
  display: inline-flex;
  align-items: center;
  padding: 3px 9px;
  font-size: 12px;
  background-color: rgba(108, 117, 125, 0.1);
  color: #495057;
  border-radius: 11px;
  border: 1px solid rgba(108, 117, 125, 0.2);
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-chip-title {
  color: #6c757d;
  margin-left: 4px;
}

@media (max-width: 768px) {
  .message-content {
    max-width: 85%;
  }

  .chat-message {
    padding: 0 10px;
  }
}

.user-attachment {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px dashed rgba(255, 255, 255, 0.25);
}

.attachment-thumb-link {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.attachment-thumb {
  width: 64px;
  height: 64px;
  border-radius: 8px;
  object-fit: cover;
  background: rgba(0, 0, 0, 0.2);
}

.attachment-icon {
  width: 64px;
  height: 64px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;
}

.attachment-emoji {
  font-size: 32px;
}

.attachment-thumb-placeholder {
  width: 64px;
  height: 64px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  flex-shrink: 0;
}

.attachment-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.attachment-name {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.95);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.attachment-meta {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.7);
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  align-items: center;
  margin-top: 2px;
}

.indexed-tag,
.uploaded-tag {
  background: rgba(40, 167, 69, 0.25);
  color: #d4edda;
  padding: 1px 6px;
  border-radius: 8px;
  font-size: 10px;
}

.uploading-tag {
  background: rgba(255, 193, 7, 0.25);
  color: #fff3cd;
  padding: 1px 6px;
  border-radius: 8px;
  font-size: 10px;
}

.upload-error {
  color: #ffcccc;
  font-size: 10px;
}
</style> 