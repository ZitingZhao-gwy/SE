# 全系统启动手册（STARTUP.md）

> 本机（Windows 11 + Git Bash）经验证的正确启动方式与踩坑记录。
> 一键脚本见同目录 `start-stack.sh`。

## 0. 一句话

```bash
bash /e/stock-system/start-stack.sh
```

它会按**正确次序**把全栈拉起来；想省内存可加 `--no-fe`（不起两个前端），想保留历史成交可加 `--keep-data`。

---

## 1. 正确的启动次序（核心）

| 步 | 服务 | 端口 | 启动方式 |
|----|------|------|----------|
| 0 | 清场：杀掉所有 java/node | — | `Get-Process java,node \| Stop-Process -Force` |
| 1 | MySQL / Redis | 3306 / 6379 | 不在则启动（基础设施，不随栈重启）|
| 2 | 修正股票数据 | — | `fix-stock-data.py`（见 §3.4）|
| 3 | **Kafka** | 9092 | **`.sh` 脚本**（见 §3.1），**等 9092 监听**后再继续 |
| 4 | 清 `order_book`/`trade_record` | — | 避免 CT 重启撞号（见 §3.3）|
| 5 | 中央交易 CT | 8082 | `java -jar`（带 DB 环境变量），等 8082 |
| 6 | 账户 / 交易管理 / 信息发布 | 8080 / 8081 / 8083 | `java -jar` |
| 7 | 交易客户端 | 8090 | `node server/app.js`（Kafka 已就绪后才起），确认日志 `Kafka pipeline connected` |
| 8 | 前端 | 5173 / 3000 | `npm run dev`（最吃内存，可跳过）|
| 9 | auto-trade | — | `bash auto-trade.sh`（最后；内置等 Kafka ~60s 再下单）|

**铁律：Kafka 必须先起且 9092 稳定监听，再起 CT 和交易客户端。** 否则 CT 的消费者、客户端的生产者会在 Kafka 起来前发起连接、进入坏的重试态（`ECONNRESET` 风暴），且**不会自动恢复**——表现为：服务都在、但订单进不了 Kafka、不撮合。

---

## 2. 数据流

```
auto-trade → 交易客户端(:8090) → Kafka(central.order.command) → 中央交易 CT(:8082) 消费撮合
                                                                      ↓
                                                          order_book / trade_record(MySQL)
                                                                      ↓
                                            信息发布后端(:8083) → Redis → 前端(:3000)
```

---

## 3. 踩坑与原因（务必理解，否则会反复掉坑）

### 3.1 Kafka 只能用 `.sh`，不能用 Windows `.bat`
- 现象：从 bash（或 bash 衍生的 cmd）跑 `bin\windows\kafka-server-start.bat` → `参数太多 / 命令语法不正确`，窗口瞬崩，连 `logs/server.log` 都来不及写。
- 原因：`kafka-run-class.bat` 要 `set CLASSPATH=%CLASSPATH%;...` 拼超长 classpath；Git Bash(MSYS) 注入的环境变量（`PATH`/`CLASSPATH` 含 Unix 的 `:`、`/`）把这条 `set` 拼坏。
- 正解：用 Unix 脚本 `bin/kafka-server-start.sh`——它的 `kafka-run-class.sh` 检测到 MinGW 会用 `cygpath` 把 classpath 转成 Windows 的 `;` 格式。
  ```bash
  cd /e/stock-system/kafka_2.13-3.6.1
  KAFKA_GC_LOG_OPTS=" " JAVA_HOME=/e/stock-system/online-info-publish/jdk-17.0.12+7 \
    nohup bin/kafka-server-start.sh config/kraft.properties > /tmp/kafka.log 2>&1 &
  ```
  注意 `KAFKA_GC_LOG_OPTS=" "` 是**一个空格**（不是空串）。
- `config/kraft.properties` 里 `log.dirs=E:/stock-system/kafka-logs`，存储已格式化，**正常重启不要重新 format**（会清掉已建 topic）。

### 3.2 启动次序（同 §1 铁律）
java 后端（CT/账户/交易管理/信息发布）用 `java -jar` 起，**不吃 MSYS 环境**，先后无所谓；但**依赖 Kafka 的 CT 消费者、客户端生产者必须在 Kafka 之后**。

### 3.3 CT 重启会让 `trade_no` 主键撞号
- 现象：订单能进 `order_book`，但 `trade_record` 不涨；CT 日志 `DuplicateKeyException: Duplicate entry 'T20260621xxxx' ... at TradeService.executeTrade`。
- 原因：CT 撮合引擎是**内存态**，重启后成交号计数器重置回低位，与表里旧成交号冲突；且内存盘口为空，旧 ACCEPTED 挂单成了孤儿。
- 正解：全新启动时 `TRUNCATE order_book; TRUNCATE trade_record;`（脚本第 4 步默认做；`--keep-data` 跳过）。auto-trade 会几分钟内重新灌满。

