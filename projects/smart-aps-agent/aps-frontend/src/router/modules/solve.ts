import type { RouterModule } from '../types'

export const solveModule: RouterModule = {
  key: 'solve',
  label: '排产求解',
  menuPath: '/solve-tasks',
  order: 50,
  menu: true,
  routes: [
    {
      path: 'solve-tasks',
      name: 'solve-tasks',
      component: () => import('@/views/SolveTaskView.vue'),
      meta: { title: '排产求解' }
    }
  ]
}

export default solveModule
