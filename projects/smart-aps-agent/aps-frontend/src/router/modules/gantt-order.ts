import type { RouterModule } from '../types'

/**
 * 订单甘特路由（M2-FE-02）：订单为泳道，复用 components/gantt 的分析与渲染。
 * 路径与资源甘特（/gantt）并列，互不改写。
 */
export const ganttOrderModule: RouterModule = {
  key: 'gantt-order',
  label: '订单甘特',
  menuPath: '/gantt-orders',
  order: 61,
  menu: true,
  routes: [
    {
      path: 'gantt-orders',
      name: 'gantt-orders',
      component: () => import('@/views/gantt/OrderGanttView.vue'),
      meta: {
        title: '订单甘特图',
        description:
          '订单为泳道的甘特，复用资源甘特的 C14 日历、冲突、热力与 lateMinutes/lockType 口径。'
      }
    }
  ]
}

export default ganttOrderModule
