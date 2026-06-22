# Git 协作操作指南

## 分支与分工对应

| 分支 | 负责角色 | 工作内容 |
|------|----------|----------|
| `feat/auth-and-user` | B1 | AuthInterceptor + UserController + UserServiceImpl |
| `feat/search-and-quote` | B2 | StockController + StockServiceImpl + MarketServiceImpl + MarketController.quote |
| `feat/kline-and-trader` | B3 | TopTraderEngine + KLineAggregator + MarketController.kline |
| `feat/frontend-infra` | F1 | router + store + api + App.vue + Portal.vue |
| `feat/stock-detail` | F2 | StockDetail.vue |
| `feat/charts-and-upgrade` | F3 | KLineChart.vue + UpgradeModal.vue |

```
main ────────────────────────────────────────────
  ├── feat/auth-and-user       (B1)
  ├── feat/search-and-quote    (B2)
  ├── feat/kline-and-trader    (B3)
  ├── feat/frontend-infra      (F1)
  ├── feat/stock-detail        (F2)
  └── feat/charts-and-upgrade  (F3)
```

---

## 环境准备

```bash
# 1. 克隆仓库
git clone https://github.com/JY-GIEGIEGIE/Online-Info-Publishing-System.git
cd Online-Info-Publishing-System

# 2. 切换到自己的分支
git checkout feat/auth-and-user   # 示例：B1 的分支

# 3. 确认在自己的分支上
git branch   # 前面有 * 的就是当前分支
```

---

## 日常开发流程

```bash
# 1. 写代码前，先拉取 main 最新代码（如果别人已合 PR）
git checkout main
git pull origin main

# 2. 切回自己的分支，合并 main 的更新
git checkout feat/auth-and-user
git merge main

# 3. 写代码、改代码...

# 4. 查看改动
git status
git diff

# 5. 添加并提交（小步提交，一次只做一件事）
git add src/main/java/com/stock/publish/interceptor/AuthInterceptor.java
git commit -m "feat(auth): 实现 Token 提取与 GUEST 降级"

# 6. 继续写、继续提交
git commit -m "feat(auth): 对接外部 SSO 证书校验接口"

# 7. 推送到远程
git push origin feat/auth-and-user
```

---

## Commit 格式

```
<type>(<scope>): <简短描述>

type:  feat(新功能) | fix(修复) | refactor(重构) | docs(文档)
scope: auth | user | search | quote | kline | trader | portal | detail | chart | upgrade
```

示例：
```
feat(auth): 实现 SSO Token 提取与证书绑定校验
feat(quote): 实现 Redisson 分布式锁防缓存击穿
feat(kline): 实现 1H/1D 动态聚合算法
feat(portal): 实现首页搜索框与5秒行情轮询
fix(chart): 修复 window resize 时 ECharts 未自适应
```

---

## 发起 Pull Request (PR)

```bash
# Push 后在 GitHub 网页操作：
# 1. 打开仓库 → Pull requests → New pull request
# 2. base: main ← compare: feat/auth-and-user
# 3. 填写 PR 标题和描述（见下方模板）
# 4. Create pull request
# 5. 通知组长 Review
```

**PR 描述模板**：
```markdown
## 改动内容
- 实现了 XXX 功能
- 修复了 XXX 问题

## 涉及文件
- interceptor/AuthInterceptor.java
- controller/UserController.java

## 测试方法
1. 不带 Token 请求 /quote → 应返回 GUEST 级别数据
2. 带有效 Token 请求 → 应返回完整数据
3. POST /upgrade → 数据库 is_premium 应变为 true

## 截图（前端 PR 必填）
（贴截图）
```

---

## 合并顺序

```
第1轮：B1(鉴权) 先合          ← 最优先，其他人需要 UserContext
第2轮：B2(行情) + B3(计算)     ← 可以并行 PR
第3轮：F1(基础设施) 先合       ← F2/F3 依赖 router/store/api
第4轮：F2 + F3 并行 PR
```

B1 合并后，其他人执行：
```bash
git checkout main && git pull
git checkout feat/你的分支
git merge main
```

---

## 解决冲突

```bash
# 合并 main 时如果冲突：
git merge main
# CONFLICT 出现 → 打开冲突文件，找到 <<<<<<< 标记
# 手动编辑，保留正确的代码，删除 <<<<<<< ======= >>>>>>> 标记
git add 冲突文件名
git commit -m "merge: 解决与 main 的冲突"
git push
```

---

## Review 检查清单

- [ ] 代码能否编译通过
- [ ] 方法签名是否符合接口契约
- [ ] TODO 注释是否全部解决
- [ ] 有无硬编码的敏感信息（密码、密钥）
- [ ] 前端 `setInterval` 是否有对应的 `clearInterval`
- [ ] 权限控制是否正确（GUEST/STANDARD/VIP 边界）
- [ ] Commit message 是否清晰

---

## 禁止事项

- **禁止直接 push 到 main 分支**
- **禁止 force push (`git push -f`)**
- **禁止提交 node_modules/、target/、.env 等文件**（.gitignore 已配置）
- **禁止提交时跳过 Review 直接合入 main**
