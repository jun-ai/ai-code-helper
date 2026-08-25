import { ref, watch } from 'vue'
import { safeGet, safeSet, KEY } from './storage.js'

const theme = ref(safeGet(KEY.THEME, 'light') ?? 'light')

const applyTheme = (val) => {
  if (typeof document !== 'undefined') {
    document.documentElement.setAttribute('data-theme', val)
  }
}

applyTheme(theme.value)

watch(theme, (val) => {
  safeSet(KEY.THEME, val)
  applyTheme(val)
})

export function useTheme() {
  const setTheme = (val) => { theme.value = val }
  const toggle = () => { theme.value = theme.value === 'light' ? 'dark' : 'light' }
  return { theme, setTheme, toggle }
}