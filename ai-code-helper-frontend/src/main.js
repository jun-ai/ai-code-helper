import { createApp } from 'vue'
import axios from 'axios'
import App from './App.vue'
import router from './router/index.js'
import { safeGet, safeGetJSON, safeSet, KEY } from './composables/storage.js'
import './styles/tokens.css'
import 'highlight.js/styles/github.css'
import './utils/markdown.js'

// 旧版 adminKey 混存在 settings JSON 里，启动时迁移到独立的 ADMIN_KEY（单一事实源）
const legacy = safeGetJSON(KEY.SETTINGS, {}) || {}
if (!safeGet(KEY.ADMIN_KEY) && legacy.adminKey) {
    safeSet(KEY.ADMIN_KEY, legacy.adminKey)
}

// 按路径注入密钥头：用户接口 X-API-Key、管理接口 X-Admin-Key，不再全量双发；
// 显式传入的头不覆盖（AdminLogin 换 key 验证依赖显式头）
axios.interceptors.request.use((config) => {
    const s = safeGetJSON(KEY.SETTINGS, {}) || {}
    const adminKey = safeGet(KEY.ADMIN_KEY, '')
    const headers = config.headers = config.headers || {}
    const url = config.url || ''
    const isUserApi = url.includes('/ai/') || url.includes('/upload')
    const isAdminApi = url.includes('/rag') || url.includes('/admin') || url.includes('/actuator')
    if (isUserApi && s.apiKey && !headers['X-API-Key']) headers['X-API-Key'] = s.apiKey
    if (isAdminApi && adminKey && !headers['X-Admin-Key']) headers['X-Admin-Key'] = adminKey
    return config
})

const app = createApp(App)
app.use(router)
app.mount('#app')
