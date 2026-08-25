import { createApp } from 'vue'
import axios from 'axios'
import App from './App.vue'
import router from './router/index.js'
import { safeGetJSON, KEY } from './composables/storage.js'
import './styles/tokens.css'
import 'highlight.js/styles/github.css'
import './utils/markdown.js'

// axios 请求拦截：从 localStorage 读取 apiKey / adminKey 并注入对应请求头
// 修复原 SettingsDrawer.apiKey 字段保存后不生效的 pre-existing bug（之前只能 Vite env 注入）
axios.interceptors.request.use((config) => {
    const s = safeGetJSON(KEY.SETTINGS, {}) || {}
    const headers = config.headers = config.headers || {}
    if (s.apiKey) headers['X-API-Key'] = s.apiKey
    if (s.adminKey) headers['X-Admin-Key'] = s.adminKey
    return config
})

const app = createApp(App)
app.use(router)
app.mount('#app')
