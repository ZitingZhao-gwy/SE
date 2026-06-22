# B2 单元测试记录

## 测试环境

| 项 | 配置 |
|----|------|
| JDK | Temurin 17.0.12+7 |
| 构建工具 | Maven 3.9.9 |
| 测试框架 | JUnit 5 + Mockito 5 |
| 编译目标 | Java 17 (--release 17) |
| 测试日期 | 2026-06-18 |

---

## 测试结构

```
src/test/java/com/stock/publish/
├── controller/
│   ├── StockControllerTest.java       (2 条)
│   └── MarketControllerTest.java      (3 条)
└── service/impl/
    ├── StockServiceImplTest.java       (3 条)
    └── MarketServiceImplTest.java      (8 条)
```

所有测试使用 `@ExtendWith(MockitoExtension.class)`，纯 Mock 隔离，不依赖真实 Redis/MySQL。

---

## 测试用例说明

### 1. StockServiceImplTest（3 条，全部通过）

| 编号 | 方法 | 场景 | 验证点 |
|------|------|------|--------|
| S1 | `testSearchByStockCode` | 输入股票代码前缀 "600" | Mock Mapper 返回贵州茅台列表，断言列表大小=1、代码=600519、名称=贵州茅台 |
| S2 | `testSearchByPinyin` | 输入拼音缩写 "GZMT" | Mock Mapper 返回贵州茅台，断言代码正确 |
| S3 | `testSearchNoResult` | 输入不存在的关键词 "zzzz" | Mock Mapper 返回空列表，断言 `result.isEmpty()` |

---

### 2. StockControllerTest（2 条，全部通过）

| 编号 | 方法 | 场景 | 验证点 |
|------|------|------|--------|
| C1 | `testSearchReturnsOk` | `GET /stock/search?keyword=600` | HTTP 200、`$.code=200`、`$.data[0].stockCode=600519` |
| C2 | `testSearchEmptyKeyword` | `GET /stock/search?keyword=` | HTTP 200、data 为空数组 |

---

### 3. MarketControllerTest（3 条，全部通过）

| 编号 | 方法 | 场景 | 验证点 |
|------|------|------|--------|
| M1 | `testQuoteFound` | `GET /market/quote/600519` | HTTP 200、`code=200`、`lastPrice=1680.00`、`changeRate=+1.20%` |
| M2 | `testQuoteNotFound` | `GET /market/quote/999999` | HTTP 200、`code=404`、`message=股票未找到` |
| M3 | `testQuoteGuestMasked` | GUEST 角色请求行情 | `topBuyer`、`topSeller` 在响应 JSON 中不存在（`doesNotExist`） |

**技术点**：使用 `MockedStatic<UserContext>` 模拟静态 ThreadLocal 方法，`tearDown` 中 `close()` 防污染。

---

### 4. MarketServiceImplTest（8 条，全部通过）

#### 4.1 缓存读取

| 编号 | 方法 | 场景 | 验证点 |
|------|------|------|--------|
| M4 | `testGetQuoteCacheHit` | Redis 有缓存 | 直接返回缓存数据，`verify(valueOps, never()).set(...)` 确认未回填 |
| M5 | `testGetQuoteCacheMissWithLock` | Redis 无缓存，抢锁成功 | `tryLock(3L,10L,SECONDS)` 匹配、double check、`valueOps.set` 被调用回填 |

#### 4.2 角色屏蔽

| 编号 | 方法 | 场景 | 验证点 |
|------|------|------|--------|
| M6 | `testMaskByRoleGuest` | GUEST 角色 | `bidPrice/askPrice/bidVolume/askVolume` 均为 null |
| M7 | `testMaskByRoleStandard` | STANDARD 角色 | 盘口字段完整保留，`bidPrice=1679.99` |

#### 4.3 buildQuote 数据库兜底

| 编号 | 方法 | 场景 | 验证点 |
|------|------|------|--------|
| M8 | `testBuildQuoteFromDb` | 查 `sync_stock_info` + 无 K 线 | `lastPrice=yesterdayClose=1660.00`、`changeRate=+0.00%` |
| M9 | `testBuildQuoteUnknownStock` | 股票代码不存在 | `stockInfoMapper.selectById` 返回 null → `getQuote` 返回 null |

#### 4.4 定时任务

| 编号 | 方法 | 场景 | 验证点 |
|------|------|------|--------|
| M10 | `testRefreshQuotesWritesRedis` | 执行 5 秒刷新 | `valueOps.set("quote:600519",...,5s)` 被调用 1 次、`listOps.rightPush("tick:600519",...)` 被调用 |
| M11 | `testAggregate5mKline` | 3 条 tick 聚合为 5 分钟 K 线 | OHLC 正确（O=100, C=98, H=102, L=98）、Volume=4500、`redisTemplate.delete("tick:600519")` 清空 |

**技术点**：
- 手动构造 `MarketServiceImpl` 注入真实 `ObjectMapper`（注册 `JavaTimeModule` 处理 `LocalDateTime`）
- `lenient()` 处理共享的 `redisTemplate.opsForValue()/opsForList()` stub
- `ArgumentCaptor` 捕获 `kline5mDataMapper.insert()` 参数进行多字段断言
- `tryLock` 参数使用 `3L, 10L`（long 类型），否则 Mockito 不匹配

---

## 测试结果汇总

```
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0

StockServiceImplTest     3/3 ✅
StockControllerTest      2/2 ✅
MarketControllerTest     3/3 ✅
MarketServiceImplTest    8/8 ✅
────────────────────────────
B2 合计                 16/16 ✅
```

## 遇坑记录

| 问题 | 原因 | 解决 |
|------|------|------|
| Lombok 不生成 getter/setter | Java 24 不兼容默认 Lombok | 升级 lombok 1.18.38 + `annotationProcessorPaths` + `release 17` |
| `UnnecessaryStubbingException` | setUp 公用 stub 部分测试未使用 | 加 `lenient()` |
| `tryLock` stub 不生效 | Mockito 参数类型：`int` 3 vs `long` 3L | 使用 `3L, 10L` |
| `listOps.rightPush` 未被调用 | `objectMapper` 无 JSR310 模块，序列化 `LocalDateTime` 静默失败 | `registerModule(new JavaTimeModule())` |
| `insert` 方法重载歧义 | `BaseMapper.insert(T)` vs `insert(Collection)` | 用 `ArgumentCaptor<Kline5mData>` 替代 `argThat` |
