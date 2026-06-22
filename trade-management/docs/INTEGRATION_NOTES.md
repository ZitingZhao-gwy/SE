# 联调说明

## 已查看的小组仓库

- 交易客户端：`https://github.com/KDBRonaldo/Trading_client.git`
- 中央交易系统：`https://github.com/LeoHugh/Central_trading.git`

本地参考克隆目录：

```text
D:/大学遗物/大二下/软工基/实验课/codex/integration-repos
```

## 与交易客户端的对接

交易客户端通过浏览器配置：

```js
localStorage.setItem("managementApiBase", "http://localhost:8081");
location.reload();
```

交易客户端调用：

```http
POST /api/trade-management/orders/review
```

其当前真实请求体主要包含：

```json
{
  "fundAccountNo": "6222026000000001",
  "stockCode": "600519",
  "direction": "BUY",
  "price": 1688.35,
  "quantity": 100
}
```

本模块已兼容该格式：

- `direction` 会自动映射为 `side`
- 缺少 `reviewId` 时自动生成
- 缺少 `orderId` 时自动生成
- 缺少 `accountId` 时使用 `fundAccountNo`
- 缺少 `securityAccountNo` 时使用 `fundAccountNo`
- 缺少 `amount` 时按 `price * quantity` 计算

交易客户端只判断 `approved !== false`，因此：

- `AUTO_APPROVED` 会继续提交中央交易系统
- `PENDING_MANUAL` 会被拦下，不会继续发布交易
- `REJECTED` 会被拦下

## 与中央交易系统的对接

中央交易系统默认地址：

```text
http://localhost:8082
```

中央交易系统已经提供管理类接口：

```http
POST /api/central-trading/admin/stocks/{stockCode}/suspend
POST /api/central-trading/admin/stocks/{stockCode}/resume
GET  /api/central-trading/admin/stocks/{stockCode}/orders
POST /api/central-trading/admin/stocks/{stockCode}/price-limit
```

本模块现在支持通过 `config.properties` 开启中央交易系统联调：

```properties
central.enabled=true
central.api-base=http://localhost:8082
```

开启后，以下功能会调用中央交易系统接口：

- 暂停交易
- 重启交易
- 查看股票委托簿
- 设置涨跌停

委托审查本身仍应由交易客户端先调用本模块，审查通过后再提交中央交易系统。

账户资金和证券持仓的冻结、解冻不属于交易管理系统职责，由账户业务子系统或中央交易系统处理。

## 账户系统冻结/解冻联调

本模块现通过账户系统公开 HTTP 接口发起管理员冻结/解冻：

```http
POST /api/internal/staff/login
POST /api/admin/accounts/freeze
POST /api/admin/accounts/unfreeze
X-Staff-Auth-Token: <token>
```

请求枚举：

- `account_type`: `FUND` 或 `SECURITY`
- `freeze_type`: `LOSS` 或 `VIOLATION`

本模块不直接修改账户数据库。

账户系统目前仍使用 `userName` 调用黑名单接口。身份证主键版本联调时，账户组需要把 `HttpBlacklistClient` 和前端 `checkBlacklist` 改为发送 `idCardNo`。

2026-06-20 拉取账户仓库最新代码执行 `mvn test` 时，仓库自身因 `StaffService` 引用缺失的 `ChangeStaffPasswordRequest` 而编译失败。冻结/解冻端到端测试需等待账户组修复。

## 当前中央仓库构建注意事项

2026-06-20 拉取中央仓库最新 `main` 后，在 Windows UTF-8 Maven 环境执行 `mvn test` 时，中央仓库部分 Java 文件出现源文件编码错误，并导致 Lombok getter/setter 连锁编译失败。联调前需要中央组统一这些文件为 UTF-8 并确认 `mvn test` 或 `mvn package` 能通过。
