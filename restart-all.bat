@echo off
chcp 65001 >nul
title 全系统联调 — 一键重启
echo ========================================
echo   全系统清理 + 重启
echo ========================================

REM ===== 杀进程 =====
echo [1/6] 清理旧进程...
taskkill /F /IM java.exe >nul 2>&1
taskkill /F /IM node.exe >nul 2>&1
timeout /t 3 >nul
echo 进程已清理

REM ===== MySQL =====
echo [2/6] 检查 MySQL...
"C:\Users\JY\mysql\bin\mysqld.exe" --defaults-file="C:\Users\JY\mysql\my.ini" --console >nul 2>&1 &
timeout /t 3 >nul
"C:\Users\JY\mysql\bin\mysql.exe" -u root -proot -e "SELECT 1" >nul 2>&1 && echo MySQL OK || echo MySQL FAIL

REM ===== Redis =====
echo [3/6] 检查 Redis...
cd /d E:\stock-system\online-info-publish
start "Redis" redis-server.exe
timeout /t 3 >nul
redis-cli.exe ping >nul 2>&1 && echo Redis OK || echo Redis FAIL

REM ===== Kafka =====
echo [4/6] 启动 Kafka...
cd /d E:\stock-system\kafka_2.13-3.6.1
set JAVA_HOME=E:\stock-system\online-info-publish\jdk-17.0.12+7
set KAFKA_GC_LOG_OPTS=
start "Kafka" bin\windows\kafka-server-start.bat config\kraft.properties
timeout /t 20 >nul
netstat -ano | find ":9092" | find "LISTENING" >nul && echo Kafka OK || echo Kafka FAIL

REM ===== 后端系统 =====
echo [5/6] 启动后端...
set JAVA_HOME=E:\stock-system\online-info-publish\jdk-17.0.12+7

start "中央交易:8082" cmd /c "set DB_USER=root&&set DB_PASSWORD=root&&set DB_HOST=localhost&&set DB_PORT=3306&&set DB_DATABASE=central_trading&&%JAVA_HOME%\bin\java -jar E:\stock-system\central-trading\target\central-trading-1.0.0-SNAPSHOT.jar"
start "账户系统:8080" cmd /c "set ACCOUNT_DB_USERNAME=root&&set ACCOUNT_DB_PASSWORD=root&&%JAVA_HOME%\bin\java -jar E:\stock-system\account-management\target\account-management-1.0.0-SNAPSHOT.jar"
REM 确保交易管理配置存在
if not exist "E:\stock-system\trade-management\config.properties" (
    echo db.password=root> "E:\stock-system\trade-management\config.properties"
    echo db.url=jdbc:mysql://localhost:3306/stock_trade_management?useUnicode=true^&characterEncoding=utf8^&serverTimezone=Asia/Shanghai>> "E:\stock-system\trade-management\config.properties"
    echo server.port=8081>> "E:\stock-system\trade-management\config.properties"
)
start "交易管理:8081" cmd /c "cd /d E:\stock-system\trade-management&&%JAVA_HOME%\bin\java -jar target\stock-trade-management-1.0.0-shaded.jar"
start "信息发布:8083" cmd /c "%JAVA_HOME%\bin\java -jar E:\stock-system\online-info-publish\target\online-info-publish-subsys-1.0.0-SNAPSHOT.jar"

timeout /t 25 >nul
echo 后端已启动

REM ===== 前端 + 交易客户端 =====
echo [6/6] 启动前端...
start "交易客户端:8090" cmd /c "cd /d E:\stock-system\trading-client&&set DB_PASSWORD=root&&set KAFKA_ENABLED=true&&set KAFKA_BROKERS=localhost:9092&&set KAFKAJS_NO_PARTITIONER_WARNING=1&&node server\app.js"
start "账户前端:5173" cmd /c "cd /d E:\stock-system\account-management\frontend&&npm run dev"
start "信息发布前端:3000" cmd /c "cd /d E:\stock-system\online-info-publish\publish-frontend&&npm run dev"

timeout /t 15 >nul

REM ===== 自动交易 =====
start "自动交易模拟" cmd /c "cd /d E:\stock-system&&bash auto-trade.sh"

echo.
echo ========================================
echo   全部启动完成！端口：
echo   :3000 信息发布前端  :5173 账户前端
echo   :8080 账户系统     :8081 交易管理
echo   :8082 中央交易     :8083 信息发布后端
echo   :8090 交易客户端   :9092 Kafka
echo ========================================
echo   打开 http://localhost:3000
echo   登录: 2026000000000001 / 123
pause
