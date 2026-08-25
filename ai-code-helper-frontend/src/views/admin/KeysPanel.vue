<template>
  <div class="keys-panel">
    <n-alert type="info" :show-icon="false" style="margin-bottom: 16px">
      当前配置显示的是脱敏后的 key。<b>轮换 Admin Key</b>会立即生成新 key 并写入
      <code>application-local.yml</code>，旧 key 即刻失效。当前 session 也需用新 key 重新登录后台。
    </n-alert>

    <div class="card-grid">
      <n-card title="API Key（X-API-Key）" :bordered="false">
        <div class="key-display">
          <code>{{ data.apiKeyMasked || '—' }}</code>
        </div>
        <p class="hint">用户聊天面 / 上传 / RAG 检索使用。</p>
      </n-card>

      <n-card title="Admin Key（X-Admin-Key）" :bordered="false">
        <div class="key-display">
          <code>{{ data.adminKeyMasked || '—' }}</code>
        </div>
        <n-space style="margin-top: 12px">
          <n-button type="warning" :loading="rotating" @click="confirmRotate">
            ⟳ 轮换 Admin Key
          </n-button>
        </n-space>
        <p class="hint">仅后台管理使用。</p>
      </n-card>
    </div>

    <n-modal v-model:show="showNew" preset="card" title="新 Admin Key（仅显示一次）" style="width: 520px">
      <p class="new-key-hint">⚠️ 请立即复制保存，刷新或离开后将不可见。</p>
      <div class="new-key-box">
        <code>{{ newKey }}</code>
        <n-button @click="copyNew">复制</n-button>
      </div>
      <template #footer>
        <n-button type="primary" @click="showNew = false">我已保存</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script>
import { ref, reactive, onMounted } from 'vue'
import { NAlert, NCard, NSpace, NButton, NModal, useDialog, useMessage } from 'naive-ui'
import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081/api'

export default {
  name: 'KeysPanel',
  components: { NAlert, NCard, NSpace, NButton, NModal },
  setup() {
    const data = reactive({ apiKeyMasked: '', adminKeyMasked: '' })
    const rotating = ref(false)
    const showNew = ref(false)
    const newKey = ref('')
    const dialog = useDialog()
    const message = useMessage()

    async function load() {
      try {
        const r = await axios.get(`${API_BASE_URL}/admin/keys`, { timeout: 5000 })
        data.apiKeyMasked = r.data.apiKeyMasked || ''
        data.adminKeyMasked = r.data.adminKeyMasked || ''
      } catch (e) {
        message.error('加载失败: ' + (e.message || '未知错误'))
      }
    }

    function confirmRotate() {
      dialog.warning({
        title: '轮换 Admin Key',
        content: '将立即生成新的 Admin Key 并写入配置文件，旧 key 立即失效。是否继续？',
        positiveText: '继续',
        negativeText: '取消',
        onPositiveClick: doRotate
      })
    }

    async function doRotate() {
      rotating.value = true
      try {
        const r = await axios.post(`${API_BASE_URL}/admin/keys/rotate`, null, { timeout: 10000 })
        newKey.value = r.data.adminKey || r.data.value || ''
        showNew.value = true
        await load()
      } catch (e) {
        const msg = (e.response && e.response.data && e.response.data.message) || e.message || '轮换失败'
        message.error(msg)
      } finally {
        rotating.value = false
      }
    }

    async function copyNew() {
      try {
        await navigator.clipboard.writeText(newKey.value)
        message.success('已复制')
      } catch (_) {
        message.warning('浏览器拒绝复制，请手动选中复制')
      }
    }

    onMounted(load)
    return { data, rotating, showNew, newKey, confirmRotate, copyNew }
  }
}
</script>

<style scoped>
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 12px;
}
.key-display {
  background: var(--color-bg);
  padding: 10px 12px;
  border-radius: 6px;
  border: 1px solid var(--color-border);
  font-size: 13px;
  word-break: break-all;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
}
.hint { font-size: 12px; color: var(--color-text-faint); margin-top: 8px; }

.new-key-hint { color: var(--color-warn); font-size: 13px; margin: 0 0 8px; }
.new-key-box {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--color-bg);
  padding: 10px 12px;
  border-radius: 6px;
  border: 1px solid var(--color-border);
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
  word-break: break-all;
}
.new-key-box code { flex: 1; }
</style>
