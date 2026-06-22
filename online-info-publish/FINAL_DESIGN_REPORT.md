# 网上信息发布子系统 — 最终设计报告 V1.0

> 基于 mock 数据模拟的独立可运行版本。记录实际实现与联调过程中的全部设计决策。

---

## 1. 系统概述

"网上信息发布子系统"是股票交易生态中的数据视窗。**不处理交易**，消费中央交易系统的成交流水，在后端实时计算盘口、K 线、主力动向，按三层权限向前端分发。

### 1.1 运行环境

| 项 | 配置 |
|----|------|
| JDK | 17 (Temurin) |
| 构建 | Maven 3.9 |
| 数据库 | MySQL 8.0 (root/root, stock_publish) |
| 缓存 | Redis 5.0 (localhost:6379) |
| 后端 | Spring Boot 3.3.5, port 8080, context-path /api/publish |
| 前端 | Vite 8 + Vue 3, port 3000, 代理 → 8080 |

### 1.2 模块架构

```
┌──────────────────────────────────────────────┐
│                  前端 Vue 3                    │
│  Portal → StockDetail ← KLineChart/Upgrade   │
│  Pinia(store) ← Axios(api) ← VueRouter       │
└──────────────────┬───────────────────────────┘
                   │ HTTP /api/publish
┌──────────────────▼───────────────────────────┐
│               AuthInterceptor                 │
│    Token → Mock SSO → UserContext(ThreadLocal) │
└──────────────────┬───────────────────────────┘
                   │
┌──────────────────▼───────────────────────────┐
│  Controller: Stock / Market / User            │
│  Service: StockService / MarketService        │
│  Calculation: TopTraderEngine / KLineAggregato│
└────────┬──────────────┬──────────────────────┘
         │              │
    ┌────▼────┐    ┌────▼────┐
    │  MySQL  │    │  Redis  │
    │3 tables │    │quote+tick│
    └─────────┘    │+trader+  │
                   │lock      │
                   └──────────┘
```

---

## 2. 数据库设计

### 2.1 local_user_subscription

```sql
CREATE TABLE local_user_subscription (
    id INT AUTO_INCREMENT PRIMARY KEY,
    global_user_id VARCHAR(50) NOT NULL UNIQUE,  -- 对应账户系统的 fund_acc_no
    is_premium BOOLEAN NOT NULL DEFAULT FALSE,
    upgrade_time DATETIME NULL
);
```

### 2.2 sync_stock_info

```sql
CREATE TABLE sync_stock_info (
    stock_code CHAR(6) PRIMARY KEY,
    stock_name VARCHAR(100) NOT NULL,
    stock_type INT NOT NULL,           -- 0:普通, 1:ST
    yesterday_close DECIMAL(10,2) NOT NULL,
    limit_rate DECIMAL(5,4) NOT NULL,
    status INT NOT NULL,               -- 0:正常, 1:暂停
    pinyin_abbr VARCHAR(20)            -- 本地生成，极速模糊搜索
);
```

### 2.3 kline_5m_data

```sql
CREATE TABLE kline_5m_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code CHAR(6) NOT NULL,
    period_start_time DATETIME NOT NULL,  -- 5分钟窗口起点
    open_price DECIMAL(10,2) NOT NULL,
    close_price DECIMAL(10,2) NOT NULL,
    high_price DECIMAL(10,2) NOT NULL,
    low_price DECIMAL(10,2) NOT NULL,
    volume BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_code_time (stock_code, period_start_time)
);
```

> 设计决策：仅存 5 分钟线作为最小基准尺度。1H/1D/1W/1M/1Y 均由后端从 5M 数据动态聚合。不冗余存储更长周期数据。

### 2.4 种子数据

| 股票 | 昨收 | 类型 |
|------|------|------|
| 600519 贵州茅台 | 1660.00 | 普通 |
| 000001 平安银行 | 12.30 | 普通 |
| 000858 五粮液 | 166.90 | 普通 |
| 300750 宁德时代 | 438.40 | 普通 |
| 600036 招商银行 | 38.50 | 普通 |
| 601318 中国平安 | 45.60 | 普通 |

