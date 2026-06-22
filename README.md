# 股票交易系统 — 全系统集成联调版

> 6 个子系统在本机协同运行的完整集成环境。一键启动，开箱即用。

## 目录

- [系统概览](#系统概览)
- [环境要求](#环境要求)
- [目录结构](#目录结构)
- [服务编排](#服务编排)
- [数据库](#数据库)
- [测试账号矩阵](#测试账号矩阵)
- [编译说明](#编译说明)
- [启动与停止](#启动与停止)
- [访问地址](#访问地址)
- [演示流程](#演示流程)
- [常见问题](#常见问题)

---

## 系统概览

| 子系统 | 端口 | 技术栈 | 说明 |
|--------|------|--------|------|
| 账户管理系统 | 8080 (后端) / 5173 (前端) | Spring Boot + React | 投资者注册/登录、资金账户、证券账户管理 |
| 中央交易系统 | 8082 | Spring Boot + Kafka | 委托撮合引擎、盘口快照、K线数据 |
| 交易管理系统 | 8081 | Java HttpServer | 管理员后台、审核、风控 |
| 交易客户端 | 8090 | Node.js + Express | 投资者交易终端、下单、查持仓 |
| 网上信息发布 | 8083 (后端) / 3000 (前端) | Spring Boot + Vue3 | 行情展示、K线图表、主力动向、VIP权限 |
| Kafka | 9092 | KRaft 模式 | 消息中间件，交易客户端↔中央交易 |
| MySQL | 3306 | 8.0 | 共享数据库（各系统独立库） |
| Redis | 6379 | 5.0 | 行情缓存、主力累加、分布式锁 |

**数据流**：

```
投资者 → 交易客户端(:8090) → Kafka → 中央交易(:8082) → 撮合成交
                                         ↓
              网上信息发布(:8083) ← 盘口快照/成交数据
                    ↓
              Redis → 实时行情API → 前端(:3000)
```

---

## 环境要求

| 组件 | 版本 | 备注 |
|------|------|------|
| JDK | 17 (Temurin) | 路径已内置: `online-info-publish/jdk-17.0.12+7` |
| Maven | 3.9+ | 路径已内置: `online-info-publish/apache-maven-3.9.9` |
| Node.js | 18+ | 全局安装 |
| MySQL | 8.0 | `C:\Users\JY\mysql`，root/root |
| 操作系统 | Windows 11 | 16GB+ 内存推荐 |

---

## 目录结构

```
E:/stock-system/
├── account-management/       # 账户管理系统
│   ├── frontend/             # React 前端（npm run dev → :5173）
│   ├── scripts/              # SQL 脚本
│   └── target/               # JAR
├── central-trading/          # 中央交易系统（自编译）
│   ├── src/                  # 源码（已统一UTF-8）
│   └── target/               # JAR (38MB)
├── trade-management/         # 交易管理系统
│   ├── web/                  # 前端静态文件
│   ├── config.properties     # 数据库密码配置
│   └── target/               # JAR (shaded)
├── trading-client/           # 交易客户端
│   ├── server/               # Express API 服务器
│   ├── js/                   # 前端 JS
│   └── index.html            # 前端页面
├── online-info-publish/      # 网上信息发布子系统（我们）
│   ├── jdk-17.0.12+7/        # JDK 17
│   ├── apache-maven-3.9.9/   # Maven
│   ├── redis-*.exe           # Redis 可执行文件
│   ├── publish-frontend/     # Vue3 前端
│   └── target/               # JAR (50MB)
├── kafka_2.13-3.6.1/         # Kafka (KRaft 模式)
├── auto-trade.sh             # 自动交易模拟脚本
├── restart-all.bat           # 一键重启脚本
└── README.md                 # 本文件
```

---

## 服务编排

### 启动顺序（restart-all.bat 已内置）

```
1. MySQL    :3306  — 基础设施
2. Redis    :6379  — 基础设施
3. Kafka    :9092  — 消息中间件（需最先启动，其他服务依赖）
4. 中央交易  :8082  — 撮合引擎
5. 账户系统  :8080  — SSO 认证
6. 交易管理  :8081  — 管理后台
7. 我们后端  :8083  — 行情计算（512m 内存）
8. 交易客户端 :8090  — API + 前端
9. 账户前端  :5173  — React 页面
10. 我们前端  :3000  — Vue3 页面
11. 自动交易   —      — 持续生成模拟交易
```

### 依赖关系

```
前端(:3000) → 后端(:8083) → Redis + MySQL(stock_publish)
                             ↓
                        中央交易(:8082) → Kafka ← 交易客户端(:8090) ← 前端
                             ↓                              ↓
                        MySQL(central_trading)   账户系统(:8080) → MySQL(account_db)
```

---

## 数据库

### 数据库列表（同一 MySQL 实例）

| 数据库 | 用途 | 建表方式 |
|--------|------|----------|
| `stock_publish` | 网上信息发布 | 手动 SQL |
| `account_db` | 账户管理 | `scripts/01_create_tables.sql` |
| `central_trading` | 中央交易 | 应用启动自动建表 + `schema.sql` |
| `stock_trade_management` | 交易管理 | `sql/schema.sql` |
| `trading_client` | 交易客户端 | 手动 SQL |

### 创建方法

`restart-all.bat` 首次运行时会自动创建所有数据库。也可手动执行：

```bash
# MySQL 连接
mysql -u root -proot

# 创建各库
CREATE DATABASE IF NOT EXISTS stock_publish DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS account_db DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS central_trading DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS stock_trade_management DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS trading_client DEFAULT CHARSET utf8mb4;
```

### stock_publish 核心表

```sql
-- 股票基础信息（10只，从中央交易同步）
CREATE TABLE sync_stock_info (
    stock_code CHAR(6) PRIMARY KEY,
    stock_name VARCHAR(100) NOT NULL,
    yesterday_close DECIMAL(10,2) NOT NULL,
    pinyin_abbr VARCHAR(20)
);

-- 用户订阅状态
CREATE TABLE local_user_subscription (
    id INT AUTO_INCREMENT PRIMARY KEY,
    global_user_id VARCHAR(50) NOT NULL UNIQUE,
    is_premium BOOLEAN NOT NULL DEFAULT FALSE
);

-- 5分钟K线基准表
CREATE TABLE kline_5m_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code CHAR(6) NOT NULL,
    period_start_time DATETIME NOT NULL,
    open_price DECIMAL(10,2), close_price DECIMAL(10,2),
    high_price DECIMAL(10,2), low_price DECIMAL(10,2),
    volume BIGINT, UNIQUE KEY uk_code_time (stock_code, period_start_time)
);
```

---

## 测试账号矩阵

### 投资者账号（10个）

| 资金账户 | 证券账户 | 密码 | 初始余额 | 用途 |
|----------|----------|------|----------|------|
| 2026000000000001 | 3026000000000001 | 123 | 10,000,000 | 自动交易 |
| 2026000000000002 | 3026000000000002 | 123 | 10,000,000 | 自动交易 |
| 2026000000000003 | 3026000000000003 | 123 | 10,000,000 | 自动交易 |
| 2026000000000004 | 3026000000000004 | 123 | 10,000,000 | 自动交易 |
| 2026000000000005 | 3026000000000005 | 123 | 10,000,000 | 自动交易 |
| 2026000000000006 | 3026000000000006 | 123 | 10,000,000 | 自动交易 |
| 2026000000000007 | 3026000000000007 | 123 | 10,000,000 | 自动交易 |
| 2026000000000008 | 3026000000000008 | 123 | 10,000,000 | 自动交易 |
| 2026000000000009 | 3026000000000009 | 123 | 10,000,000 | 自动交易 |
| 2026000000000010 | 3026000000000010 | 123 | 10,000,000 | 自动交易 |

> 所有账号密码 `123`，资金账户与证券账户一一对应。

### 管理员账号

| 系统 | 用户名 | 密码 | 角色 |
|------|--------|------|------|
| 账户管理系统 | staff01 | 123 | 员工 |
| 账户管理系统 | admin | 123 | 管理员 |
| 交易管理系统 | admin | 123 | SUPER_ADMIN |

### 股票列表（10只）

| 代码 | 名称 | 类型 | 昨收 |
|------|------|------|------|
| 000001 | 平安银行 | 普通 | 12.30 |
| 000002 | ST万科A | ST | 8.75 |
| 000858 | 五粮液 | 普通 | 168.50 |
| 600000 | 浦发银行 | 普通 | 7.85 |
| 600016 | 民生银行 | 普通 | 3.92 |
| 600036 | 招商银行 | 普通 | 35.50 |
| 600519 | 贵州茅台 | 普通 | 1662.00 |
| 600900 | ST长江电力 | ST | 25.60 |
| 601398 | 工商银行 | 普通 | 5.82 |
| 601988 | 中国银行 | 普通 | 4.15 |

---

## 编译说明

### 中央交易系统（特殊，源码编码已修复）

```bash
set JAVA_HOME=E:\stock-system\online-info-publish\jdk-17.0.12+7
cd E:\stock-system\central-trading
mvn package -Dmaven.test.skip=true
```

> 源码已从 GBK 统一转换为 UTF-8，并添加了 Lombok 注解处理器配置。

### 其他后端

```bash
# 账户管理系统
cd E:\stock-system\account-management
mvn package -DskipTests

# 交易管理系统
cd E:\stock-system\trade-management
mvn package -DskipTests

# 网上信息发布
cd E:\stock-system\online-info-publish
mvn package -DskipTests -Dmaven.test.skip=true
```

### 前端

```bash
# 账户系统前端
cd E:\stock-system\account-management\frontend
npm install && npm run dev

# 网上信息发布前端
cd E:\stock-system\online-info-publish\publish-frontend
npm install && npm run dev
```

---

## 启动与停止

### 一键启动

```cmd
双击 E:\stock-system\restart-all.bat
```

脚本自动执行：杀旧进程 → 启动 MySQL → Redis → Kafka → 4 个后端 → 3 个前端 → 自动交易

耗时约 3 分钟（Kafka 初始化最慢）。

### 手动启动

```bash
# 1. 基础设施
start "MySQL" "C:\Users\JY\mysql\bin\mysqld.exe" --defaults-file="C:\Users\JY\mysql\my.ini"
cd E:\stock-system\online-info-publish && redis-server.exe

# 2. Kafka（等待20秒）
cd E:\stock-system\kafka_2.13-3.6.1
set KAFKA_GC_LOG_OPTS=
bin\windows\kafka-server-start.bat config\kraft.properties

# 3. 后端
java -jar central-trading\target\central-trading-1.0.0-SNAPSHOT.jar
java -jar account-management\target\account-management-1.0.0-SNAPSHOT.jar
cd trade-management && java -jar target\stock-trade-management-1.0.0-shaded.jar
java -Xmx512m -jar online-info-publish\target\online-info-publish-subsys-1.0.0-SNAPSHOT.jar

# 4. 前端 + 交易客户端
cd trading-client && node server\app.js
cd account-management\frontend && npm run dev
cd online-info-publish\publish-frontend && npm run dev

# 5. 自动交易
bash auto-trade.sh
```

### 停止

```cmd
taskkill /F /IM java.exe
taskkill /F /IM node.exe
```

---

## 访问地址

| 页面 | URL | 说明 |
|------|-----|------|
| **网上信息发布首页** | http://localhost:3000 | 行情列表、搜索、K线 |
| 个股详情 | http://localhost:3000/stock/600519 | K线+盘口+主力 |
| 账户系统登录 | http://localhost:5173/login | 投资者/管理员登录 |
| 交易客户端 | http://localhost:8090 | 下单交易 |
| 交易管理后台 | http://localhost:8081 | 管理员登录 admin/123 |

---

## 演示流程

### 完整演示链路

1. **管理员开户**：打开 http://localhost:5173 → 管理员登录(admin/123) → 创建投资者账户
2. **投资者登录交易**：打开 http://localhost:8090 → 用投资者账号登录 → 发起买卖委托
3. **行情展示**：打开 http://localhost:3000 → 查看实时行情 → 点击个股 → K线图+MACD
4. **权限演示**：无登录=GUEST(仅价格) → 登录=STANDARD(盘口+主力+日K) → VIP(全尺度K线+MACD)

### 自动交易（演示前提前启动）

```bash
bash E:\stock-system\auto-trade.sh
```

10 个测试账户每 1~3 秒随机发起买卖委托，经过 Kafka → 中央交易撮合成交
→ 我们的系统消费展示。运行 5 分钟后 K 线数据充实，MACD 指标有意义。

---

## 常见问题

**Q: 启动后 `localhost:3000` 拒绝连接？**
A: 等待 3 分钟，Kafka 初始化最慢。查看 `restart-all.bat` 窗口输出确认各端口状态。

**Q: 交易客户端显示"密码格式错误"？**
A: 已修复。若仍有问题，在交易客户端页面按 Ctrl+Shift+R 强制刷新。

**Q: 行情数据不更新？**
A: 检查自动交易是否在跑：`bash E:\stock-system\auto-trade.sh`

**Q: 内存不足？**
A: 网上信息发布后端已设 `-Xmx512m`。若系统内存 < 8GB，关闭不需要的 JVM 进程。

**Q: MySQL 连接失败？**
A: 检查 MySQL 服务状态，确认 root/root 密码正确。

**Q: 如何重置所有数据？**
A: 删除各数据库表后重启应用，会自动重建。Redis 数据 `redis-cli FLUSHALL`。
