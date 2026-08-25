<template>
  <n-config-provider :theme="null">
    <n-dialog-provider>
      <n-message-provider>
        <n-notification-provider>
          <router-view />
        </n-notification-provider>
      </n-message-provider>
    </n-dialog-provider>
  </n-config-provider>
</template>

<script>
import { NConfigProvider, NDialogProvider, NMessageProvider, NNotificationProvider } from 'naive-ui'

export default {
  name: 'App',
  components: {
    NConfigProvider, NDialogProvider, NMessageProvider, NNotificationProvider
  }
}
</script>

<style>
/* 全局基础样式（app-shell 等由各视图自带 scoped 样式） */
html, body, #app {
  height: 100%;
  margin: 0;
  padding: 0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
    "Hiragino Sans GB", "Microsoft YaHei", Arial, sans-serif;
}
</style>
