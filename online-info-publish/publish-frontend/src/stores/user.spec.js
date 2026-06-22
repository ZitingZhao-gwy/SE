import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useUserStore } from './user'

describe('User Store', () => {
  beforeEach(() => {
    // 每次测试前激活新的 Pinia 实例并清空 localStorage
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('should initialize with default guest state', () => {
    const store = useUserStore()
    expect(store.token).toBe('')
    expect(store.role).toBe('GUEST')
    expect(store.globalUserId).toBe('')
    expect(store.isGuest).toBe(true)
    expect(store.isStandard).toBe(false)
    expect(store.isPremiumVip).toBe(false)
  })

  it('should properly set token and role', () => {
    const store = useUserStore()
    
    store.setToken('test-token-123')
    store.setRole('PREMIUM_VIP')
    store.setGlobalUserId('U1001')
    
    expect(store.token).toBe('test-token-123')
    expect(store.role).toBe('PREMIUM_VIP')
    expect(store.globalUserId).toBe('U1001')
    
    expect(store.isPremiumVip).toBe(true)
    expect(localStorage.getItem('publish_token')).toBe('test-token-123')
    expect(localStorage.getItem('publish_role')).toBe('PREMIUM_VIP')
  })

  it('should clear state on logout', () => {
    const store = useUserStore()
    
    store.setToken('temp-token')
    store.setRole('STANDARD')
    store.logout()
    
    expect(store.token).toBe('')
    expect(store.role).toBe('GUEST')
    expect(store.globalUserId).toBe('')
    
    expect(localStorage.getItem('publish_token')).toBeNull()
    expect(localStorage.getItem('publish_role')).toBe('GUEST')
  })
})