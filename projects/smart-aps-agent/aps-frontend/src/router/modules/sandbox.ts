import type { RouterModule } from '../types'

/** C32 场景沙箱（WHAT_IF 对比与发布）。 */
export const sandboxModule: RouterModule = {
  key: 'sandbox',
  label: '场景沙箱',
  menuPath: '/sandbox',
  order: 70,
  menu: true,
  routes: [
    {
      path: 'sandbox',
      name: 'sandbox',
      component: () => import('@/views/sandbox/SandboxView.vue'),
      meta: { title: '场景沙箱' }
    }
  ]
}

export default sandboxModule
