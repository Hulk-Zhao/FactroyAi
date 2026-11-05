import type { RouteRecordRaw } from 'vue-router'

export interface MenuItemConfig {
  path: string
  label: string
}

/**
 * 路由模块：每新增一个业务模块只需新建 modules/<module>.ts
 * 并在 modules/index.ts 注册，不修改 router/index.ts。
 */
export interface RouterModule {
  key: string
  label: string
  /** 菜单落点（一级菜单或分组入口） */
  menuPath: string
  order: number
  menu?: boolean
  menuItems?: MenuItemConfig[]
  routes: RouteRecordRaw[]
}

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    hidden?: boolean
    description?: string
  }
}
