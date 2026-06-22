#!/bin/bash
# 网上信息发布子系统 — 一键环境检查 + 启动全部服务
set -e

RED='\033[0;31m' GREEN='\033[0;32m' YELLOW='\033[1;33m' NC='\033[0m'
pass() { echo -e "${GREEN}[OK]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

echo "========================================"
echo "  网上信息发布子系统 — 环境检查与启动"
echo "========================================"
echo ""

# ----- 1. JDK 17 -----
echo "[1/5] 检查 JDK 17..."
if [ -d "$PROJECT_DIR/jdk-17.0.12+7" ]; then
    export JAVA_HOME="$PROJECT_DIR/jdk-17.0.12+7"
fi
if [ -z "$JAVA_HOME" ]; then
    fail "请设置 JAVA_HOME 指向 JDK 17"
fi
JAVA_VER=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)
pass "JDK: $JAVA_VER"

# ----- 2. MySQL -----
echo "[2/5] 检查 MySQL..."
MYSQL_DIR=""
if [ -d "C:/Users/JY/mysql" ]; then
    MYSQL_DIR="C:/Users/JY/mysql"
fi
if [ -z "$MYSQL_DIR" ]; then
    fail "请设置 MYSQL_DIR，或安装 MySQL 到 C:/Users/JY/mysql"
fi
MYSQL_RUNNING=false
"$MYSQL_DIR/bin/mysql.exe" -u root -proot -e "SELECT 1" 2>/dev/null && MYSQL_RUNNING=true
if [ "$MYSQL_RUNNING" = false ]; then
    warn "MySQL 未运行，正在启动..."
    MSYS_NO_PATHCONV=1 taskkill /F /IM mysqld.exe 2>/dev/null
    sleep 1
    "$MYSQL_DIR/bin/mysqld.exe" --defaults-file="$MYSQL_DIR/my.ini" --console &
    for i in $(seq 1 15); do
        sleep 2
        "$MYSQL_DIR/bin/mysql.exe" -u root -proot -e "SELECT 1" 2>/dev/null && break
    done
    "$MYSQL_DIR/bin/mysql.exe" -u root -proot -e "SELECT 1" 2>/dev/null || fail "MySQL 启动失败"
fi
pass "MySQL 就绪 (root/root)"

# ----- 3. Redis -----
echo "[3/5] 检查 Redis..."
REDIS_CLI="$PROJECT_DIR/redis-cli.exe"
REDIS_SERVER="$PROJECT_DIR/redis-server.exe"
if [ ! -f "$REDIS_SERVER" ]; then
    fail "未找到 redis-server.exe，请解压到项目目录"
fi
"$REDIS_CLI" ping 2>/dev/null && REDIS_RUNNING=true || REDIS_RUNNING=false
if [ "$REDIS_RUNNING" = false ]; then
    warn "Redis 未运行，正在启动..."
    "$REDIS_SERVER" &
    for i in $(seq 1 10); do
        sleep 1
        "$REDIS_CLI" ping 2>/dev/null && break
    done
    "$REDIS_CLI" ping 2>/dev/null || fail "Redis 启动失败"
fi
pass "Redis 就绪"

# ----- 4. Node.js -----
echo "[4/5] 检查 Node.js..."
NODE_VER=$(node -v 2>/dev/null) || fail "未找到 Node.js，请安装"
pass "Node.js $NODE_VER"
cd "$PROJECT_DIR/publish-frontend"
if [ ! -d "node_modules" ]; then
    warn "安装前端依赖..."
    npm install
fi

# ----- 5. Backend + Frontend -----
echo "[5/5] 启动服务..."

# 杀旧 Java 进程
MSYS_NO_PATHCONV=1 taskkill /F /IM java.exe 2>/dev/null
sleep 2

# 后端
echo "  编译后端..."
cd "$PROJECT_DIR"
export PATH="$JAVA_HOME/bin:$PROJECT_DIR/apache-maven-3.9.9/bin:$PATH"
mvn package -DskipTests -q 2>/dev/null || fail "后端编译失败"
java -jar "target/online-info-publish-subsys-1.0.0-SNAPSHOT.jar" > /tmp/backend.log 2>&1 &
for i in $(seq 1 20); do
    sleep 2
    curl -s "http://localhost:8080/api/publish/stock/search?keyword=600" > /dev/null 2>&1 && break
done
curl -s "http://localhost:8080/api/publish/stock/search?keyword=600" > /dev/null 2>&1 || fail "后端启动失败"
pass "后端: http://localhost:8080/api/publish"

# 前端
cd "$PROJECT_DIR/publish-frontend"
npx vite --host > /tmp/frontend.log 2>&1 &
for i in $(seq 1 10); do
    sleep 1
    curl -s "http://localhost:3000" > /dev/null 2>&1 && break
done
curl -s "http://localhost:3000" > /dev/null 2>&1 || fail "前端启动失败"
pass "前端: http://localhost:3000"

echo ""
echo "========================================"
echo "  ${GREEN}全部服务已启动${NC}"
echo "  前端: http://localhost:3000"
echo "  后端: http://localhost:8080/api/publish"
echo "  退出: 关闭此窗口"
echo "========================================"

# 保持前台
wait
