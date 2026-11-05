import type { RouterModule } from '../types'

/** M3 导入中心：C38 客户导入与 C39 异步导入。 */
export const importModule: RouterModule = {
  key: 'import',
  label: '导入中心',
  menuPath: '/import',
  order: 48,
  menu: true,
  routes: [
    {
      path: 'import',
      name: 'import-center',
      component: () => import('@/views/import/ImportCenterView.vue'),
      meta: { title: '导入中心' }
    }
  ]
}

export default importModule
