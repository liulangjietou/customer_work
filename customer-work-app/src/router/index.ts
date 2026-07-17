import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/Login.vue'),
      meta: { public: true },
    },
    {
      path: '/register',
      name: 'Register',
      component: () => import('@/views/Register.vue'),
      meta: { public: true },
    },
    {
      path: '/chat',
      name: 'Chat',
      component: () => import('@/views/Chat.vue'),
    },
    {
      path: '/messages',
      name: 'Messages',
      component: () => import('@/views/Messages.vue'),
    },
    {
      path: '/orders',
      name: 'OrderList',
      component: () => import('@/views/OrderList.vue'),
    },
    {
      path: '/orders/:id',
      name: 'OrderDetail',
      component: () => import('@/views/OrderDetail.vue'),
      props: true,
    },
    {
      path: '/profile',
      name: 'Profile',
      component: () => import('@/views/Profile.vue'),
    },
    {
      path: '/profile/info',
      name: 'ProfileInfo',
      component: () => import('@/views/ProfileInfo.vue'),
    },
    {
      path: '/tickets/:id',
      name: 'TicketDetail',
      component: () => import('@/views/TicketDetail.vue'),
      props: true,
    },
    {
      path: '/',
      redirect: '/messages',
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/messages',
    },
  ],
})

// 无 token 时除 /login /register 外一律跳 /login
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'Login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
