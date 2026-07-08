import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/login/Login.vue'),
      meta: { public: true },
    },
    {
      path: '/change-password',
      name: 'ChangePassword',
      component: () => import('@/views/login/ChangePassword.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/',
      name: 'Layout',
      component: () => import('@/layouts/MainLayout.vue'),
      redirect: '/home',
      children: [
        {
          path: 'home',
          name: 'Home',
          component: () => import('@/views/Home.vue'),
        },
        {
          // 动态智能体工作区节点：path 形如 /workspace/{agentCode}，运行时拼进菜单，不落库、不预注册
          path: 'workspace/:agentCode',
          name: 'Workspace',
          component: () => import('@/views/workspace/WorkspaceView.vue'),
          props: true,
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('@/views/NotFound.vue'),
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    return true
  }
  if (!auth.isLoggedIn) {
    return { name: 'Login', query: { redirect: to.fullPath } }
  }
  if (auth.forceChangePassword && to.name !== 'ChangePassword') {
    return { name: 'ChangePassword' }
  }

  const menuStore = useMenuStore()
  if (!menuStore.routesRegistered) {
    await menuStore.bootstrap()
    // 动态路由刚注册完，重新触发一次导航解析，命中新加入的路由记录
    return { ...to, replace: true }
  }
  return true
})

export default router
