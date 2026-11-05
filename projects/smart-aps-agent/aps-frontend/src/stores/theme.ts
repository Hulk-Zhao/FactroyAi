import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

const STORAGE_KEY = 'aps-theme-mode'
const DARK_QUERY = '(prefers-color-scheme: dark)'

function readMode(): ThemeMode {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return value === 'light' || value === 'dark' || value === 'system' ? value : 'system'
  } catch {
    return 'system'
  }
}

const media = typeof window === 'undefined' ? null : window.matchMedia(DARK_QUERY)

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(readMode())
  const systemDark = ref(media?.matches ?? true)
  const resolved = computed<ResolvedTheme>(() =>
    mode.value === 'system' ? (systemDark.value ? 'dark' : 'light') : mode.value
  )

  function apply() {
    const root = document.documentElement
    const dark = resolved.value === 'dark'
    root.classList.toggle('dark', dark)
    root.setAttribute('data-theme', dark ? 'dark' : 'light')
    root.style.colorScheme = dark ? 'dark' : 'light'
  }

  function setMode(next: ThemeMode) {
    mode.value = next
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // 隐私模式下 localStorage 可能不可写，内存态仍然生效。
    }
    apply()
  }

  media?.addEventListener('change', (event) => {
    systemDark.value = event.matches
    if (mode.value === 'system') {
      apply()
    }
  })

  // 入口已由 index.html 内联脚本预置，这里同步一次确保 HMR / 非首屏场景一致。
  apply()

  return { mode, resolved, setMode }
})
