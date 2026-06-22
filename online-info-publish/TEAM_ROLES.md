# 网上信息发布子系统 - 团队分工（6人）

## 项目文件总览

```
后端 (src/main/java/com/stock/publish/)
├── PublishApplication.java          ← 已完成
├── config/                          ← 已完成（Redis/Redisson/WebMvc）
├── entity/                          ← 已完成（3张表的实体映射）
├── mapper/                          ← 已完成（3个BaseMapper接口）
├── dto/                             ← 已完成（ApiResponse/QuoteDTO/KLineDTO/TopTraderDTO）
├── service/StockService.java        ← 已完成（接口声明）
├── service/MarketService.java       ← 已完成（接口声明）
├── service/UserService.java         ← 已完成（接口声明）
│
├── interceptor/AuthInterceptor.java ← 待实现
├── interceptor/UserContext.java     ← 已完成
│
├── service/impl/StockServiceImpl.java   ← 待实现
├── service/impl/MarketServiceImpl.java  ← 待实现（最复杂）
├── service/impl/UserServiceImpl.java    ← 待实现
│
├── controller/StockController.java  ← 待实现
├── controller/MarketController.java ← 待实现
├── controller/UserController.java   ← 待实现
│
├── calculation/TopTraderEngine.java ← 待实现
├── calculation/KLineAggregator.java ← 待实现

前端 (publish-frontend/src/)
├── main.js                          ← 已完成
├── App.vue                          ← 空壳
├── router/index.js                  ← 空壳
├── stores/user.js                   ← 空壳
├── api/market.js                    ← 空壳
├── views/Portal.vue                 ← 空壳
├── views/StockDetail.vue            ← 空壳
├── components/KLineChart.vue        ← 空壳
└── components/UpgradeModal.vue      ← 空壳
```

---

## 后端 3 人

### B1：鉴权拦截与用户模块

**文件**：`AuthInterceptor.java`、`UserController.java`、`UserServiceImpl.java`

**要做的事**：

1. **AuthInterceptor**（核心）：从 Header 提取 `Authorization: Bearer {token}` → 无 Token 设 GUEST → 有 Token 则 HTTP POST 调用外部账户系统 `http://account-system/api/v1/auth/certificate-validate`（Mock 返回 `{"global_user_id":"U1001","certificate_bind":true}`）→ `certificate_bind==false` 或失败 → 降级 GUEST → 通过则查 `local_user_subscription`（无记录自动插入）→ 根据 `is_premium` 设 STANDARD 或 PREMIUM_VIP → 存入 `UserContext`（ThreadLocal）。请求结束后 `afterCompletion` 中清理 ThreadLocal。

2. **UserController**：`POST /user/upgrade` → 校验 STANDARD 角色 → 调 UserServiceImpl。

3. **UserServiceImpl**：按 `global_user_id` 查用户记录，更新 `is_premium=true`、`upgrade_time=now()`。

**外部依赖**：SSO 账户系统接口（Mock）

---

### B2：股票搜索与实时行情模块

**文件**：`StockController.java`、`StockServiceImpl.java`、`MarketController.java`（quote 部分）、`MarketServiceImpl.java`

**要做的事**：

1. **StockServiceImpl**：
   - `search(keyword)`：MyBatis-Plus 条件构造器，`stock_code LIKE keyword OR pinyin_abbr LIKE keyword`，LIMIT 10
   - `syncFromCentralSystem()`：从中央系统全量拉取股票列表，生成拼音首字母缩写（可用 `pinyin4j` 库或手动写简单映射），写入 `sync_stock_info`

2. **StockController**：`GET /stock/search?keyword={xx}`，GUEST 权限

