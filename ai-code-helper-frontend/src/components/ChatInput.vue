<template>
  <div
    class="chat-input"
    :class="{ 'drag-over': isDragging }"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent="onDragOver"
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <transition name="chip">
      <div v-if="uploadSummary" class="summary-chip" @click="askSummary">
        <span class="chip-icon">💡</span>
        <span class="chip-text">让 AI 总结刚刚上传的文档？</span>
        <button class="chip-close" @click.stop="$emit('dismiss-summary')" title="忽略">✕</button>
      </div>
    </transition>

    <div v-if="isDragging" class="drop-hint">
      <span class="drop-emoji">📥</span>
      <span>松开鼠标即可上传</span>
    </div>

    <div v-if="attachment" class="attachment-preview">
      <img v-if="isImage" :src="previewUrl" class="thumb" :alt="attachment.name" />
      <video v-else-if="isVideo" :src="previewUrl" class="thumb" muted />
      <div v-else class="file-icon-thumb">
        <span class="file-emoji">{{ fileEmoji }}</span>
      </div>
      <div class="attachment-meta">
        <span class="attachment-name">{{ attachment.name }}</span>
        <span class="attachment-size">{{ formattedSize }}</span>
        <span v-if="uploadStatus" class="attachment-status">{{ uploadStatus }}</span>
      </div>
      <button class="remove-btn" @click="clearAttachment" title="移除附件">✕</button>
    </div>

    <div class="input-container">
      <button
        class="attach-btn"
        :disabled="disabled"
        @click="openFilePicker"
        title="附件（图片 / 视频 / 文档），或拖入文件"
      >📎</button>
      <input
        ref="fileInputRef"
        type="file"
        class="file-input-hidden"
        accept="image/*,video/*,.pdf,.docx,.txt,.md"
        @change="onFileSelected"
      />
      <textarea
        ref="inputRef"
        v-model="inputMessage"
        :placeholder="placeholder"
        :disabled="disabled"
        class="input-textarea"
        rows="1"
        @keydown="handleKeyDown"
        @input="adjustHeight"
      />
      <button
        :disabled="disabled || (!inputMessage.trim() && !attachment)"
        @click="sendMessage"
        class="send-button"
        :title="inputMessage.trim() || attachment ? '发送 (Ctrl+Enter)' : '请输入内容'"
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M2 21l21-9L2 3v7l15 2-15 2v7z" fill="currentColor"/>
        </svg>
      </button>
    </div>
    <div class="hint-line">
      <span>Enter 发送 · Shift+Enter 换行 · Ctrl+Enter 强制发送 · 拖入文件快速上传</span>
    </div>
  </div>
</template>

<script>
export default {
  name: 'ChatInput',
  props: {
    disabled: {
      type: Boolean,
      default: false
    },
    placeholder: {
      type: String,
      default: '请输入您的问题...'
    },
    uploadSummary: {
      type: String,
      default: ''
    }
  },
  emits: ['send-message', 'dismiss-summary', 'ask-summary'],
  data() {
    return {
      inputMessage: '',
      attachment: null,
      previewUrl: '',
      uploadStatus: '',
      isDragging: false
    }
  },
  computed: {
    isImage() {
      return this.attachment && this.attachment.type.startsWith('image/')
    },
    isVideo() {
      return this.attachment && this.attachment.type.startsWith('video/')
    },
    fileEmoji() {
      if (!this.attachment) return '📄'
      const t = this.attachment.type
      if (t.includes('pdf')) return '📕'
      if (t.includes('word') || t.includes('document')) return '📘'
      if (t.startsWith('video/')) return '🎬'
      if (t.startsWith('image/')) return '🖼️'
      if (t.includes('text') || t.includes('markdown')) return '📝'
      return '📄'
    },
    formattedSize() {
      if (!this.attachment) return ''
      const kb = this.attachment.size / 1024
      if (kb < 1024) return `${kb.toFixed(1)} KB`
      return `${(kb / 1024).toFixed(2)} MB`
    }
  },
  beforeUnmount() {
    if (this.previewUrl) {
      URL.revokeObjectURL(this.previewUrl)
    }
  },
  methods: {
    openFilePicker() {
      if (this.disabled) return
      this.$refs.fileInputRef.click()
    },
    onFileSelected(event) {
      const file = event.target.files && event.target.files[0]
      if (!file) return
      this.acceptFile(file)
      event.target.value = ''
    },
    acceptFile(file) {
      if (file.size > 50 * 1024 * 1024) {
        alert('文件超过 50MB')
        return
      }
      this.attachment = file
      if (this.previewUrl) URL.revokeObjectURL(this.previewUrl)
      this.previewUrl = URL.createObjectURL(file)
      this.uploadStatus = ''
    },
    onDragEnter() { this.isDragging = true },
    onDragOver() { this.isDragging = true },
    onDragLeave(e) {
      if (e.target === e.currentTarget) this.isDragging = false
    },
    onDrop(event) {
      this.isDragging = false
      const file = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0]
      if (!file) return
      this.acceptFile(file)
    },
    clearAttachment() {
      if (this.previewUrl) URL.revokeObjectURL(this.previewUrl)
      this.previewUrl = ''
      this.attachment = null
      this.uploadStatus = ''
    },
    sendMessage() {
      const text = this.inputMessage.trim()
      if ((!text && !this.attachment) || this.disabled) return
      this.$emit('send-message', {
        message: text,
        attachment: this.attachment
      })
      this.inputMessage = ''
      this.clearAttachment()
      this.adjustHeight()
    },
    handleKeyDown(event) {
      if (event.key === 'Enter') {
        if (event.shiftKey) return
        event.preventDefault()
        this.sendMessage()
      }
    },
    askSummary() {
      const prompt = `请基于上传的文档总结展开讲讲：${this.uploadSummary}`
      this.$emit('ask-summary', prompt)
    },
    adjustHeight() {
      this.$nextTick(() => {
        const textarea = this.$refs.inputRef
        textarea.style.height = 'auto'
        textarea.style.height = Math.min(textarea.scrollHeight, 120) + 'px'
      })
    },
    focus() {
      this.$refs.inputRef.focus()
    },
    setUploadStatus(text) {
      this.uploadStatus = text
    }
  },
  mounted() {
    this.adjustHeight()
  }
}
</script>

