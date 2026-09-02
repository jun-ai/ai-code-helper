import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { NaiveUiResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ imports: ['vue', 'vue-router'] }),
    Components({
      resolvers: [NaiveUiResolver()],
      dirs: ['src/components']
    })
  ],
  server: {
    port: 3000,
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true
      },
      // Prometheus 端点在后端 /api context-path 下，rewrite 补前缀；同源代理避免 CORS
      '/actuator': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        rewrite: (path) => '/api' + path
      }
    }
  }
})