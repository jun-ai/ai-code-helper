<template>
  <n-drawer
    :show="show"
    @update:show="(v) => emit('update:show', v)"
    :width="520"
    placement="right"
  >
    <n-drawer-content title="📚 知识库管理" closable>
      <div class="kb-toolbar">
        <n-space>
          <n-button type="primary" size="small" :loading="loading" @click="refresh">
            <template #icon><span>🔄</span></template>
            刷新列表
          </n-button>
          <n-button
            type="warning"
            size="small"
            :loading="rebuilding"
            @click="confirmRebuild"
          >
            <template #icon><span>🔧</span></template>
            重建索引
          </n-button>
        </n-space>
        <span class="kb-count">{{ docs.length }} 项</span>
      </div>

      <n-alert
        v-if="lastResult"
        :type="lastResult.ok ? 'success' : 'error'"
        :title="lastResult.title"
        :show-icon="false"
        style="margin-bottom: 12px"
      >
        {{ lastResult.message }}
      </n-alert>

      <n-spin :show="loading">
        <n-empty v-if="!docs.length && !loading" description="知识库为空" />
        <div v-else class="kb-list">
          <div
            v-for="(d, i) in docs"
            :key="d.fileName + '-' + i"
            class="kb-row"
            :class="{ 'kb-builtin': d.source === 'builtin' }"
          >
            <div class="kb-icon">
              <span v-if="d.source === 'builtin'">📕</span>
              <span v-else>📄</span>
            </div>
            <div class="kb-meta">
              <div class="kb-name" :title="d.fileName">{{ d.fileName }}</div>
              <div class="kb-sub">
                <n-tag
                  size="tiny"
                  :type="d.source === 'builtin' ? 'info' : 'default'"
                  :bordered="false"
                >{{ d.source === 'builtin' ? '内置' : '已上传' }}</n-tag>
                <span v-if="d.sizeBytes" class="kb-size">{{ formatSize(d.sizeBytes) }}</span>
              </div>
            </div>
            <n-button
              v-if="d.source === 'uploaded'"
              size="tiny"
              quaternary
              type="error"
              :loading="deleting === d.fileName"
              @click="confirmDelete(d.fileName)"
            >删除</n-button>
            <span v-else class="kb-locked" title="内置文档不可删除">🔒</span>
          </div>
        </div>
      </n-spin>

      <div class="kb-footer-hint">
        <span>💡 删除上传文档后会自动重建索引，操作可能需要数十秒</span>
      </div>
    </n-drawer-content>
  </n-drawer>
</template>

<script>
import { ref, watch } from 'vue'
import {
  NDrawer, NDrawerContent, NSpace, NButton, NSpin, NEmpty, NTag, NAlert, useDialog, useMessage
} from 'naive-ui'
import { listDocs, deleteDoc, rebuildIndex } from '../api/ragApi.js'

export default {
  name: 'KnowledgeBaseDrawer',
  components: { NDrawer, NDrawerContent, NSpace, NButton, NSpin, NEmpty, NTag, NAlert },
  props: {
    show: { type: Boolean, default: false }
  },
  emits: ['update:show'],
  setup(props, { emit }) {
    const docs = ref([])
    const loading = ref(false)
    const rebuilding = ref(false)
    const deleting = ref('')
    const lastResult = ref(null)
    const dialog = useDialog()
    const message = useMessage()

    function formatSize(n) {
        if (n < 1024) return `${n} B`
        if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
        return `${(n / 1024 / 1024).toFixed(2)} MB`
    }

    async function refresh() {
        loading.value = true
        try {
            const data = await listDocs()
            docs.value = data.docs || []
        } catch (e) {
            message.error('获取列表失败: ' + (e.message || '未知错误'))
        } finally {
            loading.value = false
        }
    }

    async function doDelete(fileName) {
        deleting.value = fileName
        try {
            const r = await deleteDoc(fileName)
            lastResult.value = {
                ok: !!r.ok,
                title: r.ok ? '删除成功' : '删除失败',
                message: r.message || ''
            }
            await refresh()
        } catch (e) {
            lastResult.value = {
                ok: false,
                title: '删除失败',
                message: e.message || '未知错误'
            }
        } finally {
            deleting.value = ''
        }
    }

    function confirmDelete(fileName) {
        dialog.warning({
            title: '确认删除',
            content: `将删除上传文件「${fileName}」并触发全量索引重建。是否继续？`,
            positiveText: '删除',
            negativeText: '取消',
            onPositiveClick: () => doDelete(fileName)
        })
    }

    async function doRebuild() {
        rebuilding.value = true
        try {
            const r = await rebuildIndex()
            lastResult.value = {
                ok: !!r.ok,
                title: r.ok ? '重建成功' : '重建失败',
                message: `${r.message}（${r.rebuilt} 个文档，${r.elapsedMs}ms）`
            }
            await refresh()
        } catch (e) {
            lastResult.value = {
                ok: false,
                title: '重建失败',
                message: e.message || '未知错误'
            }
        } finally {
            rebuilding.value = false
        }
    }

    function confirmRebuild() {
        dialog.warning({
            title: '确认重建索引',
            content: '将清空 Milvus 全部向量并重新导入所有文档。此过程可能持续数十秒，期间 RAG 检索不可用。是否继续？',
            positiveText: '重建',
            negativeText: '取消',
            onPositiveClick: doRebuild
        })
    }

    watch(() => show, (v) => {
        if (v) {
            lastResult.value = null
            refresh()
        }
    })

    return { docs, loading, rebuilding, deleting, lastResult, formatSize, refresh, confirmDelete, confirmRebuild }
  }
}
</script>

<style scoped>
.kb-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.kb-count {
  font-size: 12px;
  color: var(--color-text-faint);
}
.kb-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.kb-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg-elev);
  transition: background 0.15s;
}
.kb-row:hover { background: var(--color-bg-hover); }
.kb-row.kb-builtin {
  background: rgba(0, 123, 255, 0.04);
}
.kb-icon {
  font-size: 22px;
  flex-shrink: 0;
}
.kb-meta {
  flex: 1;
  min-width: 0;
}
.kb-name {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kb-sub {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  font-size: 11px;
  color: var(--color-text-faint);
}
.kb-size { color: var(--color-text-muted); }
.kb-locked { color: var(--color-text-faint); font-size: 14px; }

.kb-footer-hint {
  margin-top: 16px;
  font-size: 12px;
  color: var(--color-text-faint);
}
</style>