import { ref } from 'vue'

/**
 * 当前 UI 上下文场景键：路由只能定位到页面级场景（如订单管理），
 * 但 AI 解读、交期预估、问一问这类场景只在某个抽屉/卡片打开时才成立。
 * 由对应组件在进入该上下文时写入，HelpAssistant 优先展示它，离开时清空。
 */
export const helpContextKey = ref<string | null>(null)

export function setHelpContext(key: string): void {
  helpContextKey.value = key
}

/** 只清除自己写入的键，避免组件卸载时误清别人刚设置的上下文。 */
export function clearHelpContext(key?: string): void {
  if (!key || helpContextKey.value === key) {
    helpContextKey.value = null
  }
}
