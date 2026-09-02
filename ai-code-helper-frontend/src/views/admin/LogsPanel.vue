<template>
  <div class="logs-panel">
    <div class="toolbar">
      <n-space>
        <n-input v-model:value="filter" placeholder="关键词过滤（ERROR/OOM 等）" clearable style="width: 260px" />
        <n-select v-model:value="lines" :options="lineOptions" style="width: 120px" />
        <n-button @click="load" :loading="loading">刷新</n-button>
        <n-switch v-model:value="auto" /> <span class="auto-label">每 3s 自动刷新</span>
      </n-space>
      <span v-if="lastFetchedAt" class="time">最近拉取：{{ lastFetchedAt }}</span>
    </div>

    <n-card :bordered="false" size="small" class="log-card">
      <pre class="log-body" ref="bodyRef"><span
        v-for="(line, i) in displayLines"
        :key="i"
        :class="lineClass(line)"
      >{{ line }}</span></pre>
    </n-card>

    <p v-if="error" class="err">⚠️ {{ error }}</p>
  </div>
</template>

<script>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { NSpace, NInput, NSelect, NButton, NSwitch, NCard } from 'naive-ui'
import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081/api'

export default {
  name: 'LogsPanel',
  components: { NSpace, NInput, NSelect, NButton, NSwitch, NCard },
  setup() {
    const lines = ref(200)
    const filter = ref('')
    const auto = ref(true)
    const loading = ref(false)
    const error = ref('')
    const allLines = ref([])
    const lastFetchedAt = ref('')
    const bodyRef = ref(null)
    let timer = null
    let loadSeq = 0        // 请求序号：并发时只认最新一次，过期响应直接丢弃
    let filterTimer = null

    const lineOptions = [
      { label: '100 行', value: 100 },
      { label: '200 行', value: 200 },
      { label: '500 行', value: 500 },
      { label: '1000 行', value: 1000 }
    ]

    const displayLines = computed(() => {
      const f = filter.value.trim().toLowerCase()
      if (!f) return allLines.value
      return allLines.value.filter((l) => l.toLowerCase().includes(f))
    })

    function lineClass(line) {
      const lower = line.toLowerCase()
      if (lower.includes('error') || lower.includes('exception')) return 'log-error'
      if (lower.includes('warn')) return 'log-warn'
      if (lower.includes('debug')) return 'log-debug'
      return ''
    }

    async function load() {
      const seq = ++loadSeq
      loading.value = true
      error.value = ''
      try {
        const r = await axios.get(`${API_BASE_URL}/admin/logs`, {
          params: { lines: lines.value, filter: filter.value || undefined },
          timeout: 8000
        })
        if (seq !== loadSeq) return // 已有更新的请求，丢弃旧响应
        const payload = r.data
        allLines.value = (payload.lines && payload.lines.length) ? payload.lines : (payload.content ? payload.content.split('\n') : [])
        lastFetchedAt.value = new Date().toLocaleTimeString('zh-CN')
        await nextTick()
        if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
      } catch (e) {
        if (seq !== loadSeq) return
        error.value = (e.response && e.response.data && e.response.data.message) || e.message || '拉取失败'
      } finally {
        if (seq === loadSeq) loading.value = false
      }
    }

    function startTimer() {
      stopTimer()
      if (!auto.value) return
      timer = setInterval(load, 3000)
    }
    function stopTimer() {
      if (timer) { clearInterval(timer); timer = null }
    }

    watch(auto, (v) => { v ? startTimer() : stopTimer() })
    // 过滤词防抖 300ms，避免每个按键都打一次后端
    watch(filter, () => {
      clearTimeout(filterTimer)
      filterTimer = setTimeout(load, 300)
    })
    watch(lines, () => load())

    onMounted(() => { load(); startTimer() })
    onBeforeUnmount(() => {
      stopTimer()
      clearTimeout(filterTimer)
    })

    return { lines, filter, auto, loading, error, displayLines, lastFetchedAt, lineOptions, bodyRef, lineClass, load }
  }
}
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.auto-label { font-size: 13px; color: var(--color-text-muted); margin-left: 4px; }
.time { font-size: 12px; color: var(--color-text-faint); }

.log-card { padding: 0; }
.log-body {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  margin: 0;
  padding: 10px 12px;
  max-height: calc(100vh - 240px);
  min-height: 320px;
  overflow-y: auto;
  background: #0d1117;
  color: #e6edf3;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
}
.log-error { color: #ff7b72; }
.log-warn { color: #d29922; }
.log-debug { color: #8b949e; }

.err { color: var(--color-error); font-size: 13px; margin-top: 8px; }
</style>
