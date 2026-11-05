import type { RouterModule } from '../types'

export const strategiesModule: RouterModule = {
  key: 'strategies',
  label: '排产策略',
  menuPath: '/strategies',
  order: 45,
  menu: true,
  routes: [
    {
      path: 'strategies',
      name: 'strategies',
      component: () => import('@/views/strategies/StrategyListView.vue'),
      meta: { title: '排产策略' }
    }
  ]
}

export default strategiesModule
