/**
 * 阿里云 OSS 签名直传链路：
 *   1) requestPresignUpload({fileName, size, contentType}) → {uploadUrl, key, publicUrl}
 *   2) 前端 PUT 文件到 uploadUrl
 *   3) notifyUploadFinished({key, fileName, size, mimeType}) → 后端触发 ingestion
 */
import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081/api'

export async function requestPresignUpload({ fileName, size, contentType }) {
    const r = await axios.post(
        `${API_BASE_URL}/upload/presign`,
        null,
        {
            params: { fileName, size, contentType },
            timeout: 10_000,
        }
    )
    return r.data
}

/**
 * 直接 PUT 到签名 URL（不携带 axios 拦截器的 API Key 头，避免 OSS 验签干扰）。
 * 失败抛出 Error；调用方负责重试/降级。
 */
export async function putToPresignedUrl(uploadUrl, file, contentType) {
    const resp = await fetch(uploadUrl, {
        method: 'PUT',
        body: file,
        headers: contentType ? { 'Content-Type': contentType } : {},
    })
    if (!resp.ok) {
        const detail = await resp.text().catch(() => '')
        throw new Error(`OSS PUT failed: HTTP ${resp.status} ${detail}`)
    }
}

export async function notifyUploadFinished({ key, fileName, size, mimeType }) {
    const r = await axios.post(
        `${API_BASE_URL}/upload/finish`,
        { key, fileName, size, mimeType },
        { timeout: 60_000 }
    )
    return r.data
}