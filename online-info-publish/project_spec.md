# 网上信息发布子系统 — 完整设计规格 V4.0

> 合并自 `project_spec.md` 与 `overall-guideline.md`，经团队讨论修正所有冲突点。

---

## 1. 系统定位

本系统是股票交易生态中的"数据视窗与计算引擎"。

- **不处理交易**：不产生订单、不撮合。仅作为中央交易系统的下游消费数据。
- **不存密码**：用户身份依赖外部证券/资金账户系统（SSO），本系统仅维护增值服务状态。
- **计算中心**：消费原始成交流水 → 实时计算盘口快照、主力动向、多尺度 K 线及技术指标。

---

## 2. 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 17, Spring Boot 3.3, MyBatis-Plus 3.5, Redis (Lettuce + Redisson), MySQL 8.0 |
| 前端 | Vue 3 (Composition API), Vite, Vue Router 4, Pinia, Axios, ECharts |

---

## 3. 用户权限三层体系

| 角色 | 判定条件 | 权限范围 |
|------|----------|----------|
| **GUEST** | 无 Token，或 Token 无效，或 `certificate_bind==false` | 首页大盘、股票列表、模糊搜索、实时价格 |
| **STANDARD** | Token 有效 + 证书已绑定 + 本地 `is_premium=false` | + 买一/卖一盘口、主力动向(Top1)、日K线(仅1D) |
| **PREMIUM_VIP** | STANDARD 基础上 `is_premium=true` | + 全尺度K线(5M/1H/1D/1M/1Y)、MA5/MA10、MACD |

鉴权失败不返回 401，统一降级为 GUEST，前端按"游客模式"展示（渐进式权限引导）。

---

## 4. 数据库设计 (MySQL 8.0)

严禁物理外键和级联删除，数据完整性由应用层保障。

### 4.1 用户订阅信息表

```sql
CREATE TABLE local_user_subscription (
    id INT AUTO_INCREMENT PRIMARY KEY COMMENT '本地自增主键',
    global_user_id VARCHAR(50) NOT NULL UNIQUE COMMENT '外部用户唯一标识',
    is_premium BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为VIP',
    upgrade_time DATETIME NULL COMMENT '升级VIP时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户订阅信息表';
```

### 4.2 股票基础信息只读副本表

数据源自中央交易系统，本地生成拼音首字母缩写，支撑极速模糊搜索。

```sql
CREATE TABLE sync_stock_info (
    stock_code CHAR(6) PRIMARY KEY COMMENT '股票代码',
    stock_name VARCHAR(100) NOT NULL COMMENT '股票名称',
    stock_type INT NOT NULL COMMENT '股票类型(0:普通, 1:ST)',
    yesterday_close DECIMAL(10,2) NOT NULL COMMENT '昨日收盘价(计算涨跌幅基准)',
    limit_rate DECIMAL(5,4) NOT NULL COMMENT '涨跌幅比例',
    status INT NOT NULL COMMENT '交易状态(0:正常, 1:暂停)',
    pinyin_abbr VARCHAR(20) COMMENT '拼音首字母缩写(本地生成)'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股票基础信息只读副本表';
```

### 4.3 最小尺度 K 线基准表

仅存 5 分钟线。更长周期（1H/1D/1M/1Y）由后端动态聚合，不冗余存储。

```sql
CREATE TABLE kline_5m_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '物理主键',
    stock_code CHAR(6) NOT NULL COMMENT '股票代码',
    period_start_time DATETIME NOT NULL COMMENT '5分钟周期的开始时间',
    open_price DECIMAL(10,2) NOT NULL COMMENT '开盘价',
    close_price DECIMAL(10,2) NOT NULL COMMENT '收盘价',
    high_price DECIMAL(10,2) NOT NULL COMMENT '最高价',
    low_price DECIMAL(10,2) NOT NULL COMMENT '最低价',
    volume BIGINT NOT NULL DEFAULT 0 COMMENT '成交量',
    UNIQUE KEY uk_code_time (stock_code, period_start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='最小尺度K线基准表(5分钟)';
```

---

## 5. Redis 缓存设计

| 用途 | Key 格式 | 数据结构 | TTL | 说明 |
|------|----------|----------|-----|------|
| 实时盘口 | `quote:{stockCode}` | JSON String | 5s | 最新价、涨跌幅、买一/卖一、挂单量、主力Top1 |
| 主力买入累加 | `top_buyer:{stockCode}:{yyyy-MM-dd}` | Hash | 当日 | `{accountId} → 买入量`，HINCRBY 累加 |
| 主力卖出累加 | `top_seller:{stockCode}:{yyyy-MM-dd}` | Hash | 当日 | `{accountId} → 卖出量`，HINCRBY 累加 |
| Tick 流水暂存 | `tick:{stockCode}` | List | — | 5 秒轮询推入，5 分钟聚合消费后清空 |
| 分布式锁 | `lock:quote:{stockCode}` | Redisson RLock | 自动续期 | Cache Miss 时防击穿 |

