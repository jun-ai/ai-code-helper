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

// 自定义 code 渲染器：包裹一层 .code-block 带语言标签 + 复制按钮
// 复制按钮靠 CSS 选择器 ::before / ::after 不行，改为运行时事件委托
const renderer = new marked.Renderer()
const baseCodeRenderer = renderer.code.bind(renderer)
renderer.code = (code, infostring) => {
    const lang = (infostring || '').trim().split(/\s+/)[0] || ''
    const html = baseCodeRenderer(code, infostring)
    // 外层 div 带 data-lang + .code-block，复制按钮按需 JS 注入
    return `<div data-lang="${lang || 'text'}" class="code-block">${html}</div>`
}

// 必须在 setOptions 之后再覆盖 renderer
marked.use({ renderer })

// 给 v-html 容器里所有 .code-block 绑定复制按钮（首次渲染后调用）
export function attachCodeCopyButtons(container) {
    if (!container) return
    const blocks = container.querySelectorAll('.code-block')
    blocks.forEach((block) => {
        if (block.querySelector('.code-copy-btn')) return
        const lang = block.getAttribute('data-lang') || 'text'
        const langLabel = document.createElement('span')
        langLabel.className = 'code-lang'
        langLabel.textContent = lang
        const copyBtn = document.createElement('button')
        copyBtn.className = 'code-copy-btn'
        copyBtn.title = '复制代码'
        copyBtn.textContent = '📋'
        copyBtn.addEventListener('click', async () => {
            const code = block.querySelector('code')
            const text = code ? code.innerText : block.innerText
            try {
                await navigator.clipboard.writeText(text)
                copyBtn.textContent = '✓'
                setTimeout(() => { copyBtn.textContent = '📋' }, 1200)
            } catch (_) {
                copyBtn.textContent = '✗'
                setTimeout(() => { copyBtn.textContent = '📋' }, 1200)
            }
        })
        block.appendChild(langLabel)
        block.appendChild(copyBtn)
    })
}

// 全局 Markdown 渲染：marked → DOMPurify 消毒，v-html 前统一调用
export function renderMarkdown(md) {
    if (!md) return ''
    return DOMPurify.sanitize(marked(md))
}
