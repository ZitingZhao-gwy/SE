import { describe, it, expect, vi, beforeEach } from 'vitest'
import api, { searchStock, getQuote, getKLine, upgradeToVip } from './market'

describe('Market API', () => {
  beforeEach(() => {
    // 清空对 mock 方法的调用记录
    vi.clearAllMocks()
  })

  it('searchStock should call correct endpoint with keyword', async () => {
    const spy = vi.spyOn(api, 'get').mockResolvedValue([])
    await searchStock('茅台')
    expect(spy).toHaveBeenCalledWith('/stock/search', { params: { keyword: '茅台' } })
  })

  it('getQuote should call correct endpoint with stock code', async () => {
    const spy = vi.spyOn(api, 'get').mockResolvedValue({})
    await getQuote('600519')
    expect(spy).toHaveBeenCalledWith('/market/quote/600519')
  })

  it('getKLine should use correct defaults and params', async () => {
    const spy = vi.spyOn(api, 'get').mockResolvedValue([])
    
    await getKLine('000001') // 测试默认周期
    expect(spy).toHaveBeenCalledWith('/market/kline', { params: { stockCode: '000001', period: '1D' } })
    
    await getKLine('000001', '5M') // 测试指定周期
    expect(spy).toHaveBeenCalledWith('/market/kline', { params: { stockCode: '000001', period: '5M' } })
  })

  it('upgradeToVip should make POST request', async () => {
    const spy = vi.spyOn(api, 'post').mockResolvedValue({ success: true })
    await upgradeToVip()
    expect(spy).toHaveBeenCalledWith('/user/upgrade')
  })
})