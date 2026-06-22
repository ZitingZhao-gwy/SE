import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import { useUserStore } from './stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: { template: '<div>导航门户</div>' } },
    { path: '/home', component: { template: '<div>首页内容</div>' } }
  ]
})

describe('App.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders branding and subsystem links on portal pages', async () => {
    router.push('/home')
    await router.isReady()

    const wrapper = mount(App, {
      global: { plugins: [router] }
    })

    expect(wrapper.text()).toContain('网上信息发布')
    expect(wrapper.text()).toContain('账户系统')
    expect(wrapper.text()).toContain('交易管理')
  })

  it('shows login link for guests on portal pages', async () => {
    router.push('/home')
    await router.isReady()

    const wrapper = mount(App, {
      global: { plugins: [router] }
    })

    const loginLink = wrapper.find('.actions a.ghost-btn')
    expect(loginLink.exists()).toBe(true)
    expect(loginLink.text()).toBe('登录')
  })

  it('shows role label and logout button for logged-in users on portal pages', async () => {
    const store = useUserStore()
    store.setToken('dummy')
    store.setRole('PREMIUM_VIP')
    store.setGlobalUserId('FA20260620212321')

    router.push('/home')
    await router.isReady()

    const wrapper = mount(App, {
      global: { plugins: [router] }
    })

    expect(wrapper.find('.role-pill').exists()).toBe(true)
    expect(wrapper.find('.role-pill').text()).toBe('PREMIUM_VIP')
    expect(wrapper.find('button.ghost-btn').text()).toBe('退出')
  })
})
