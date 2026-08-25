import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081/api'
const API_KEY = import.meta.env.VITE_API_KEY || ''

// 网络/5xx 时的最大重试次数
const MAX_RETRIES = 3

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 从后端错误响应里抽 message 字段，纯文本直接返回
 */
function parseErrorMessage(body) {
    if (!body) return null
    const trimmed = body.trim()
    if (trimmed.startsWith('{')) {
        try {
            const obj = JSON.parse(trimmed)
            if (obj && typeof obj.message === 'string') {
                return obj.message
            }
        } catch (_) {
            // 非 JSON
        }
    }
    return trimmed || null
}

/**
 * SSE 聊天（GET，无附件）：浏览器原生 EventSource 无法塞自定义头，改用 fetch + ReadableStream。
 */
export function chatWithSSE(memoryId, message, onMessage, onError, onClose, onRetry) {
    const url = `${API_BASE_URL}/ai/chat?${new URLSearchParams({ memoryId, message })}`
    return sseStream(url, { method: 'GET' }, onMessage, onError, onClose, onRetry)
}

/**
 * SSE 聊天（POST，multipart，带附件）：图片走视觉模型，其他文件当附件上下文。
 */
export function chatWithFile(memoryId, message, file, onMessage, onError, onClose, onRetry) {
    const url = `${API_BASE_URL}/ai/chat`
    const form = new FormData()
    form.append('memoryId', String(memoryId))
    if (message) {
        form.append('message', message)
    }
    if (file) {
        form.append('file', file)
    }
    return sseStream(url, { method: 'POST', body: form }, onMessage, onError, onClose, onRetry)
}

function sseStream(url, init, onMessage, onError, onClose, onRetry) {
    const controller = new AbortController()
    const decoder = new TextDecoder()

    const streamHandle = {
        close: () => controller.abort(),
    }

    const run = (async () => {
        let attempt = 0
        let receivedAnyChunk = false

        // eslint-disable-next-line no-constant-condition
        while (true) {
            try {
                const response = await fetch(url, {
                    ...init,
                    headers: {
                        Accept: 'text/event-stream',
                        ...(API_KEY ? { 'X-API-Key': API_KEY } : {}),
                    },
                    signal: controller.signal,
                })

                const shouldRetryStatus = response.status >= 500
                if (!response.ok || !response.body) {
                    let detail = ''
                    try {
                        detail = await response.text()
                    } catch (_) {
                        // 忽略
                    }
                    // 429 + 有 Retry-After 头：等一次再试，单次机会避免久等
                    if (response.status === 429) {
                        const retryAfter = Number(response.headers.get('Retry-After')) || 5
                        if (attempt === 0) {
                            attempt++
                            await sleep(Math.min(retryAfter * 1000, 10_000))
                            continue
                        }
                    }
                    if (shouldRetryStatus && attempt < MAX_RETRIES) {
                        attempt++
                        await sleep(500 * Math.pow(2, attempt))
                        continue
                    }
                    // 解析后端 message JSON 字段，去掉 HTTP 前缀和外壳
                    const friendly = parseErrorMessage(detail) || `HTTP ${response.status}`
                    throw new Error(friendly)
                }

                const reader = response.body.getReader()
                let buffer = ''
                // eslint-disable-next-line no-constant-condition
                while (true) {
                    const { value, done } = await reader.read()
                    if (done) {
                        break
                    }
                    if (!receivedAnyChunk && attempt > 0 && onRetry) {
                        onRetry()
                    }
                    receivedAnyChunk = true
                    buffer += decoder.decode(value, { stream: true })
                    let sepIndex
                    while ((sepIndex = buffer.indexOf('\n\n')) !== -1) {
                        const rawEvent = buffer.slice(0, sepIndex)
                        buffer = buffer.slice(sepIndex + 2)
                        const eventName = rawEvent
                            .split('\n')
                            .find((line) => line.startsWith('event:'))
                        const dataLine = rawEvent
                            .split('\n')
                            .find((line) => line.startsWith('data:'))
                        if (eventName && dataLine) {
                            const ev = eventName.slice(6).trim()
                            const data = dataLine.slice(5).trim()
                            if (ev === 'error') {
                                // 后端业务错误帧：抛出，终止流
                                throw new Error(parseErrorMessage(data) || '生成失败')
                            }
                            // 其他自定义事件暂不处理
                        } else if (dataLine) {
                            const data = dataLine.slice(5).trim()
                            if (data) {
                                onMessage(data)
                            }
                        }
                    }
                }
                if (buffer.trim()) {
                    const eventName = buffer
                        .split('\n')
                        .find((line) => line.startsWith('event:'))
                    const dataLine = buffer
                        .split('\n')
                        .find((line) => line.startsWith('data:'))
                    if (eventName && dataLine) {
                        const ev = eventName.slice(6).trim()
                        const data = dataLine.slice(5).trim()
                        if (ev === 'error') {
                            throw new Error(parseErrorMessage(data) || '生成失败')
                        }
                    } else if (dataLine) {
                        const data = dataLine.slice(5).trim()
                        if (data) {
                            onMessage(data)
                        }
                    }
                }
                return
            } catch (err) {
                if (err.name === 'AbortError') {
                    return
                }
                if (receivedAnyChunk && attempt < MAX_RETRIES) {
                    attempt++
                    await sleep(800 * Math.pow(2, attempt))
                    continue
                }
                throw err
            }
        }
    })()

    void run

    run.then(
        () => onClose && onClose(),
        (err) => {
            console.error('SSE 连接错误:', err)
            if (onError) {
                onError(err)
            }
            if (onClose) {
                onClose()
            }
        }
    )

    return streamHandle
}

/**
 * 检查后端服务是否可用
 */
export async function checkServiceHealth() {
    try {
        const response = await axios.get(`${API_BASE_URL}/health`, {
            timeout: 5000,
            ...(API_KEY ? { headers: { 'X-API-Key': API_KEY } } : {}),
        })
        return response.status === 200
    } catch (error) {
        console.error('服务健康检查失败:', error)
        return false
    }
}

/**
 * 上传文件（图片 / 视频 / 文档），由 UploadController 处理
 * @returns {Promise<{url, fileName, size, mimeType, indexed, chunks?}>}
 */
export async function uploadFile(file) {
    const form = new FormData()
    form.append('file', file)
    const response = await axios.post(`${API_BASE_URL}/upload`, form, {
        headers: {
            'Content-Type': 'multipart/form-data',
            ...(API_KEY ? { 'X-API-Key': API_KEY } : {}),
        },
        timeout: 60_000,
    })
    return response.data
}
