import { describe, it, expect } from 'vitest'
import router from './index'

describe('Vue Router Configuration', () => {
  it('should map root path to Navigation route', () => {
    const routes = router.getRoutes()
    const navRoute = routes.find(r => r.name === 'Navigation')
    expect(navRoute).toBeDefined()
    expect(navRoute.path).toBe('/')
  })

  it('should map info-publish home to Portal route', () => {
    const routes = router.getRoutes()
    const portalRoute = routes.find(r => r.name === 'Portal')
    expect(portalRoute).toBeDefined()
    expect(portalRoute.path).toBe('/home')
  })

  it('should map stock detail path properly', () => {
    const routes = router.getRoutes()
    const detailRoute = routes.find(r => r.name === 'StockDetail')
    expect(detailRoute).toBeDefined()
    expect(detailRoute.path).toBe('/stock/:code')
  })
})