**不进 Redis 的**：K 线历史（直接查 MySQL）、股票基础信息（查 `sync_stock_info` 表）。

---

## 6. 核心算法与机制

### 6.1 统一鉴权拦截器 (AuthInterceptor)

拦截所有 `/api/publish/**` 请求：

1. 从 Header 提取 `Authorization: Bearer {token}`
2. 无 Token → ThreadLocal 设为 GUEST
3. 有 Token → HTTP POST `http://account-system/api/v1/auth/certificate-validate`
   - Mock 返回：`{"global_user_id": "U1001", "certificate_bind": true}`
4. 请求失败 或 `certificate_bind == false` → 降级 GUEST（不返回 401）
5. 校验通过 → 查 `local_user_subscription`（无记录自动插入），根据 `is_premium` 设 STANDARD 或 PREMIUM_VIP
6. 将 `global_user_id` 和 Role 存入 ThreadLocal (`UserContext`)
7. 请求结束后 `afterCompletion` 清理 ThreadLocal

### 6.2 实时行情缓存与防击穿 (MarketService)

**定时任务（每 5 秒）**：
1. 从中央系统拉取最新成交流水（Mock），每条记录含：`stock_code, timestamp, buy_account_id, sell_account_id, deal_price, deal_quantity`
2. 计算 `last_price` ← 最新 `deal_price`
3. 计算涨跌幅：`change_rate = (last_price - yesterday_close) / yesterday_close × 100%`
4. 写入 Redis `quote:{stockCode}`，TTL 5s
5. 将 tick 推入 Redis List `tick:{stockCode}`
6. 调用 TopTraderEngine 累加主力买卖数据

**接口读取（getQuote）**：
1. 读 Redis `quote:{stockCode}`
2. Cache Miss → Redisson 分布式锁 `tryLock(3s, 10s)` → double check → 查库回填
3. 按角色屏蔽字段：GUEST 不返回 topBuyer/topSeller/bidPrice/askPrice

### 6.3 5 分钟 K 线落盘

**定时任务（每 5 分钟）**：
1. 从 Redis `tick:{stockCode}` 取出过去 5 分钟的所有 tick
2. 聚合为 OHLCV：Open=首条价格，Close=末条价格，High=Max，Low=Min，Volume=Sum
3. 写入 `kline_5m_data` 表（利用 `uk_code_time` 唯一索引防重复）
4. 清空 Redis List

### 6.4 K 线动态聚合 (KLineAggregator)

1. 从 `kline_5m_data` 查出时间段内所有数据
2. 按目标周期分组：

| 周期 | 聚合规则 |
|------|----------|
| 5M | 直接映射，不聚合 |
| 1H | 每 12 条 5M 为 1 组 |
| 1D | 每 48 条 5M 为 1 组（约 4 小时交易时段） |
| 1M | 从日线聚合（~22 条/组） |
| 1Y | 从月线聚合（12 条/组） |

3. 聚合算法：Open=首条，Close=末条，High=组内 Max，Low=组内 Min，Volume=组内 Sum

### 6.5 技术指标计算

**MA（移动平均线）**：基于聚合后的收盘价数组计算。

- MA5 = 近 5 个周期收盘价的算术平均值
- MA10 = 近 10 个周期收盘价的算术平均值
- 不足周期数时返回 null

**MACD（指数平滑异同移动平均线）**：

```
EMA12(t) = (Close(t) − EMA12(t−1)) × (2/13) + EMA12(t−1)
EMA26(t) = (Close(t) − EMA26(t−1)) × (2/27) + EMA26(t−1)
DIF = EMA12 − EMA26
DEA(t) = (DIF(t) − DEA(t−1)) × (2/10) + DEA(t−1)
MACD柱 = 2 × (DIF − DEA)
```

首日 EMA 以当日收盘价初始化。

### 6.6 主力动向计算 (TopTraderEngine)

1. 每收到一条成交流水，执行：
   - `HINCRBY top_buyer:{stockCode}:{date} {buy_account_id} {quantity}`
   - `HINCRBY top_seller:{stockCode}:{date} {sell_account_id} {quantity}`
2. 查询时对 Hash 按 value 降序排列，返回 Top 1 的账号及数量

---

## 7. API 接口契约

### 7.1 本系统对外提供接口

**股票模糊搜索**
```
GET /api/publish/stock/search?keyword={xx}
权限：GUEST
逻辑：查 sync_stock_info，stock_code 或 pinyin_abbr 模糊匹配，LIMIT 10
```

