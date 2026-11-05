import { createRouter, createWebHistory } from 'vue-router'

import AppLayout from '@/layouts/AppLayout.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import LoginView from '@/views/login/LoginView.vue'

import { routerModules } from './modules'

/**
 * C30 登录页是唯一不带侧栏/顶栏的独立页面，必须挂在 AppLayout 之外；
 * 其余业务模块仍只在 src/router/modules/ 注册。
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { title: '登录', hidden: true }
    },
    {
      path: '/',
      component: AppLayout,
      children: [
        ...routerModules.flatMap((module) => module.routes),
        {
          path: ':pathMatch(.*)*',
          name: 'not-found',
          component: NotFoundView,
          meta: { title: '页面不存在', hidden: true }
        }
      ]
    }
  ]
})

export default router