| global_user_id | is_premium | 备注 |
|----------------|------------|------|
| F0001 | false | STANDARD 测试账号 |
| F0002 | true | PREMIUM_VIP 测试账号 |

---

## 3. 用户权限三层体系

### 3.1 判定流程

```
HTTP 请求 → AuthInterceptor.preHandle()
  │
  ├── 无 Authorization header → GUEST
  ├── Token = "invalid_token"  → GUEST (Mock: 登录失败)
  ├── Token = "no_cert_token"  → GUEST (Mock: 证书未绑定)
  ├── Token = "valid_token"    → fundAccNo="F0001" → DB is_premium=false → STANDARD
  └── Token = "vip_token"      → fundAccNo="F0002" → DB is_premium=true  → PREMIUM_VIP
```

### 3.2 权限矩阵

| 功能 | GUEST | STANDARD | PREMIUM_VIP |
|------|-------|----------|-------------|
| 首页大盘 | ✅ | ✅ | ✅ |
| 股票搜索 | ✅ | ✅ | ✅ |
| 实时价格 | ✅ | ✅ | ✅ |
| 买一/卖一价 | ❌ | ✅ | ✅ |
| 主力动向 Top1 | ❌ | ✅ | ✅ |
| K 线（5M/15M/30M/1H/1D） | ❌ | ✅ | ✅ |
| K 线（1W/1M/1Y） | ❌ | ❌ | ✅ |
| MA5/MA10 | — | ✅ | ✅ |
| MACD (DIF/DEA/Bar) | — | ❌ | ✅ |
| VIP 升级 | ❌ | ✅ | — |

> 设计决策：GUEST 鉴权失败不返回 401，统一降级 GUEST，"渐进式权限引导"。STANDARD 保留 MA5/MA10（行业惯例：免费行情软件均免费给均线），MACD 需 VIP 解锁。

---

## 4. API 接口

### 4.1 股票搜索

```
GET /api/publish/stock/search?keyword={xx}
权限: GUEST
逻辑: sync_stock_info 表 stock_code / stock_name / pinyin_abbr 三字段 LIKE，LIMIT 10
```

> 设计决策：搜索覆盖股票名称（字串包含），示例"茅台"→"贵州茅台"、"平安"→"平安银行"+"中国平安"。

### 4.2 实时行情

```
GET /api/publish/market/quote/{stock_code}
权限: GUEST(仅价格) / STANDARD(完整)
来源: Redis quote:{code} (TTL 5s)
Cache Miss → Redisson 分布式锁 → double check → DB 兜底

响应: {
  stockCode, stockName, lastPrice, yesterdayClose, changeRate,
  topBuyer: {account, qty}, topSeller: {account, qty},
  bidPrice, askPrice, bidVolume, askVolume
}
```

> 设计决策：bid/ask 字段来自中央交易系统的委托盘口快照（与成交流水合并推送）。本系统 Mock 生成 bid=lastPrice-0.01, ask=lastPrice+0.01。GUEST 请求时 maskByRole() 清除 topBuyer/topSeller/bid/ask。

### 4.3 K 线数据

```
GET /api/publish/market/kline?stock_code={xx}&period={P}
权限: GUEST 拒绝 / STANDARD(5M~1D) / VIP(全8尺度)

period 支持: 5M, 15M, 30M, 1H, 1D, 1W, 1M, 1Y
聚合规则: 从 kline_5m_data 按 chunkSize 动态聚合
  - 5M=1, 15M=3, 30M=6, 1H=12, 1D=48, 1W=240, 1M=1056, 1Y=12000

响应: [{
  time, open, close, high, low, volume,
  ma5, ma10,
  dif, dea, macdBar    // STANDARD 时为 null
}]
```

> 设计决策：STANDARD 看 5 个常用尺度（对标同花顺免费版），VIP 解锁 1W/1M/1Y。MA5/MA10 公开，MACD VIP 专属。前端按钮用中文标签（5分/15分/30分/1时/日/周/月/年）消除 M 歧义。

