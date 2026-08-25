/**
 * RAG 知识库管理 API：列表 / 单文件删除 / 全量重建。
 */
import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081/api'
const API_KEY = import.meta.env.VITE_API_KEY || ''

function headers() {
    return API_KEY ? { 'X-API-Key': API_KEY } : {}
}

export function listDocs() {
    return axios
        .get(`${API_BASE_URL}/rag/docs`, { headers: headers(), timeout: 15_000 })
        .then((r) => r.data)
}

export function deleteDoc(fileName) {
    return axios
        .delete(`${API_BASE_URL}/rag/docs/${encodeURIComponent(fileName)}`, {
            headers: headers(),
            timeout: 300_000, // 删除会触发重建，给足超时
        })
        .then((r) => r.data)
}

export function rebuildIndex() {
    return axios
        .post(`${API_BASE_URL}/rag/rebuild`, null, {
            headers: headers(),
            timeout: 600_000, // 重建可能很慢
        })
        .then((r) => r.data)
}