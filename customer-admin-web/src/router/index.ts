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
          meta: { title: '首页' },
        },
        {
          // "智能体工作区"分组节点自身的 path（还没有任何启用中的智能体、或点了分组节点本身时命中），
          // 展示空态引导去新建智能体，而不是落到通配路由变成 404。
          path: 'workspace',
          name: 'WorkspaceEmpty',
          component: () => import('@/views/workspace/WorkspaceEmpty.vue'),
          meta: { title: '智能体工作区' },
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
    try {
      await menuStore.bootstrap()
    } catch (e) {
      // 拉菜单/权限失败（网络抖动、token 失效等）时，由本守卫自己统一做重定向。
      // 不能任异常未接住向外抛：否则会与 request.ts 拦截器里的 router.push('/login')
      // 形成两个并发导航互打，在 Vue 卸载中那个组件上触发
      // "Cannot read properties of null (reading 'parentNode')"（已实测复现）。
      console.error('menu bootstrap failed, redirect to login', e)
      auth.clear()
      menuStore.reset()
      return { name: 'Login', query: { redirect: to.fullPath } }
    }
    // 动态路由刚注册完，重新触发一次导航解析，命中新加入的路由记录。
    // 坑：不能 `{ ...to, replace: true }`——bootstrap() 之前 to 还没匹配到任何动态路由，
    // 已经被解析成了 name: 'NotFound'；Vue Router 对返回的重定向目标只要带 name 字段就按
    // 具名路由解析（优先级高于 path），会原样跳回 NotFound，前端表现就是刷新页面必现 404。
    // 只传 path（用 fullPath 保留 query/hash），强制按路径重新匹配，才能命中刚注册的路由。
    return { path: to.fullPath, replace: true }
  }
  return true
})

export default router
