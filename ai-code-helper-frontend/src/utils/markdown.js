import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js'

// marked 配置：模块加载时执行一次，避免每次渲染重复 setOptions
marked.setOptions({
    gfm: true,
    breaks: true,
    highlight(code, lang) {
        if (lang && hljs.getLanguage(lang)) {
            try {
                return hljs.highlight(code, { language: lang }).value
            } catch (_) {
                // 降级到自动识别
            }
        }
        try {
            return hljs.highlightAuto(code).value
        } catch (_) {
            // 极端情况下原样返回
            return code
        }
    },
})

// 全局 Markdown 渲染：marked → DOMPurify 消毒，v-html 前统一调用
export function renderMarkdown(md) {
    if (!md) return ''
    return DOMPurify.sanitize(marked(md))
}
