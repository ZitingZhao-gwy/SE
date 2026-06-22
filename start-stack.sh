#!/usr/bin/env bash
# =============================================================================
# start-stack.sh — 全系统一键启动（本机 Git Bash + Windows 验证可用）
#
# 为什么有这个脚本：restart-all.bat 在本机的两个硬伤——
#   1) Kafka 步用 Windows 的 .bat，在 Git Bash 衍生的进程树里拼 CLASSPATH 必崩
#      （“参数太多 / 命令语法不正确”）；
#   2) 它没有保证“Kafka 先于 CT/客户端就绪”，导致 CT 消费者、客户端生产者
#      在 Kafka 起来前连接、进入坏的重试态（ECONNRESET），不会自动恢复。
#
# 本脚本用经验证的正确方式：
#   - Kafka 用 Unix 的 .sh 脚本起（kafka-run-class.sh 会用 cygpath 转 classpath）；
#   - 严格次序：清场 → 基础设施 → Kafka(等9092) → 清表 → CT(等8082) → 其它后端
#     → 交易客户端(等8090+确认生产者连上) → 前端 → auto-trade（最后）；
#   - 全程 bash 直起（java -jar / node / .sh），不用 start 开新窗口（沙箱会“拒绝访问”）。
#
# 用法：  bash start-stack.sh            # 起全栈（含前端 + auto-trade）
#         bash start-stack.sh --no-fe    # 不起两个 Vite 前端（省内存）
#         bash start-stack.sh --no-auto  # 不起 auto-trade
#         bash start-stack.sh --keep-data# 不清 order_book/trade_record（默认会清，见说明）
# 详见同目录 STARTUP.md。
# =============================================================================
set -u

# ---- 可调参数 ----
ROOT=/e/stock-system
JAVA_HOME_DIR="$ROOT/online-info-publish/jdk-17.0.12+7"
JAVA="$JAVA_HOME_DIR/bin/java.exe"
MYSQL="C:/Users/JY/mysql/bin/mysql.exe"
MYSQLD="C:/Users/JY/mysql/bin/mysqld.exe"
MYSQL_INI="C:/Users/JY/mysql/my.ini"
LOG=/tmp/stack-logs
START_FE=1; START_AUTO=1; CLEAN_DATA=1
for a in "$@"; do case "$a" in
  --no-fe) START_FE=0;; --no-auto) START_AUTO=0;; --keep-data) CLEAN_DATA=0;;
esac; done

export JAVA_HOME="$JAVA_HOME_DIR"
mkdir -p "$LOG"

say(){ echo "[$(date '+%H:%M:%S')] $*"; }
mysqlq(){ "$MYSQL" -u root -proot --default-character-set=utf8mb4 -N -e "$1" 2>/dev/null; }
# 等端口监听：wait_port <port> <name> <timeout_s>
wait_port(){ local p="$1" n="$2" t="${3:-60}" i=0
  while [ $i -lt "$t" ]; do
    if netstat -ano | grep -q ":$p .*LISTENING"; then say "  $n :$p OK (${i}s)"; return 0; fi
    sleep 2; i=$((i+2))
  done
  say "  !! $n :$p 在 ${t}s 内未监听"; return 1; }