**实时行情与主力动向**
```
GET /api/publish/market/quote/{stock_code}
权限：GUEST（仅价格/涨跌幅） / STANDARD（附加 top_buyer/top_seller/盘口）
来源：Redis 缓存
响应示例：
{
  "stock_code": "600519",
  "stock_name": "贵州茅台",
  "last_price": 1680.00,
  "yesterday_close": 1660.00,
  "change_rate": "+1.20%",
  "status": 0,
  "top_buyer": {"account": "A1001", "qty": 5000},
  "top_seller": {"account": "B2001", "qty": 4500},
  "bid_price": 1679.99,
  "ask_price": 1680.01,
  "bid_volume": 15000,
  "ask_volume": 12000
}
```

**多尺度 K 线与技术指标**
```
GET /api/publish/market/kline?stock_code={xx}&period={5M|1H|1D|1M|1Y}
权限：STANDARD (仅 1D) / PREMIUM_VIP (全尺度)
响应：
[{
  "time": "2026-05-01 09:30",
  "open": 10.00,
  "close": 11.00,
  "high": 11.50,
  "low": 9.80,
  "volume": 10000,
  "ma5": 10.50,
  "ma10": 10.20,
  "dif": 0.15,
  "dea": 0.12,
  "macdBar": 0.06
}]
```

**模拟升级 VIP**
```
POST /api/publish/user/upgrade
权限：STANDARD
逻辑：更新 local_user_subscription.is_premium = true, upgrade_time = NOW()
```

### 7.2 外部依赖接口

| 接口 | 用途 | 调用方式 |
|------|------|----------|
| `POST {account-system}/api/v1/auth/certificate-validate` | 验证 Token + 证书绑定 | AuthInterceptor 每次请求 |
| `GET {central-trade}/api/v1/stock/list` | 全量股票字典 | 开盘前/启动时同步 |
| `GET {central-trade}/api/v1/transaction/recent` | 近期成交流水 + 盘口快照（合并推送）。成交流水无数据时返回空数组，盘口照常返回。盘口字段：bidPrice、bidVolume、askPrice、askVolume | 每 5 秒轮询 |

---

## 8. 前端视图设计

### 8.1 路由

| 路由 | 组件 | 说明 |
|------|------|------|
| `/` | Portal.vue | 首页门户（GUEST） |
| `/stock/:code` | StockDetail.vue | 个股详情（GUEST 仅价格，STANDARD+ 完整） |

### 8.2 全局导航栏（App.vue）

- 左侧："网上信息发布" logo（标识当前所在系统，不是跳转链接）
- 中间：跳转到**其他子系统**的入口（交易系统、账户系统）
- 右侧：登录/注册按钮（跳转外部 SSO 账户系统）→ 已登录显示角色标签 + 退出
- 本系统内部页面切换（首页→详情）通过 Vue Router，不在导航栏放本系统入口

### 8.3 Portal.vue（首页门户）

- 搜索框：股票代码/拼音 → `searchStock()` API
- 大盘指数面板（Mock 上证/深证）
- 实时行情列表：`setInterval` 每 5 秒轮询 `getQuote()`
- 点击行 → Vue Router 跳转 `/stock/:code`
- `onUnmounted` 中 `clearInterval`

### 8.4 StockDetail.vue（个股详情）

- 从 `route.params` 获取 `stockCode`
- `setInterval` 每 5 秒调 `getQuote()`，`onUnmounted` 中 `clearInterval`
- 左侧 K 线图区（KLineChart 组件），右侧盘口/主力面板
- 权限分层渲染

### 8.5 KLineChart.vue（ECharts 蜡烛图）

- 阴阳蜡烛：`close >= open` 红色，`close < open` 绿色
- 叠加 MA5（白线）、MA10（黄线）
- 底部副图：MACD 柱（DIF/DEA/红绿柱）
- 周期切换按钮：5M | 1H | 1D | 1M | 1Y
- `window resize` 时 `chart.resize()`
- `onMounted` 初始化图表，`onUnmounted` dispose

### 8.6 UpgradeModal.vue（VIP 升级弹窗）

- 当 STANDARD 用户点击非 1D 周期时触发
- 图表容器 CSS `filter: blur(5px)` 高斯模糊
- 居中弹窗："升级 VIP 解锁多尺度 K 线与 MACD 技术指标"
- 模拟支付按钮 → `POST /user/upgrade` → 更新 userStore → 关闭

---

## 9. 数据流全景

```
中央交易系统 ──(每5秒)──→ MarketService.refreshQuotes()
                              │
                              ├──→ Redis quote:{stockCode} (TTL 5s) ──→ 前端轮询
                              ├──→ Redis tick:{stockCode} ──(每5分钟)──→ kline_5m_data
                              └──→ TopTraderEngine ──→ Redis top_buyer/seller Hash

外部账户系统 ←──(每次请求)── AuthInterceptor ──→ UserContext(ThreadLocal)
                                                    │
                                                    └──→ 接口权限校验

前端 Portal/Detail ──(5秒轮询)──→ /market/quote ──→ Redis 极速读取
前端 KLineChart    ──→ /market/kline ──→ KLineAggregator ──→ kline_5m_data
```
