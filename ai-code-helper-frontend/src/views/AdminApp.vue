<template>
  <div class="admin-shell">
    <aside class="admin-sidebar">
      <div class="brand">
        <span class="brand-icon">⚙️</span>
        <span class="brand-title">管理后台</span>
      </div>
      <nav class="menu">
        <router-link
          v-for="m in menus"
          :key="m.path"
          :to="m.path"
          class="menu-item"
          :class="{ active: $route.path.startsWith(m.path) }"
        >
          <span class="menu-icon">{{ m.icon }}</span>
          <span>{{ m.label }}</span>
        </router-link>
      </nav>
      <div class="sidebar-footer">
        <button class="footer-btn" @click="goUser" title="返回用户聊天面">← 用户面</button>
        <button class="footer-btn danger" @click="logout" title="清除本地 Admin Key">退出</button>
      </div>
    </aside>

    <main class="admin-main">
      <header class="admin-header">
        <h2>{{ currentTitle }}</h2>
        <span class="badge">Admin</span>
      </header>
      <div class="admin-content">
        <router-view />
      </div>
    </main>
  </div>
</template>

<script>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { safeGetJSON, safeSet, safeRemove, KEY } from '../composables/storage.js'

export default {
  name: 'AdminApp',
  setup() {
    const route = useRoute()
    const router = useRouter()

    const menus = [
      { path: '/admin/kb', icon: '📚', label: '知识库' },
      { path: '/admin/metrics', icon: '📈', label: '监控' },
      { path: '/admin/logs', icon: '📜', label: '日志' },
      { path: '/admin/keys', icon: '🔑', label: 'API Key' }
    ]

    const currentTitle = computed(() => {
      const m = menus.find((it) => route.path.startsWith(it.path))
      return m ? m.label : '后台'
    })

    const goUser = () => router.push('/')
    const logout = () => {
      safeRemove(KEY.ADMIN_KEY)
      const settings = safeGetJSON(KEY.SETTINGS, {}) || {}
      delete settings.adminKey
      safeSet(KEY.SETTINGS, JSON.stringify(settings))
      router.replace('/admin/login')
    }

    return { menus, currentTitle, goUser, logout }
  }
}
</script>

<style scoped>
.admin-shell {
  height: 100vh;
  display: grid;
  grid-template-columns: 220px 1fr;
  background: var(--color-bg);
  color: var(--color-text);
}
.admin-sidebar {
  display: flex;
  flex-direction: column;
  background: var(--color-bg-sidebar);
  border-right: 1px solid var(--color-border);
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 18px 16px;
  font-weight: 600;
  border-bottom: 1px solid var(--color-border);
}
.brand-icon { font-size: 20px; }
.brand-title { font-size: 16px; }

.menu {
  flex: 1;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 6px;
  color: var(--color-text);
  text-decoration: none;
  font-size: 14px;
  transition: background 0.1s;
}
.menu-item:hover { background: var(--color-bg-hover); }
.menu-item.active {
  background: var(--color-accent-soft);
  color: var(--color-accent);
}
.menu-icon { width: 18px; text-align: center; }

.sidebar-footer {
  display: flex;
  gap: 6px;
  padding: 12px;
  border-top: 1px solid var(--color-border);
}
.footer-btn {
  flex: 1;
  background: transparent;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  padding: 6px 8px;
  cursor: pointer;
  font-size: 12px;
  color: var(--color-text-muted);
}
.footer-btn:hover { background: var(--color-bg-hover); color: var(--color-text); }
.footer-btn.danger:hover { color: var(--color-error); border-color: var(--color-error); }

.admin-main { display: flex; flex-direction: column; overflow: hidden; }
.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  background: var(--color-bg-elev);
  border-bottom: 1px solid var(--color-border);
}
.admin-header h2 { margin: 0; font-size: 18px; font-weight: 600; }
.badge {
  background: var(--color-accent);
  color: white;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
}
.admin-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
}
</style>