3. **MarketServiceImpl**（最复杂）：
   - `@Scheduled(fixedRate=5000)` `refreshQuotes()`：每 5 秒 Mock 拉取成交流水（字段：stock_code, timestamp, buy_account, sell_account, deal_price, deal_quantity）
   - 计算 `last_price`（最新成交价）→ 计算 `change_rate = (last_price - yesterday_close) / yesterday_close × 100%`
   - 写入 Redis `quote:{stockCode}`，TTL 5 秒
   - 调用 B3 的 `TopTraderEngine.accumulate()` 累加主力
   - 将 tick 推入 Redis List `tick:{stockCode}`
   - `@Scheduled(initialDelay=300000, fixedRate=300000)` `aggregate5mKline()`：每 5 分钟从 Redis List 取出 tick → 聚合为 OHLCV → 写入 `kline_5m_data` → 清空 list
   - `getQuote(stockCode)`：读 Redis → **Cache Miss 时用 Redisson 分布式锁**（tryLock + double check + 单线程查库回填）→ 按角色屏蔽字段（GUEST 不返回 topBuyer/topSeller/bidPrice/askPrice 等）

4. **MarketController.quote**：`GET /market/quote/{stockCode}`

**依赖**：需要 B3 的 `TopTraderEngine`（先约定接口）

---

### B3：计算中心（K线聚合 + 技术指标 + 主力引擎）

**文件**：`TopTraderEngine.java`、`KLineAggregator.java`、`MarketController.java`（kline 部分）

**要做的事**：

1. **TopTraderEngine**：
   - `accumulate(stockCode, buyAccount, sellAccount, qty)`：执行 `HINCRBY top_buyer:{stockCode}:{yyyy-MM-dd} {account} {qty}` 和 `HINCRBY top_seller:{stockCode}:{yyyy-MM-dd} {account} {qty}`
   - `getTopBuyer(stockCode)` / `getTopSeller(stockCode)`：`HGETALL` 后按 value 排序取 Top 1

2. **KLineAggregator**（计算核心）：
   - `getKLineData(stockCode, period, start, end)`：查 `kline_5m_data` 表 → 根据 period 聚合：
     - `5M`：直接映射
     - `1H`：每 12 条 5M 聚合为 1 组
     - `1D`：每 48 条 5M 聚合（按交易日）
     - `1M`：从 1D 再聚合（~22 条日线）
     - `1Y`：从 1M 再聚合（12 条月线）
   - 聚合规则：Open=第1条，Close=最后1条，High=Max，Low=Min，Volume=Sum
   - `computeMA()`：MA5 = 近 5 周期收盘价均值，MA10 = 近 10 周期
   - `computeMACD()`：EMA12 = (close - prevEMA12) × (2/13) + prevEMA12；EMA26 同理；DIF = EMA12 − EMA26；DEA = (DIF − prevDEA) × (2/10) + prevDEA；MACD柱 = 2 × (DIF − DEA)

3. **MarketController.kline**：`GET /market/kline?stockCode={xx}&period={5M|1H|1D|1M|1Y}` → STANDARD 仅 1D，PREMIUM_VIP 全尺度，GUEST 拒绝

**依赖**：`Kline5mDataMapper`（已完成）

---

## 前端 3 人

### F1：基础设施 + 首页门户

**文件**：`router/index.js`、`stores/user.js`、`api/market.js`、`App.vue`、`views/Portal.vue`

**要做的事**：

1. **router/index.js**：`/` → Portal，`/stock/:code` → StockDetail，懒加载
2. **stores/user.js**（Pinia）：state: `token`, `role`(GUEST/STANDARD/PREMIUM_VIP), `globalUserId`；getters: `isGuest`, `isStandard`, `isPremiumVip`；actions: `setToken`(localStorage 持久化), `setRole`, `logout`
3. **api/market.js**（Axios）：baseURL `/api/publish`；请求拦截器注入 Authorization header；响应拦截器统一解包 `{code,message,data}`；导出 `searchStock/getQuote/getKLine/upgradeToVip`
4. **App.vue**：全局布局——顶部导航栏（左侧"网上信息发布"logo 标识当前系统、中间"交易系统/账户系统"跳转到其他子系统的入口链接、右侧登录/注册按钮或角色标签+退出）。注意：本系统内部页面切换（首页→详情页）通过 Vue Router 完成，不需要导航栏放本系统入口
5. **views/Portal.vue**：
   - 顶部全局搜索框（代码/拼音 → `searchStock()`）
   - 大盘指数面板（Mock 上证/深证）
   - 实时行情列表（`setInterval` 5 秒轮询，`onUnmounted` 中 `clearInterval`）
   - 点击行 → 路由跳转 `/stock/:code`
   - 登录按钮 → 跳转外部 SSO 页面

