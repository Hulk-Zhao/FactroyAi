import type { HelpModule, HelpScene } from './types'

interface HelpEntry {
  /** HelpModule 内的场景键（统一用路由 name，避免动态参数 path 无法静态匹配） */
  key: string
  /** 正文按需拉取，保证不进入首屏入口 chunk */
  load: () => Promise<{ default: HelpModule }>
}

/**
 * 场景帮助注册表：以路由 path 为键；动态路由额外用 route name 注册。
 * 未注册的路由不渲染悬浮入口，避免所有页面共用一个通用弹窗。
 */
export const HELP_REGISTRY: Record<string, HelpEntry> = {
  '/': { key: 'dashboard', load: () => import('@/content/help/dashboard') },
  '/master-data/materials': {
    key: 'master-data-materials',
    load: () => import('@/content/help/master-data')
  },
  '/master-data/boms': {
    key: 'master-data-boms',
    load: () => import('@/content/help/master-data')
  },
  '/master-data/resources': {
    key: 'master-data-resources',
    load: () => import('@/content/help/master-data')
  },
  '/master-data/calendars': {
    key: 'master-data-calendars',
    load: () => import('@/content/help/master-data')
  },
  '/process/routings': {
    key: 'process-routings',
    load: () => import('@/content/help/process')
  },
  'process-routing-editor': {
    key: 'process-routing-editor',
    load: () => import('@/content/help/process')
  },
  '/orders': { key: 'orders', load: () => import('@/content/help/orders') },
  '/strategies': { key: 'strategies', load: () => import('@/content/help/planning') },
  '/mrp/analysis': { key: 'mrp-analysis', load: () => import('@/content/help/planning') },
  '/solve-tasks': { key: 'solve-tasks', load: () => import('@/content/help/planning') },
  '/import': { key: 'import-center', load: () => import('@/content/help/planning') },
  '/gantt': { key: 'gantt', load: () => import('@/content/help/gantt') },
  '/gantt-orders': { key: 'gantt-orders', load: () => import('@/content/help/gantt') },
  '/plans/publish': { key: 'plans-publish', load: () => import('@/content/help/plans') },
  '/plans/compare': { key: 'plans-compare', load: () => import('@/content/help/plans') },
  '/sandbox': { key: 'sandbox', load: () => import('@/content/help/plans') },
  '/system/users': { key: 'system-users', load: () => import('@/content/help/system') },
  '/system/audit': { key: 'system-audit', load: () => import('@/content/help/system') }
}

/** 已注册路径（用于交付清单与未注册场景核对） */
export const HELP_ROUTE_PATHS = Object.keys(HELP_REGISTRY)

export function resolveHelpEntry(path: string, name?: string | null): HelpEntry | undefined {
  return HELP_REGISTRY[path] ?? (name ? HELP_REGISTRY[name] : undefined) ?? undefined
}

export type { HelpEntry, HelpModule, HelpScene }
