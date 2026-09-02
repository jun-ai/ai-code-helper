<template>
  <div class="login-shell">
    <n-card class="login-card" title="🔐 管理后台登录" :bordered="false">
      <p class="hint">请输入 Admin Key（独立管理后台的 <code>X-Admin-Key</code>）</p>
      <n-form size="medium">
        <n-form-item label="Admin Key">
          <n-input
            v-model:value="adminKey"
            type="password"
            show-password-on="click"
            placeholder="从 application.yml / APP_ADMIN_KEY 获取"
            @keyup.enter="submit"
          />
        </n-form-item>
      </n-form>
      <n-space vertical>
        <n-button type="primary" block :loading="testing" @click="submit">登录后台</n-button>
        <n-button block secondary @click="$router.push('/')">返回用户面</n-button>
      </n-space>
      <p v-if="error" class="err">{{ error }}</p>
    </n-card>
  </div>
</template>

<script>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NCard, NForm, NFormItem, NInput, NButton, NSpace } from 'naive-ui'
import axios from 'axios'
import { safeSet, safeGet, KEY } from '../composables/storage.js'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081/api'

export default {
  name: 'AdminLogin',
  components: { NCard, NForm, NFormItem, NInput, NButton, NSpace },
  setup() {
    const route = useRoute()
    const router = useRouter()
    const adminKey = ref(safeGet(KEY.ADMIN_KEY, '') || '')
    const testing = ref(false)
    const error = ref('')

    const submit = async () => {
      error.value = ''
      const v = (adminKey.value || '').trim()
      if (!v) {
        error.value = '请输入 Admin Key'
        return
      }
      testing.value = true
      try {
        // 试调一个轻量 admin-only 接口验证 key 有效
        await axios.get(`${API_BASE_URL}/admin/logs`, {
          headers: { 'X-Admin-Key': v },
          params: { lines: 1 },
          timeout: 5000
        })
        safeSet(KEY.ADMIN_KEY, v)
        const target = route.query.redirect || '/admin'
        router.replace(target)
      } catch (e) {
        const msg = (e.response && e.response.data && e.response.data.message) || e.message || '验证失败'
        error.value = msg
      } finally {
        testing.value = false
      }
    }

    return { adminKey, testing, error, submit }
  }
}
</script>

<style scoped>
.login-shell {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg);
  color: var(--color-text);
}
.login-card {
  width: 420px;
  max-width: 90vw;
}
.hint {
  font-size: 13px;
  color: var(--color-text-muted);
  margin: 0 0 12px;
}
.hint code {
  background: var(--color-bg-elev);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
}
.err {
  margin-top: 12px;
  color: var(--color-error);
  font-size: 13px;
}
</style>
