<template>
  <div class="metrics-panel">
    <div class="toolbar">
      <n-space>
        <n-button @click="load" :loading="loading">手动刷新</n-button>
        <n-switch v-model:value="auto" /> <span class="auto-label">每 5s 自动刷新</span>
      </n-space>
      <span v-if="lastFetchedAt" class="time">最近拉取：{{ lastFetchedAt }}</span>
    </div>

    <div class="cards">
      <div v-for="c in cards" :key="c.label" class="card">
        <div class="card-label">{{ c.label }}</div>
        <div class="card-value" :class="c.tone">{{ c.value }}</div>
        <div class="card-hint">{{ c.hint }}</div>
      </div>
    </div>

    <n-card title="原始 /actuator/prometheus（节选）" :bordered="false" size="small">
      <pre class="raw">{{ rawSnippet || (loading ? '加载中…' : '暂无数据') }}</pre>
    </n-card>

    <p v-if="error" class="err">⚠️ {{ error }}</p>
  </div>
</template>

<script>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { NSpace, NButton, NSwitch, NCard } from 'naive-ui'
import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081/api'

export default {
  name: 'MetricsPanel',
  components: { NSpace, NButton, NSwitch, NCard },
  setup() {
    const raw = ref('')
    const loading = ref(false)
    const auto = ref(true)
    const lastFetchedAt = ref('')
    const error = ref('')
    let timer = null

    // 关注的指标（Micrometer → Prometheus 命名：. 转 _，Counter 加 _total）
    const WANTED = [
      { key: 'jvm_memory_used_bytes', labels: ['area="heap"'], label: 'JVM Heap 用量', hint: '字节' },
      { key: 'process_cpu_usage', labels: [], label: '进程 CPU 使用率', hint: '0~1' },
      { key: 'chat_requests_total', labels: [], label: '聊天请求总数', hint: 'Prometheus Counter' },
      { key: 'chat_errors_total', labels: [], label: '聊天失败次数', hint: 'Prometheus Counter' },
      { key: 'rag_retrieves_total', labels: [], label: 'RAG 检索次数', hint: 'Prometheus Counter' },
      { key: 'rag_fallback_total', labels: [], label: 'RAG 兜底次数', hint: '空结果或异常' }
    ]

    function findMetric(text, key, labels = []) {
      if (!text) return null
      const lines = text.split('\n')
      for (const line of lines) {
        if (!line || line.startsWith('#')) continue
        if (!line.startsWith(key)) continue
        if (labels.length === 0) {
          // 抓第一个匹配（无 label 或任意 label）
          const m = line.match(/^[a-zA-Z_:][a-zA-Z0-9_:]+\s+([0-9.eE+\-]+)/)
          if (m) return Number(m[1])
        } else {
          if (labels.every((l) => line.includes(l))) {
            const m = line.match(/^[a-zA-Z_:][a-zA-Z0-9_:]*\{[^}]*\}\s+([0-9.eE+\-]+)/)
            if (m) return Number(m[1])
          }
        }
      }
      return null
    }

    function formatNum(n) {
      if (n == null) return '—'
      if (Math.abs(n) >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(2)} MB`
      if (Math.abs(n) >= 1024) return `${(n / 1024).toFixed(1)} KB`
      if (Math.abs(n) < 1 && n !== 0) return n.toFixed(4)
      return String(Math.round(n))
    }

    const cards = computed(() => {
      return WANTED.map((w) => {
        const v = findMetric(raw.value, w.key, w.labels)
        let tone = ''
        if (w.key === 'chat_request_errors_total' && v > 0) tone = 'warn'
        if (w.key === 'rag_retrieve_fallback_total' && v > 10) tone = 'warn'
        return { label: w.label, value: formatNum(v), hint: w.hint, tone }
      })
    })

    const rawSnippet = computed(() => {
      if (!raw.value) return ''
      const lines = raw.value.split('\n').filter((l) => l && !l.startsWith('#help') && !l.startsWith('# TYPE') && !l.startsWith('# EOF'))
      // 只保留与 wanted 相关 + jvm/proc 关键行
      const keep = lines.filter((l) => WANTED.some((w) => l.startsWith(w.key)))
      const out = keep.slice(0, 60).join('\n')
      return out.length > 2000 ? out.slice(0, 2000) + '\n…(截断)' : out
    })

    async function load() {
      loading.value = true
      error.value = ''
      try {
        const r = await axios.get(`${API_BASE_URL.replace(/\/api$/, '')}/actuator/prometheus`, { timeout: 8000 })
        raw.value = r.data
        lastFetchedAt.value = new Date().toLocaleTimeString('zh-CN')
      } catch (e) {
        error.value = (e.response && e.response.data && e.response.data.message) || e.message || '拉取失败'
      } finally {
        loading.value = false
      }
    }

    function startTimer() {
      stopTimer()
      if (!auto.value) return
      timer = setInterval(load, 5000)
    }
    function stopTimer() {
      if (timer) { clearInterval(timer); timer = null }
    }

    watch(auto, (v) => {
      if (v) startTimer()
      else stopTimer()
    })

    onMounted(() => { load(); startTimer() })
    onBeforeUnmount(stopTimer)

    return { loading, auto, error, lastFetchedAt, cards, rawSnippet, load }
  }
}
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.auto-label { font-size: 13px; color: var(--color-text-muted); margin-left: 4px; }
.time { font-size: 12px; color: var(--color-text-faint); }

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}
.card {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 14px;
  background: var(--color-bg-elev);
}
.card-label { font-size: 12px; color: var(--color-text-muted); }
.card-value { font-size: 24px; font-weight: 600; margin: 6px 0 2px; }
.card-value.warn { color: var(--color-warn); }
.card-hint { font-size: 11px; color: var(--color-text-faint); }

.raw {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  max-height: 280px;
  overflow-y: auto;
  background: var(--color-bg);
  padding: 10px;
  border-radius: 6px;
}
.err { color: var(--color-error); font-size: 13px; }
</style>
