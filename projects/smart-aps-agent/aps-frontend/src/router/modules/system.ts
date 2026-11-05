import type { RouterModule } from '../types'

/** C30/C31 系统管理：用户与角色、审计日志。 */
export const systemModule: RouterModule = {
  key: 'system',
  label: '系统管理',
  menuPath: '/system/users',
  order: 80,
  menu: true,
  menuItems: [
    { path: '/system/users', label: '用户与角色' },
    { path: '/system/audit', label: '审计日志' }
  ],
  routes: [
    {
      path: 'system/users',
      name: 'system-users',
      component: () => import('@/views/system/SystemUsersView.vue'),
      meta: { title: '用户与角色' }
    },
    {
      path: 'system/audit',
      name: 'system-audit',
      component: () => import('@/views/system/AuditLogView.vue'),
      meta: { title: '审计日志' }
    }
  ]
}

export default systemModule