### 3.4 数据前提：`previous_close` 必须非 0
- 现象：委托返回 `accepted:true` 却不落库、无成交。
- 原因：`stock_info.previous_close=0` → PriceLimiter 涨跌停带 `[0,0]` → 任何价都被静默 `REJECTED`。
- 正解：`fix-stock-data.py`（用 pymysql 以 utf8mb4 写真实昨收价 + 中文名）。也修了 GBK 乱码的股票名。

### 3.5 `restart-all.bat` 的问题（本手册未改它）
- **行尾必须 CRLF**：仓库里它是 LF，`cmd` 解析 LF 的 .bat 会错位、丢字符（`kafka`→`fka`）。如要双击用，先 `unix2dos restart-all.bat`。
- 它的 **Kafka 步用 `.bat` + 相对路径**，在本机不可靠（见 §3.1）。java 后端步用绝对路径 `start ... cmd /c`，是好的。
- 结论：本机用 `start-stack.sh`；`restart-all.bat` 暂保留未改。

### 3.6 环境 / 工具坑
- 从沙箱 bash 调 `start` 开新窗口 → **拒绝访问**；所以全用 bash 直起（nohup &），不开新控制台。
- `cmd //c` 能用，但 cmd 的 cwd 只部分继承 bash；命令里给**绝对路径**最稳。给 cmd 的路径用**反斜杠**（正斜杠会被当成 `/k`、`/d` 等开关）。
- 杀进程：`powershell -NoProfile -Command "Stop-Process -Id <pid> -Force"` 最稳；`taskkill` 偶发超时。
- 重负载下 `tasklist`/`taskkill` 会超时——这本身就是**内存吃紧**的信号（多为重复/孤儿 JVM 堆积；`start-stack.sh` 第 0 步统一清场可消除）。
- 配置层两处修复已提交（仓库 `main`）：`application.yml` 的 `sql.init.mode=never`（防每次启动重跑 schema 清数据）+ 交易时段放宽全天。

### 3.7 集合竞价时段会导致“只收单不成交”
- 现象：`order_book` 在涨但 `trade_record=0`、无成交;**且仅在某些时间点出现**(早于 9:25)。
- 原因：`CallAuctionScheduler`(每 60s 跑一次)按时间判定相位——当前时间**早于** `call-auction`(默认 9:25)就进**集合竞价**(只收单、等 9:25 统一撮合);晚于 9:25 才进**连续竞价**。手动调 admin 接口强制连续会被它每分钟切回去。
- 正解:把集合竞价时间设为“已过去”,让它开机即进连续竞价并标记当日完成、不再回切——CT 启动带环境变量 `CALL_AUCTION_HOUR=0 CALL_AUCTION_MINUTE=0`(`start-stack.sh` 第 5 步已内置)。
- 排查接口:`GET /api/central-trading/admin/call-auction/status` 看 `phase` 是否 `CONTINUOUS_AUCTION`。

---

## 4. 验证 / 监控

```bash
# 端口自检
netstat -ano | grep LISTENING | grep -E ':(3000|5173|8080|8081|8082|8083|8090|9092|3306|6379) '

# 成交流水是否在增长（核心健康指标）
"C:/Users/JY/mysql/bin/mysql.exe" -u root -proot -N -e \
  "SELECT COUNT(*) FROM central_trading.order_book; SELECT COUNT(*) FROM central_trading.trade_record;"

# 关键日志（start-stack.sh 落在 /tmp/stack-logs/）
tail -f /tmp/stack-logs/kafka.log          # Kafka
tail -f /tmp/stack-logs/ct.log             # 中央交易（看撮合/异常）
tail -f /tmp/stack-logs/trading-client.log # 应有 "Kafka pipeline connected"
tail -f /tmp/stack-logs/auto-trade.log     # 下单流水
```

健康标志：`trade_record` 计数持续增长，最新 `trade_time` 紧跟当前时间，CT 日志无 `DuplicateKeyException`。

---

## 5. 停止 / 清理

```bash
# 停全部应用（保留 MySQL/Redis 基础设施）
powershell -NoProfile -Command "Get-Process java,node -EA SilentlyContinue | Stop-Process -Force"

# 连基础设施一起停
powershell -NoProfile -Command "Get-Process mysqld,'redis-server' -EA SilentlyContinue | Stop-Process -Force"
```

> 内存吃紧时，先执行上面的“停全部应用”再重跑 `start-stack.sh`——它的清场步能回收反复重启留下的孤儿 JVM/node 内存。

---

## 6. 账号 / 端口速查

- 投资者：`2026000000000001`～`...10`，密码 `123`；管理员 `admin/123`。
- 端口：3000 信息前端 / 5173 账户前端 / 8080 账户 / 8081 交易管理 / 8082 中央交易 / 8083 信息后端 / 8090 客户端 / 9092 Kafka / 3306 MySQL / 6379 Redis。
- JDK17：`E:/stock-system/online-info-publish/jdk-17.0.12+7`。
