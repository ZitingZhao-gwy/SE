@echo off
chcp 65001 >nul
title 全系统集成联调 — 主控台

echo ========================================
echo   股票交易系统 — 全系统集成联调
echo ========================================
echo.

REM ===== 检查 MySQL =====
echo [1/6] 检查 MySQL...
"C:\Users\JY\mysql\bin\mysql.exe" -u root -proot -e "SELECT 1" >nul 2>&1
if errorlevel 1 (
    echo MySQL 未运行，正在启动...
    start "MySQL" "C:\Users\JY\mysql\bin\mysqld.exe" --defaults-file="C:\Users\JY\mysql\my.ini" --console
    timeout /t 5 >nul
)
echo MySQL OK

REM ===== 创建各系统数据库 =====
echo [2/6] 创建数据库...
"C:\Users\JY\mysql\bin\mysql.exe" -u root -proot -e "CREATE DATABASE IF NOT EXISTS account_db DEFAULT CHARSET utf8mb4;" 2>nul
"C:\Users\JY\mysql\bin\mysql.exe" -u root -proot -e "CREATE DATABASE IF NOT EXISTS central_trading DEFAULT CHARSET utf8mb4;" 2>nul
"C:\Users\JY\mysql\bin\mysql.exe" -u root -proot -e "CREATE DATABASE IF NOT EXISTS trading_client DEFAULT CHARSET utf8mb4;" 2>nul
echo 数据库就绪

REM ===== 检查 Redis =====
echo [3/6] 检查 Redis...
cd /d E:\stock-system\online-info-publish
redis-cli.exe ping >nul 2>&1
if errorlevel 1 (
    echo Redis 未运行，正在启动...
    start "Redis" redis-server.exe
    timeout /t 3 >nul
)
echo Redis OK

REM ===== 设置环境变量 =====
set ACCOUNT_DB_USERNAME=root
set ACCOUNT_DB_PASSWORD=root
set DB_USER=root
set DB_PASSWORD=root

REM ===== 启动各子系统（独立窗口）=====
echo [4/6] 启动后端子系统...

REM 中央交易系统 (:8082)
start "中央交易系统" cmd /c "cd /d E:\stock-system\central-trading && mvn spring-boot:run"

REM 账户管理系统后端 (:8080)
start "账户系统后端" cmd /c "cd /d E:\stock-system\account-management && set ACCOUNT_DB_USERNAME=root && set ACCOUNT_DB_PASSWORD=root && mvn spring-boot:run"

REM 网上信息发布 — 我们 (:8083)
start "网上信息发布后端" cmd /c "cd /d E:\stock-system\online-info-publish && d:\info^ code\apache-maven-3.9.9\bin\mvn package -DskipTests && java -jar target\online-info-publish-subsys-1.0.0-SNAPSHOT.jar"

REM 交易客户端 (:8090)
start "交易客户端" cmd /c "cd /d E:\stock-system\trading-client && npm start"

echo 后端子系统已启动（等待约20秒初始化）...
timeout /t 20 >nul

REM ===== 启动前端 =====
echo [5/6] 启动前端...
start "账户系统前端" cmd /c "cd /d E:\stock-system\account-management\frontend && npm run dev"
start "网上信息发布前端" cmd /c "cd /d E:\stock-system\online-info-publish\publish-frontend && npm run dev"

echo.
echo ========================================
echo   全部系统启动中，请稍候...
echo.
echo   端口一览:
echo     5173  账户系统前端
echo     8080  账户系统后端
echo     8082  中央交易系统
echo     8083  网上信息发布后端 ★
echo     8090  交易客户端
echo     3000  网上信息发布前端 ★
echo ========================================
echo.
echo   访问 http://localhost:3000 进入本系统
echo.
pause
