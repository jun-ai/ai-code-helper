import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'

/**
 * 贴底跟随滚动：用户上翻后停止强制跟随，回到底部后恢复。
 */
export function useScrollFollow(refEl) {
  const userScrolledUp = ref(false)
  let el = null

  const handleScroll = () => {
    if (!el) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    userScrolledUp.value = distance > 60
  }

  const scrollToBottom = (force = false) => {
    if (userScrolledUp.value && !force) return
    nextTick(() => {
      if (el) el.scrollTop = el.scrollHeight
    })
  }

  onMounted(() => {
    el = refEl.value
    if (el) el.addEventListener('scroll', handleScroll, { passive: true })
  })

  onBeforeUnmount(() => {
    if (el) el.removeEventListener('scroll', handleScroll)
  })

  return { userScrolledUp, scrollToBottom }
}