<style scoped>
.chat-input {
  padding: 8px 20px 14px;
  background-color: white;
  border-top: 1px solid #e1e5e9;
  position: relative;
  transition: background-color 0.15s;
}
.chat-input.drag-over {
  background-color: #e8f0ff;
  border-top-color: #007bff;
}

.attachment-preview {
  max-width: 800px;
  margin: 0 auto 8px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background-color: #f1f3f4;
  border-radius: 12px;
  border: 1px solid #e1e5e9;
}

.attachment-preview .thumb {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  object-fit: cover;
  background: #000;
}

.file-icon-thumb {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  background-color: #fff;
  border: 1px solid #e1e5e9;
  display: flex;
  align-items: center;
  justify-content: center;
}

.file-emoji { font-size: 28px; }

.attachment-meta {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.attachment-name {
  font-size: 13px;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attachment-size { font-size: 11px; color: #888; }
.attachment-status { font-size: 11px; color: #0d6efd; }

.remove-btn {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: none;
  background: #fff;
  color: #666;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
}
.remove-btn:hover { background: #e03131; color: #fff; }

.input-container {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  max-width: 800px;
  margin: 0 auto;
}

.attach-btn {
  width: 44px;
  height: 44px;
  background-color: #f1f3f4;
  border: none;
  border-radius: 50%;
  font-size: 20px;
  cursor: pointer;
  flex-shrink: 0;
  transition: background-color 0.2s;
}
.attach-btn:hover:not(:disabled) { background-color: #e2e6ea; }
.attach-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.summary-chip {
  max-width: 800px;
  margin: 0 auto 8px;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 8px var(--space-3);
  background: var(--color-accent-soft);
  color: var(--color-accent);
  border-radius: var(--radius-pill);
  cursor: pointer;
  font-size: var(--font-sm);
  transition: background 0.15s;
}
.summary-chip:hover { background: var(--color-accent); color: white; }
.chip-icon { flex-shrink: 0; }
.chip-text { flex: 1; }
.chip-close {
  border: none; background: transparent; color: inherit;
  cursor: pointer; padding: 0 var(--space-1); border-radius: var(--radius-sm);
}
.chip-close:hover { background: rgba(0,0,0,0.08); }

.chip-enter-active, .chip-leave-active { transition: opacity 0.2s, transform 0.2s; }
.chip-enter-from, .chip-leave-to { opacity: 0; transform: translateY(4px); }

.drop-hint {
  max-width: 800px;
  margin: 0 auto 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px;
  border: 2px dashed #007bff;
  border-radius: 12px;
  color: #007bff;
  background: rgba(0, 123, 255, 0.05);
  font-size: 14px;
  pointer-events: none;
}
.drop-emoji { font-size: 18px; }

.file-input-hidden { display: none; }

.input-textarea {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #ddd;
  border-radius: 24px;
  font-size: 14px;
  line-height: 1.4;
  resize: none;
  outline: none;
  transition: border-color 0.2s;
  min-height: 44px;
  max-height: 120px;
  overflow-y: auto;
}
.input-textarea:focus { border-color: #007bff; }
.input-textarea:disabled { background-color: #f5f5f5; color: #999; cursor: not-allowed; }

.send-button {
  width: 44px;
  height: 44px;
  background-color: #007bff;
  border: none;
  border-radius: 50%;
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background-color 0.2s, transform 0.1s;
  flex-shrink: 0;
}
.send-button:hover:not(:disabled) { background-color: #0056b3; }
.send-button:active:not(:disabled) { transform: scale(0.94); }
.send-button:disabled { background-color: #ccc; cursor: not-allowed; }

.hint-line {
  max-width: 800px;
  margin: 4px auto 0;
  font-size: 11px;
  color: #999;
  text-align: center;
}

@media (max-width: 768px) {
  .chat-input { padding: 6px 15px 10px; }
  .input-container { gap: 6px; }
  .input-textarea { font-size: 16px; }
  .hint-line { display: none; }
}
</style>