# =========== 0. 清场：杀掉所有 java + node + 残留 auto-trade.sh（含旧孤儿/重复进程，防累积）===========
say "[0/9] 清场：停掉所有 java / node + 残留 auto-trade.sh ..."
powershell -NoProfile -Command "Get-Process java,node -ErrorAction SilentlyContinue | Stop-Process -Force" 2>/dev/null
# auto-trade.sh 是 bash 进程、不在 java/node 里，需按命令行单独杀（否则每次启动会叠加，曾累积到 32 个）
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { \$_.CommandLine -like '*auto-trade.sh*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -EA SilentlyContinue }" 2>/dev/null
sleep 4

# =========== 1. 基础设施：MySQL + Redis（不在则启动）===========
say "[1/9] MySQL / Redis ..."
if ! mysqlq "SELECT 1" >/dev/null 2>&1; then
  nohup "$MYSQLD" --defaults-file="$MYSQL_INI" > "$LOG/mysql.log" 2>&1 &
  sleep 5
fi
mysqlq "SELECT 1" >/dev/null 2>&1 && say "  MySQL OK" || say "  !! MySQL 连接失败"
if ! (cd "$ROOT/online-info-publish" && ./redis-cli.exe ping >/dev/null 2>&1); then
  ( cd "$ROOT/online-info-publish" && nohup ./redis-server.exe > "$LOG/redis.log" 2>&1 & )
  sleep 2
fi
say "  Redis 处理完毕"

# =========== 2. 数据前提：previous_close 非0 + 股票名 UTF-8（best-effort）===========
# previous_close=0 会让 PriceLimiter 涨跌停带变 [0,0]、所有委托被静默拒绝。
if [ -f "$ROOT/fix-stock-data.py" ] && command -v /d/python3.11.8/python >/dev/null 2>&1; then
  say "[2/9] 修正股票基础数据(previous_close/名称) ..."
  /d/python3.11.8/python "$ROOT/fix-stock-data.py" >/dev/null 2>&1 && say "  数据 OK" || say "  (跳过/失败，确认 pymysql 已装)"
else
  say "[2/9] 跳过数据修正（缺脚本或 python）"
fi

# =========== 2b. 真结算基线：播种持仓 + 复位资金 + 清幂等日志 ===========
# 真结算（ACCOUNT_API_MOCK=false）下卖方必须有券可冻；此脚本把 account_db 复位到演示基线
# （每证券账户每只股 100000 股、资金复位、清 holding_change_log/fund_transaction_log）。
if [ -f "$ROOT/seed-realsettle.py" ] && command -v /d/python3.11.8/python >/dev/null 2>&1; then
  say "[2b]  播种真结算基线(持仓/资金/幂等日志) ..."
  /d/python3.11.8/python "$ROOT/seed-realsettle.py" >/dev/null 2>&1 && say "  基线 OK" || say "  (播种失败，确认 pymysql)"
fi

# =========== 3. Kafka（必须用 .sh，等 9092 就绪）===========
say "[3/9] 启动 Kafka(.sh) ..."
( cd "$ROOT/kafka_2.13-3.6.1" \
  && KAFKA_GC_LOG_OPTS=" " JAVA_HOME="$JAVA_HOME_DIR" \
     nohup bin/kafka-server-start.sh config/kraft.properties > "$LOG/kafka.log" 2>&1 & )
wait_port 9092 "Kafka" 90 || { say "Kafka 起不来，看 $LOG/kafka.log"; exit 1; }

# =========== 4. 清表：避免 CT 重启后 trade_no 主键撞号 ===========
# CT 撮合引擎是内存态：重启后成交号计数器重置、与旧 trade_record 冲突→成交写不进库。
# 全新启动时引擎内存本就为空，清表使 DB 与引擎一致。--keep-data 可跳过。
if [ "$CLEAN_DATA" = 1 ]; then
  say "[4/9] 清空 order_book + trade_record（干净撮合，避免撞号）..."
  "$MYSQL" -u root -proot -e "TRUNCATE TABLE central_trading.trade_record; TRUNCATE TABLE central_trading.order_book;" 2>/dev/null \
    && say "  已清表" || say "  !! 清表失败"
else
  say "[4/9] 保留现有 order_book/trade_record（--keep-data）"
fi

# =========== 5. 中央交易 CT（等 8082）===========
# CALL_AUCTION_HOUR/MINUTE=0：把集合竞价时间设为“已过去”，让 CallAuctionScheduler
# 开机即判定“已过集合竞价→直接进连续竞价”，全天连续撮合。否则 9:25 之前会进集合竞价
# (只收单不成交)，演示时看不到成交流水。
say "[5/9] 启动 中央交易 CT（连续竞价，禁用集合竞价时段）..."
DB_USER=root DB_PASSWORD=root DB_HOST=localhost DB_PORT=3306 DB_DATABASE=central_trading \
  CALL_AUCTION_HOUR=0 CALL_AUCTION_MINUTE=0 ACCOUNT_API_MOCK=false \
  nohup "$JAVA" -jar "$ROOT/central-trading/target/central-trading-1.0.0-SNAPSHOT.jar" > "$LOG/ct.log" 2>&1 &
wait_port 8082 "中央交易" 60

# =========== 6. 其它后端：账户 / 交易管理 / 信息发布 ===========
say "[6/9] 启动 账户 / 交易管理 / 信息发布后端 ..."
ACCOUNT_DB_USERNAME=root ACCOUNT_DB_PASSWORD=root \
  nohup "$JAVA" -jar "$ROOT/account-management/target/account-management-1.0.0-SNAPSHOT.jar" > "$LOG/account.log" 2>&1 &
( cd "$ROOT/trade-management" && nohup "$JAVA" -jar target/stock-trade-management-1.0.0-shaded.jar > "$LOG/trade-mgmt.log" 2>&1 & )
nohup "$JAVA" -Xmx512m -jar "$ROOT/online-info-publish/target/online-info-publish-subsys-1.0.0-SNAPSHOT.jar" > "$LOG/online-info.log" 2>&1 &
wait_port 8080 "账户系统" 60; wait_port 8081 "交易管理" 40; wait_port 8083 "信息发布后端" 60

# =========== 7. 交易客户端（Kafka 已就绪后才起；确认生产者连上）===========
say "[7/9] 启动 交易客户端 ..."
( cd "$ROOT/trading-client" \
  && DB_PASSWORD=root KAFKA_ENABLED=true KAFKA_BROKERS=localhost:9092 KAFKAJS_NO_PARTITIONER_WARNING=1 \
     nohup node server/app.js > "$LOG/trading-client.log" 2>&1 & )
wait_port 8090 "交易客户端" 40
# 等 KafkaJS 生产者管道连上（最多 ~30s）
for i in $(seq 1 15); do
  grep -q "Kafka pipeline connected" "$LOG/trading-client.log" 2>/dev/null && { say "  Kafka 生产者已连上"; break; }
  sleep 2
done
grep -q "Kafka pipeline connected" "$LOG/trading-client.log" 2>/dev/null || say "  !! 未见 'Kafka pipeline connected'，查 $LOG/trading-client.log"

# =========== 8. 前端（最吃内存，可 --no-fe 跳过）===========
if [ "$START_FE" = 1 ]; then
  say "[8/9] 启动 前端(5173 / 3000) ..."
  ( cd "$ROOT/account-management/frontend" && nohup npm run dev > "$LOG/fe-account.log" 2>&1 & )
  ( cd "$ROOT/online-info-publish/publish-frontend" && nohup npm run dev > "$LOG/fe-publish.log" 2>&1 & )
  sleep 10
else
  say "[8/9] 跳过前端（--no-fe）"
fi

# =========== 9. 自动交易（最后；内置等 Kafka ~60s 再下单）===========
if [ "$START_AUTO" = 1 ]; then
  say "[9/9] 启动 auto-trade ..."
  ( cd "$ROOT" && nohup bash auto-trade.sh > "$LOG/auto-trade.log" 2>&1 & )
else
  say "[9/9] 跳过 auto-trade（--no-auto）"
fi

say "==== 启动完成。日志目录：$LOG ===="
say "端口自检："
for pn in "3306 MySQL" "6379 Redis" "9092 Kafka" "8082 CT" "8080 账户" "8081 交易管理" "8083 信息发布" "8090 客户端" "5173 账户前端" "3000 信息前端"; do
  set -- $pn
  if netstat -ano | grep -q ":$1 .*LISTENING"; then echo "  [OK]  $1 $2"; else echo "  [--]  $1 $2"; fi
done
say "验证成交流水：watch -n3 '\"$MYSQL\" -u root -proot -N -e \"SELECT COUNT(*) FROM central_trading.trade_record\"'"
