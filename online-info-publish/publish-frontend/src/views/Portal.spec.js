import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import Portal from './Portal.vue'
import * as marketApi from '../api/market'

// 伪造 Router
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: Portal },
    { name: 'StockDetail', path: '/stock/:code', component: { template: '<div>Detail</div>' } }
  ]
})

// Mock API
vi.mock('../api/market', () => ({
  getQuote: vi.fn(),
  searchStock: vi.fn()
}))

describe('Portal.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    
    marketApi.getQuote.mockResolvedValue({
      stock_code: '600519', stock_name: '贵州茅台', last_price: 1680.5, change_rate: 1.2
    })
    marketApi.searchStock.mockResolvedValue([
      { stock_code: '000001', stock_name: '平安银行' }
    ])
  })

  afterEach(() => {
    vi.runOnlyPendingTimers()
    vi.useRealTimers()
  })

  it('renders index cards and search layout', async () => {
    router.push('/')
    await router.isReady()
    
    const wrapper = mount(Portal, {
      global: { plugins: [router] }
    })
    
    expect(wrapper.text()).toContain('上证指数')
    expect(wrapper.text()).toContain('深证成指')
    expect(wrapper.text()).toContain('实时行情')
  })

  it('calls search API when typing and hitting enter', async () => {
    const wrapper = mount(Portal, { global: { plugins: [router] } })
    
    const input = wrapper.find('input#stockSearch')
    await input.setValue('000001')
    await input.trigger('keyup.enter')
    
    expect(marketApi.searchStock).toHaveBeenCalledWith('000001')
  })
})