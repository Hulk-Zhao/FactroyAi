import RouteGroup from '@/layouts/RouteGroup.vue'

import type { RouterModule } from '../types'

export const processModule: RouterModule = {
  key: 'process',
  label: '工艺路线',
  menuPath: '/process/routings',
  order: 30,
  menu: true,
  routes: [
    {
      path: 'process',
      name: 'process',
      component: RouteGroup,
      redirect: '/process/routings',
      meta: { title: '工艺路线' },
      children: [
        {
          path: 'routings',
          name: 'process-routings',
          component: () => import('@/views/process/routings/RoutingListView.vue'),
          meta: { title: '工艺路线' }
        },
        {
          path: 'routings/:id',
          name: 'process-routing-editor',
          component: () => import('@/views/process/routings/RoutingEditorView.vue'),
          meta: { title: '工艺路线编辑' }
        }
      ]
    }
  ]
}

export default processModule
