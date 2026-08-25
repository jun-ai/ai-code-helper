<template>
  <n-drawer :show="show" @update:show="updateShow" :width="380" placement="right">
    <n-drawer-content title="设置" closable @close="updateShow(false)">
      <n-form label-placement="top" size="small">
        <n-form-item label="API Key">
          <n-input
            v-model:value="form.apiKey"
            type="password"
            show-password-on="click"
            placeholder="后端 X-API-Key"
          />
        </n-form-item>

        <n-form-item label="Admin Key">
          <n-input
            v-model:value="form.adminKey"
            type="password"
            show-password-on="click"
            placeholder="后台 X-Admin-Key（独立管理后台用）"
          />
        </n-form-item>

        <n-form-item label="主题">
          <n-radio-group v-model:value="form.theme">
            <n-radio value="light">浅色</n-radio>
            <n-radio value="dark">深色</n-radio>
          </n-radio-group>
        </n-form-item>

        <n-form-item label="聊天模型">
          <n-select
            v-model:value="form.chatModel"
            :options="chatModels"
            placeholder="glm-4-flash"
          />
        </n-form-item>

        <n-form-item label="视觉模型">
          <n-select
            v-model:value="form.visionModel"
            :options="visionModels"
            placeholder="glm-4v-flash"
          />
        </n-form-item>

        <n-form-item label="向量模型">
          <n-input v-model:value="form.embeddingModel" placeholder="MiniMax-embo-01" />
        </n-form-item>

        <n-form-item label="启用 RAG 检索">
          <n-switch v-model:value="form.ragEnabled" />
        </n-form-item>

        <n-divider />

        <n-form-item>
          <n-space vertical>
            <n-button block @click="saveSettings">保存</n-button>
            <n-button block secondary @click="resetSettings">恢复默认</n-button>
            <n-button block secondary type="primary" @click="goAdmin">
              <template #icon><span>⚙️</span></template>
              打开管理后台
            </n-button>
          </n-space>
        </n-form-item>
      </n-form>
    </n-drawer-content>
  </n-drawer>
</template>

<script>
import { reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NDrawer, NDrawerContent, NForm, NFormItem, NInput, NSelect, NSwitch, NRadioGroup, NRadio, NDivider, NSpace, NButton } from 'naive-ui'
import { safeGetJSON, safeSet, KEY } from '../composables/storage.js'

const DEFAULT_SETTINGS = {
  apiKey: '',
  adminKey: '',
  chatModel: 'glm-4-flash',
  visionModel: 'glm-4v-flash',
  embeddingModel: 'MiniMax-embo-01',
  ragEnabled: true,
  theme: 'light'
}

export default {
  name: 'SettingsDrawer',
  components: {
    NDrawer, NDrawerContent, NForm, NFormItem, NInput, NSelect, NSwitch,
    NRadioGroup, NRadio, NDivider, NSpace, NButton
  },
  props: {
    show: { type: Boolean, default: false }
  },
  emits: ['update:show'],
  setup(props, { emit }) {
    const stored = safeGetJSON(KEY.SETTINGS, DEFAULT_SETTINGS) || DEFAULT_SETTINGS
    const form = reactive({ ...DEFAULT_SETTINGS, ...stored })
    const router = useRouter()

    const chatModels = [
      { label: 'glm-4-flash（推荐·免费）', value: 'glm-4-flash' },
      { label: 'glm-4-air', value: 'glm-4-air' },
      { label: 'glm-4-plus', value: 'glm-4-plus' }
    ]
    const visionModels = [
      { label: 'glm-4v-flash', value: 'glm-4v-flash' },
      { label: 'glm-4v', value: 'glm-4v' }
    ]

    const saveSettings = () => {
      safeSet(KEY.SETTINGS, JSON.stringify(form))
      document.documentElement.setAttribute('data-theme', form.theme)
      emit('update:show', false)
    }

    const resetSettings = () => {
      Object.assign(form, DEFAULT_SETTINGS)
      safeSet(KEY.SETTINGS, JSON.stringify(DEFAULT_SETTINGS))
    }

    const goAdmin = () => {
      // 先把抽屉关掉再跳，否则动画还在跑就被路由切换打断
      emit('update:show', false)
      router.push('/admin')
    }

    // 暴露给模板的转发方法，避免 _ctx.emit 为 undefined
    function updateShow(v) { emit('update:show', v) }

    watch(() => form.theme, (val) => {
      document.documentElement.setAttribute('data-theme', val)
    })

    return { form, chatModels, visionModels, saveSettings, resetSettings, goAdmin, updateShow }
  }
}
</script>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--color-text-faint);
  margin-top: 4px;
}
</style>