### 4.4 用户信息 + 升级

```
GET /api/publish/user/me
返回: { globalUserId, role, isPremium }
权限: 全部

POST /api/publish/user/upgrade
权限: STANDARD（GUEST 拒绝，VIP 拒绝重复）
逻辑: UPDATE local_user_subscription SET is_premium=true, upgrade_time=NOW()
```

> 设计决策：/user/me 接口让前端同步后端真实角色，替代硬编码 role 赋值。来自 B1 同学 PR #9 的启发。

---

## 5. 数据流

### 5.1 5 秒行情刷新链

```
@Scheduled(cron = "*/5 * * * * *")  refreshQuotes()
  │
  ├── mockTransactions()    6 只股票 × 1~3 笔 Mock 成交
  │   ├── 价格: 昨收 ±0.5% 随机波动
  │   └── 账户: B_{code} / S_{code}（固定，供主力累积）
  │
  ├── 按时间戳排序 → Map 去重取最新价 → 算 changeRate
  │
  ├── buildQuote() + 补 bid/ask Mock
  │
  ├── Redis SET quote:{code} = JSON, TTL 5s
  │
  ├── TopTraderEngine.accumulate() → HINCRBY
  │
  └── Redis RPUSH tick:{code} = tick JSON
```

### 5.2 5 分钟 K 线落盘

```
@Scheduled(cron = "0 */5 * * * *")  aggregate5mKline()
  │
  ├── Redis LRANGE tick:{code} 0 -1
  ├── JSON 反序列化 → List<TransactionRecord>
  ├── OHLCV 聚合: O=首条, C=末条, H=MAX, L=MIN, V=SUM
  ├── INSERT kline_5m_data (DuplicateKeyException 兜底)
  └── Redis DEL tick:{code}
```

### 5.3 K 线动态聚合

```
kLineAggregator.getKLineData(code, period, start, end)
  │
  ├── SELECT * FROM kline_5m_data WHERE code=? AND time BETWEEN ? AND ?
  ├── Entity → KLineDTO (time 格式化为 "yyyy-MM-dd HH:mm")
  ├── 按 chunkSize 分组聚合
  ├── computeMA() → MA5, MA10
  ├── computeMACD() → EMA12/EMA26 → DIF → DEA → MACD柱
  └── STANDARD 角色时清 dif/dea/macdBar
```

### 5.4 行情读取（防击穿）

```
getQuote(code)
  │
  ├── Redis GET quote:{code} → 命中 → maskByRole → 返回
  │
  ├── 未命中 → Redisson RLock tryLock(3s, 10s)
  │   ├── double check Redis
  │   ├── buildQuote() → 查 sync_stock_info + kline_5m_data
  │   ├── Redis SET 回填 (TTL 5s)
  │   └── unlock
  │
  └── 未抢到锁 → sleep 100ms → 再读 Redis → 仍空则 null
```

---

## 6. Redis 全景

| Key | 类型 | TTL | 说明 |
|-----|------|-----|------|
| `quote:{code}` | String(JSON) | 5s | 实时行情快照 |
| `tick:{code}` | List | — | 5秒推送累积，5分钟消费清空 |
| `top_buyer:{code}:{date}` | Hash | 当日 | HINCRBY 累加买入量 |
| `top_seller:{code}:{date}` | Hash | 当日 | HINCRBY 累加卖出量 |
| `lock:quote:{code}` | RLock | 自动续期 | 防缓存击穿 |

---

## 7. 前端架构

### 7.1 路由

| 路径 | 组件 | 说明 |
|------|------|------|
| `/` | Portal.vue | 首页：搜索+大盘+6只股票行情列表(5s轮询) |
| `/stock/:code` | StockDetail.vue | 个股：价格卡片+K线图+盘口+主力 |

### 7.2 状态管理 (Pinia)

```
userStore: token, role, globalUserId
  computed: isGuest, isStandard, isPremiumVip
  actions: setToken, setRole, setGlobalUserId, logout
```

token 通过 localStorage 持久化，刷新不丢失。

### 7.3 组件树

