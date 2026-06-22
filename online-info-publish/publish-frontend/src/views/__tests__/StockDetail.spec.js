import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

// ---- 当前登录角色（各用例在 mount 前赋值），通过 mock 注入到 useUserStore ----
let mockRole = 'GUEST'

// ---- mock 外部依赖：让 F2 脱离 F1/F3 与后端独立可测 ----
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { code: '600519' } }),
}))

vi.mock('../../stores/user', () => ({
  useUserStore: () => ({
    get role() {
      return mockRole
    },
  }),
}))

vi.mock('../../api/market', () => ({
  getQuote: vi.fn(),
  getKLine: vi.fn(),
}))

import { getQuote, getKLine } from '../../api/market'
import StockDetail from '../StockDetail.vue'

// ---- 子组件桩（替换 F3 尚未实现的真实组件，并可手动触发事件） ----
const KLineChartStub = {
  name: 'KLineChart',
  props: ['klineData', 'period'],
  emits: ['change-period', 'need-upgrade'],
  template: '<div class="kline-stub"></div>',
}
const UpgradeModalStub = {
  name: 'UpgradeModal',
  props: ['visible'],
  emits: ['close'],
  template: '<div class="upgrade-stub"></div>',
}

// ---- 标准行情样例（STANDARD+ 含盘口/主力字段） ----
const sampleQuote = {
  stockCode: '600519',
  stockName: '贵州茅台',
  lastPrice: 1680.0,
  yesterdayClose: 1660.0,
  changeRate: '+1.20%',
  status: 0,
  topBuyer: { account: 'A1001', qty: 5000 },
  topSeller: { account: 'B2001', qty: 4500 },
  bidPrice: 1679.99,
  askPrice: 1680.01,
  bidVolume: 15000,
  askVolume: 12000,
}

function mountDetail() {
  return mount(StockDetail, {
    global: {
      stubs: { KLineChart: KLineChartStub, UpgradeModal: UpgradeModalStub },
    },
  })
}

describe('F2 个股详情页 StockDetail.vue', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mockRole = 'GUEST'
    getQuote.mockReset()
    getKLine.mockReset()
    getQuote.mockResolvedValue({ ...sampleQuote })
    getKLine.mockResolvedValue([])
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('TC-F2-001 挂载后立即拉取一次行情（携带股票代码）', async () => {
    mockRole = 'STANDARD'
    mountDetail()
    await flushPromises()
    expect(getQuote).toHaveBeenCalledTimes(1)
    expect(getQuote).toHaveBeenCalledWith('600519')
  })

  it('TC-F2-002 每 5 秒轮询一次行情', async () => {
    mockRole = 'STANDARD'
    mountDetail()
    await flushPromises()
    expect(getQuote).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(5000)
    await flushPromises()
    expect(getQuote).toHaveBeenCalledTimes(2)

    vi.advanceTimersByTime(5000)
    await flushPromises()
    expect(getQuote).toHaveBeenCalledTimes(3)
  })

  it('TC-F2-003 卸载后清理定时器，不再轮询（防内存泄漏）', async () => {
    mockRole = 'STANDARD'
    const clearSpy = vi.spyOn(globalThis, 'clearInterval')
    const wrapper = mountDetail()
    await flushPromises()
    expect(getQuote).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    expect(clearSpy).toHaveBeenCalled()

    vi.advanceTimersByTime(15000)
    await flushPromises()
    expect(getQuote).toHaveBeenCalledTimes(1) // 卸载后无新增调用
  })

  it('TC-F2-004 GUEST 仅显示价格，无盘口/主力/图表', async () => {
    mockRole = 'GUEST'
    const wrapper = mountDetail()
    await flushPromises()

    expect(wrapper.find('.stock-name').text()).toBe('贵州茅台')
    expect(wrapper.find('.last-price').text()).toBe('1,680.00')
    expect(wrapper.find('.change-rate').text()).toContain('+1.20%')

    expect(wrapper.find('.orderbook-panel').exists()).toBe(false)
    expect(wrapper.find('.trader-panel').exists()).toBe(false)
    expect(wrapper.findComponent(KLineChartStub).exists()).toBe(false)
  })

  it('TC-F2-005 STANDARD 显示买一/卖一盘口（价格+挂单量）', async () => {
    mockRole = 'STANDARD'
    const wrapper = mountDetail()
    await flushPromises()

    expect(wrapper.find('.orderbook-panel').exists()).toBe(true)
    expect(wrapper.find('.ask-price').text()).toBe('1,680.01')
    expect(wrapper.find('.ask-volume').text()).toBe('12000')
    expect(wrapper.find('.bid-price').text()).toBe('1,679.99')
    expect(wrapper.find('.bid-volume').text()).toBe('15000')
  })

  it('TC-F2-006 STANDARD 显示大买家/大卖家（账号+成交量）', async () => {
    mockRole = 'STANDARD'
    const wrapper = mountDetail()
    await flushPromises()

    const buyer = wrapper.find('.top-buyer')
    const seller = wrapper.find('.top-seller')
    expect(buyer.find('.account').text()).toBe('A1001')
    expect(buyer.find('.qty').text()).toBe('5000')
    expect(seller.find('.account').text()).toBe('B2001')
    expect(seller.find('.qty').text()).toBe('4500')
  })

  it('TC-F2-007 涨跌着色：涨标红、跌标绿', async () => {
    mockRole = 'GUEST'

    getQuote.mockResolvedValueOnce({ ...sampleQuote, changeRate: '+1.20%' })
    const up = mountDetail()
    await flushPromises()
    expect(up.find('.change-rate').classes()).toContain('up')
    up.unmount()

    getQuote.mockResolvedValueOnce({ ...sampleQuote, changeRate: '-0.80%' })
    const down = mountDetail()
    await flushPromises()
    expect(down.find('.change-rate').classes()).toContain('down')
  })

  it('TC-F2-008 KLineChart 触发 need-upgrade → 弹出升级窗', async () => {
    mockRole = 'STANDARD'
    const wrapper = mountDetail()
    await flushPromises()

    expect(wrapper.findComponent(UpgradeModalStub).props('visible')).toBe(false)
    wrapper.findComponent(KLineChartStub).vm.$emit('need-upgrade')
    await flushPromises()
    expect(wrapper.findComponent(UpgradeModalStub).props('visible')).toBe(true)
  })

  it('TC-F2-009 KLineChart 触发 change-period → 按新周期拉取 K 线', async () => {
    mockRole = 'STANDARD'
    const wrapper = mountDetail()
    await flushPromises()

    getKLine.mockClear()
    wrapper.findComponent(KLineChartStub).vm.$emit('change-period', '1H')
    await flushPromises()
    expect(getKLine).toHaveBeenCalledWith('600519', '1H')
  })

  it('TC-F2-010 停牌（status=1）显示“停牌”标识', async () => {
    mockRole = 'GUEST'
    getQuote.mockResolvedValueOnce({ ...sampleQuote, status: 1 })
    const wrapper = mountDetail()
    await flushPromises()

    expect(wrapper.find('.suspended-badge').exists()).toBe(true)
    expect(wrapper.find('.suspended-badge').text()).toBe('停牌')
  })

  it('TC-F2-011 行情接口异常时显示错误提示且不崩溃', async () => {
    mockRole = 'STANDARD'
    getQuote.mockReset()
    getQuote.mockRejectedValue(new Error('network error'))
    const wrapper = mountDetail()
    await flushPromises()

    expect(wrapper.find('.error-bar').exists()).toBe(true)
    expect(wrapper.find('.stock-detail').exists()).toBe(true) // 组件未崩溃
  })
})
