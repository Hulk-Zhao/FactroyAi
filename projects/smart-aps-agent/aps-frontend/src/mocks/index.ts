import type { AxiosInstance } from 'axios'

import { mockAdapter } from './adapter'

/** Mock 默认关闭；仅在 VITE_ENABLE_MOCK=true 时生效。 */
export function isMockEnabled(): boolean {
  return import.meta.env.VITE_ENABLE_MOCK === 'true'
}

export function installMockIfEnabled(instance: AxiosInstance): boolean {
  if (!isMockEnabled()) {
    return false
  }
  instance.defaults.adapter = mockAdapter
  console.info('[aps] Mock 已启用：请求将由 src/mocks 契约数据响应')
  return true
}
