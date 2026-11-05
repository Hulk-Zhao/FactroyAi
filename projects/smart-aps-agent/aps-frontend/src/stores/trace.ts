import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 最近一次请求的链路 ID，用于顶栏展示与排障复制。 */
export const useTraceStore = defineStore('trace', () => {
  const latestTraceId = ref('')
  const recentTraceIds = ref<string[]>([])

  function setTraceId(traceId?: string | null) {
    if (!traceId) {
      return
    }
    latestTraceId.value = traceId
    recentTraceIds.value = [
      traceId,
      ...recentTraceIds.value.filter((item) => item !== traceId)
    ].slice(0, 10)
  }

  return { latestTraceId, recentTraceIds, setTraceId }
})