---

### F2：个股详情页

**文件**：`views/StockDetail.vue`

**要做的事**：

- 从 `route.params` 获取 `stockCode`
- `setInterval` 每 5 秒调 `getQuote(stockCode)` 刷新数据，`onUnmounted` 中 `clearInterval`
- 权限分层渲染：
  - GUEST：最新价、涨跌幅、股票名称（等价首页信息）
  - STANDARD+：额外显示买一/卖一盘口（价格+挂单量）、大买家/大卖家账号及成交量
- 集成 KLineChart 组件（F3 提供），默认展示日 K 线
- 监听 KLineChart 的 `need-upgrade` 事件 → 显示 UpgradeModal
- 页面布局：左侧 K 线图区 + 右侧盘口/主力面板

---

### F3：图表可视化 + VIP 升级

**文件**：`components/KLineChart.vue`、`components/UpgradeModal.vue`

**要做的事**：

1. **KLineChart.vue**（ECharts 组件）：
   - Props: `klineData`（KLineDTO 数组）、`period`（当前周期）
   - 阴阳蜡烛图：`close >= open` → 红色空心/实心阳线，`close < open` → 绿色实心阴线
   - 叠加均线：MA5（白色）、MA10（黄色）折线
   - 底部副图：MACD 柱状图（DIF 快线、DEA 慢线、红绿柱）
   - 周期切换按钮组：5M | 1H | 1D | 1M | 1Y → emit `change-period`
   - 非 VIP 点击非 1D 周期 → emit `need-upgrade`（父组件弹出升级弹窗）
   - `window resize` 时 `chart.resize()`
   - `onMounted` 初始化，`onUnmounted` dispose

2. **UpgradeModal.vue**：
   - Props: `visible`（Boolean）
   - 可见时：图表容器应用 `filter: blur(5px)` 高斯模糊
   - 居中弹窗：锁图标 + "升级 VIP 解锁多尺度 K 线与 MACD 指标"
   - 模拟支付按钮 → 调用 `upgradeToVip()` API → 更新 userStore → 关闭弹窗

---

## 接口依赖关系

```
F1(api/market.js)      ──HTTP──→  B1(AuthInterceptor 拦截所有请求)
F1(Portal)             ──HTTP──→  B2(StockController.search)
F1(Portal)+F2(Detail)  ──HTTP──→  B2(MarketController.quote)
F3(KLineChart)         ──HTTP──→  B3(MarketController.kline)
F3(UpgradeModal)       ──HTTP──→  B1(UserController.upgrade)

B2(MarketServiceImpl)  ──调用──→  B3(TopTraderEngine.accumulate)
B2(MarketServiceImpl)  ──写入──→  kline_5m_data 表
B3(KLineAggregator)    ──读取──→  kline_5m_data 表
B1(AuthInterceptor)    ──HTTP──→  外部账户系统(SSO)
```

---

## 建议开发顺序

```
第1轮（全员并行）：各自模块编译通过、能启动
第2轮（前后端对接）：B1 鉴权通了 → F1 的 API 层 + 首页能调通
第3轮（核心联调）：B2 行情通了 → F1 列表轮询 + F2 详情页盘口展示
第4轮（图表联调）：B3 K线通了 → F3 蜡烛图 + 指标展示
第5轮（收尾）：越权拦截体验（高斯模糊+升级弹窗）、清理定时器、resize 等
```
