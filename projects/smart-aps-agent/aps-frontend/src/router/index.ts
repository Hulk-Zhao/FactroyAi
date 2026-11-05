import { createRouter, createWebHistory } from 'vue-router'

import { getAuthToken } from '@/api/http'
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

/**
 * C30 登录守卫：没有本地令牌时直接访问业务页会全部 401 并停在“加载失败”，
 * 因此进入任何非登录页前先校验令牌，缺失就回到登录页（登录成功后再跳回首页）。
 * 令牌失效由 http 响应拦截器负责清理并带 redirected=1 跳转。
 */
router.beforeEach((to) => {
  if (to.name === 'login') {
    return true
  }
  if (!getAuthToken()) {
    return { name: 'login' }
  }
  return true
})

export default router
