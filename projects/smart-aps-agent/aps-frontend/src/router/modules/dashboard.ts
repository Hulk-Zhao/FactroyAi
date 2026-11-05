import type { RouterModule } from '../types'

export const dashboardModule: RouterModule = {
  key: 'dashboard',
  label: '工作台',
  menuPath: '/',
  order: 90,
  menu: true,
  routes: [
    {
      path: '',
      name: 'dashboard',
      component: () => import('@/views/DashboardView.vue'),
      meta: { title: '工作台' }
    }
  ]
}

export default dashboardModule
