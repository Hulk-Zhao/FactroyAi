import type { RouterModule } from '../types'

export const ordersModule: RouterModule = {
  key: 'orders',
  label: '订单管理',
  menuPath: '/orders',
  order: 40,
  menu: true,
  routes: [
    {
      path: 'orders',
      name: 'orders',
      component: () => import('@/views/OrdersView.vue'),
      meta: { title: '订单管理' }
    }
  ]
}

export default ordersModule
