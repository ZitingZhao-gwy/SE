# 全系统集成联调编排计划

## 目录结构

```
E:/stock-system/
├── online-info-publish/        # 网上信息发布子系统（我们）
├── account-management/         # 账户管理系统
├── central-trading/            # 中央交易系统
├── trade-management/           # 交易管理系统
├── trading-client/             # 交易客户端
├── start-all.bat               # Windows 一键启动（多窗口）
├── stop-all.bat                # 一键停止
└── INTEGRATION_PLAN.md         # 本文档
```

## 端口分配

| 端口 | 系统 | 说明 |
|------|------|------|
| 3306 | MySQL | 共享数据库（需手动建各库） |
| 6379 | Redis | 共享缓存（我们的数据前缀隔离） |
| 5173 | 账户管理系统前端 | Vite React |
| 8080 | 账户管理系统后端 | Spring Boot |
| 8082 | 中央交易系统后端 | Spring Boot |
| 8083 | 网上信息发布后端 | Spring Boot（我们） |
| 8090 | 交易客户端后端+前端 | Node.js |
| 3000 | 网上信息发布前端 | Vite Vue3（我们） |

## 启动顺序

```
1. MySQL + Redis        ← 基础设施
2. 中央交易系统 :8082    ← 撮合引擎先启动
3. 账户管理系统 :8080    ← SSO 认证
4. 交易管理系统           ← 管理后台
5. 网上信息发布 :8083    ← 我们（依赖 8080+8082）
6. 交易客户端 :8090      ← 依赖 8080+8082
7. 账户系统前端 :5173    ← React 页面
8. 网上信息发布前端 :3000 ← 我们
```

## 各系统启动命令

### 中央交易系统 (:8082)
```bash
cd E:/stock-system/central-trading
mvn spring-boot:run
```

### 账户管理系统 (:8080)
```bash
cd E:/stock-system/account-management
mvn spring-boot:run
# 前端另开终端:
cd E:/stock-system/account-management/frontend
npm install && npm run dev
```

### 交易客户端 (:8090)
```bash
cd E:/stock-system/trading-client
npm install && npm start
```

### 网上信息发布 — 我们 (:8083 + :3000)
```bash
cd E:/stock-system/online-info-publish
bash start-all.sh
```
