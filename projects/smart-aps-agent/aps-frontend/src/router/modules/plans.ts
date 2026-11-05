import type { RouterModule } from '../types'

/**
 * 计划发布与版本（M2-FE-07）。菜单序号 47 避开 W8 已用的 10/20/30/40/45/46/50/60。
 */
export const plansModule: RouterModule = {
  key: 'plans',
  label: '计划版本',
  menuPath: '/plans/publish',
  order: 47,
  menu: true,
  menuItems: [
    { path: '/plans/publish', label: '计划发布与版本' },
    { path: '/plans/compare', label: '计划对比择优' }
  ],
  routes: [
    {
      path: 'plans/publish',
      name: 'plans-publish',
      component: () => import('@/views/plans/PlanPublishView.vue'),
      meta: { title: '计划发布与版本' }
    },
    {
      path: 'plans/compare',
      name: 'plans-compare',
      component: () => import('@/views/plans/PlanCompareView.vue'),
      meta: { title: '计划对比择优' }
    }
  ]
}

export default plansModule
