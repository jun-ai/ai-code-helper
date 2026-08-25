import { createApp } from 'vue'
import App from './App.vue'
import 'highlight.js/styles/github.css'
// 引入即触发 marked 配置（模块加载副作用）
import './utils/markdown.js'

createApp(App).mount('#app')
