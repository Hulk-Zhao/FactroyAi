import type { RouterModule } from '../types'

/**
 * MRP 齐套路由（M2-FE-06）。页面由 W6 交付，路由注册由 W8 独占维护。
 */
export const mrpModule: RouterModule = {
  key: 'mrp',
  label: 'MRP 齐套',
  menuPath: '/mrp/analysis',
  order: 46,
  menu: true,
  routes: [
    {
      path: 'mrp/analysis',
      name: 'mrp-analysis',
      component: () => import('@/views/mrp/MrpAnalysisView.vue'),
      meta: { title: 'MRP 齐套' }
    }
  ]
}

export default mrpModule