```
App.vue
├── Portal.vue
│   ├── 搜索框 → searchStock()
│   ├── 大盘指数（Mock）
│   └── 行情列表 → getQuote() × 5s轮询 → router.push(/stock/:code)
└── StockDetail.vue
    ├── 价格卡片 → getQuote() × 5s轮询
    ├── KLineChart.vue（ECharts蜡烛图）
    │   ├── 周期按钮（5分~年）
    │   └── MACD副图（hasMACD 按需显示）
    ├── 盘口面板
    ├── 主力动向面板
    └── UpgradeModal.vue（高斯模糊+双层弹窗）
```

### 7.4 Mock 登录

顶部导航提供两个按钮：

| 按钮 | Token | 角色 |
|------|-------|------|
| 登录(STANDARD) | valid_token | STANDARD |
| 登录(VIP) | vip_token | PREMIUM_VIP |

> 设计决策：联调阶段不使用外部 SSO。生产环境需替换为真实跳转。刷新页面后 token 从 localStorage 恢复，保持登录态。点"退出"清除 token 回到 GUEST。

### 7.5 越权拦截体验

STANDARD 点击 VIP 专属周期（1W/1M/1Y）→ 图表区域 CSS `filter: blur(5px)` → 居中弹窗"升级VIP" → 模拟支付 → POST /user/upgrade → role 变更为 PREMIUM_VIP。

---

## 8. 定时任务

| 任务 | 调度方式 | 说明 |
|------|----------|------|
| refreshQuotes | cron `*/5 * * * * *` | 每 5 秒 |
| aggregate5mKline | cron `0 */5 * * * *` | 整 5 分钟（:00, :05, :10...） |

> 设计决策：5 分钟 K 线落盘使用 cron 而非 fixedRate。cron 按绝对时钟触发，休眠恢复后自动在下一个整 5 分钟对齐，不会出现 14:52 这类偏移时间戳。Insert 加 DuplicateKeyException 兜底防重复入库。

---

## 9. 开发过程中的关键设计决策

| 决策 | 原方案 | 最终方案 | 理由 |
|------|--------|----------|------|
| K 线存储 | kline_data 含 period_type | kline_5m_data 仅存 5M | 避免冗余，长周期动态聚合 |
| 鉴权失败 | 返回 401 | 降级 GUEST | 渐进式权限引导 |
| 盘口字段 | 最初有，后来删了 | 保留 | 追加委托盘口接口 |
| STANDARD 权限 | 仅 1D K 线 | 5 个常用尺度 | 对标同花顺免费版 |
| 技术指标 | 最初不确定 | STANDARD=MA, VIP=MA+MACD | MA 行业免费惯例 |
| 定时调度 | fixedRate | cron | 休眠恢复后时间对齐 |
| 前端按钮 | 5M/1H/1D 等 | 5分/15分/30分/1时/日 | 消除 M 歧义 |
| 搜索 | 仅代码+拼音 | 加股票名称 | 支持"茅台"搜"贵州茅台" |
| 端口代理 | 最初配置 | F1 merge 后丢失 | vite.config.js 恢复 proxy |
| 字符编码 | application.yml 缺失 | UTF-8 force | 中文搜索不乱码 |
| QutoDTO | — | 含 topBuyer/topSeller/bid/ask | 合并盘口快照 |
| /user/me | 没有 | 新增 | 前端同步真实角色 |
| 1 分钟 K 线 | — | 不做 | 基准数据为 5 分钟 |

---

## 10. 测试

- 37 单元测试全绿（B1 12 + B2 18 + B3 7）
- 10 分钟集成监控通过（6 只股票、K 线生成、MA/MACD 计算）
- 测试记录：INTEGRATION_TEST_LOG.md

---

## 11. 启动

```bash
# 一键
bash start-all.sh

# 或手动
# MySQL: C:/Users/JY/mysql/bin/mysqld.exe
# Redis: ./redis-server.exe
# 后端: java -jar target/online-info-publish-subsys-1.0.0-SNAPSHOT.jar
# 前端: cd publish-frontend && npm run dev
```

浏览器 http://localhost:3000
