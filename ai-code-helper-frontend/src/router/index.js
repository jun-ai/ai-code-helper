import { createRouter, createWebHistory } from 'vue-router'
import { safeGet, KEY } from '../composables/storage.js'

// 用户面（聊天）
const UserApp = () => import('../views/UserApp.vue')

// 后台：登录页 + 4 个模块
const AdminLogin = () => import('../views/AdminLogin.vue')
const AdminApp = () => import('../views/AdminApp.vue')
const KbPanel = () => import('../views/admin/KbPanel.vue')
const MetricsPanel = () => import('../views/admin/MetricsPanel.vue')
const LogsPanel = () => import('../views/admin/LogsPanel.vue')
const KeysPanel = () => import('../views/admin/KeysPanel.vue')

const routes = [
  { path: '/', name: 'home', component: UserApp },
  { path: '/admin/login', name: 'admin-login', component: AdminLogin },
  {
    path: '/admin',
    component: AdminApp,
    meta: { requiresAdmin: true },
    children: [
      { path: '', redirect: '/admin/kb' },
      { path: 'kb', name: 'admin-kb', component: KbPanel, meta: { title: '知识库' } },
      { path: 'metrics', name: 'admin-metrics', component: MetricsPanel, meta: { title: '监控' } },
      { path: 'logs', name: 'admin-logs', component: LogsPanel, meta: { title: '日志' } },
      { path: 'keys', name: 'admin-keys', component: KeysPanel, meta: { title: 'API Key' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  if (to.meta.requiresAdmin) {
    const adminKey = safeGet(KEY.ADMIN_KEY, '')
    if (!adminKey) {
      next({ name: 'admin-login', query: { redirect: to.fullPath } })
      return
    }
  }
  next()
})

export default router
