# 网上信息发布子系统

股票交易系统的"数据视窗与计算引擎"。不处理交易，消费中央交易系统原始成交流水，实时计算盘口、K线、主力动向，按权限分层展示。

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 17, Spring Boot 3.3, MyBatis-Plus 3.5, Redis (Lettuce + Redisson), MySQL 8.0 |
| 前端 | Vue 3 (Composition API), Vite, Vue Router 4, Pinia, Axios, ECharts |

## 快速启动

### 后端

```bash
# 1. 创建数据库
mysql -u root -p -e "CREATE DATABASE stock_publish DEFAULT CHARSET utf8mb4;"

# 2. 执行建表 DDL（见 PROJECT_SPEC.md 第3节）

# 3. 启动 Redis（默认 localhost:6379）

# 4. 启动后端
export PATH="$(pwd)/apache-maven-3.9.9/bin:$PATH"
mvn spring-boot:run
```

### 前端

```bash
cd publish-frontend
npm install
npm run dev
# 访问 http://localhost:3000，API 代理到 localhost:8080
```

## 项目结构

```
├── pom.xml                          # Maven 依赖
├── src/main/java/com/stock/publish/
│   ├── PublishApplication.java      # 启动类
│   ├── config/                      # Redis / Redisson / WebMvc 配置
│   ├── entity/                      # 实体（3 张表）
│   ├── mapper/                      # MyBatis-Plus Mapper
│   ├── dto/                         # 数据传输对象
│   ├── interceptor/                 # AuthInterceptor + UserContext
│   ├── service/                     # 服务接口 + impl
│   ├── controller/                  # REST 接口
│   └── calculation/                 # TopTraderEngine + KLineAggregator
├── publish-frontend/                # Vue 3 前端
│   └── src/
│       ├── router/                  # 路由
│       ├── stores/                  # Pinia 状态管理
│       ├── api/                     # Axios 请求封装
│       ├── views/                   # Portal / StockDetail
│       └── components/              # KLineChart / UpgradeModal
├── PROJECT_SPEC.md                  # 完整设计规格
├── TEAM_ROLES.md                    # 6 人分工说明
└── .gitignore
```

## 用户权限三层

| 角色 | 判定 | 权限 |
|------|------|------|
| GUEST | 无 Token | 首页大盘、股票列表、模糊搜索、价格 |
| STANDARD | Token 有效 + 证书已绑定 | + 盘口(买一/卖一)、主力动向(Top1)、日K线 |
| PREMIUM_VIP | STANDARD + is_premium=true | + 全尺度K线(5M/1H/1D/1M/1Y)、MA/MACD 指标 |

## 相关文档

- [完整设计规格](./PROJECT_SPEC.md)
- [团队分工](./TEAM_ROLES.md)
