import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAppStore = defineStore('app', () => {
  const appName = ref('智能APS')
  const apiBaseUrl = ref(import.meta.env.VITE_API_BASE_URL ?? '/api/v1')

  return { appName, apiBaseUrl }
})
