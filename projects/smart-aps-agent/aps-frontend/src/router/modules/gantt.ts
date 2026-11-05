import type { RouterModule } from '../types'

/**
 * 甘特图路由：M1-FE-06 PoC 落地只读资源甘特（Mock 数据源，走 GanttAdapter）。
 * 拖拽编辑属 M2-FE-03，不在本阶段范围。
 */
export const ganttModule: RouterModule = {
  key: 'gantt',
  label: '甘特图',
  menuPath: '/gantt',
  order: 60,
  menu: true,
  routes: [
    {
      path: 'gantt',
      name: 'gantt',
      component: () => import('@/views/gantt/ResourceGanttView.vue'),
      meta: {
        title: '甘特图',
        description: '资源为行的只读甘特，支持时间轴缩放、横向滚动、行头冻结与悬浮 tooltip。'
      }
    }
  ]
}

export default ganttModule
