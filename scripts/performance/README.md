# 客服台性能测量

本工具针对已批准的 10,000 条工单、每页 20 条、200 条历史消息验收。它不接入业务库，也不修改原有服务端配置。

## 当前状态

HTTP 装配自检、两个视口的浏览器 smoke 及两轮正式采样均已通过。每轮包含 400 个浏览器样本和 250 个查询样本，两轮使用相同生产构建与后端编译产物。首轮后冻结 `budgets.json`，独立复测通过全部 23 项预算检查；超限输入返回非零的对照也已验证。结果与交付状态见 `docs/admin-experience/performance-baseline-validation.md`。

HTTP 自检使用完整迁移的隔离库，验证了 10,000 条工单首/次/末页、坐席筛选、详情，以及按 30 条游标分七页取得 200 条唯一消息；缺少测量令牌、写请求和非本次工单路径被拒绝，关闭后自有库清理完成。自检暴露并修正了独立工厂未执行 MapperScan 的装配遗漏；测量客户端同时显式接受 JSON，与正式前端 Axios 的内容协商一致。

## 调用范围

- 生产构建页面通过本地 HTTP 静态资源服务与反向代理访问生产 `UserTicketController → UserTicketService → CustomerWorkTicketClient`。
- `CustomerWorkClientConfig` 每请求生成真实坐席签名；客服端实际 `AgentAuthWebFilter → AgentTicketController → TicketService/ChatLogService → MyBatis` 处理请求。
- 数据集使用 `CustomerWorkPersistenceConfig` 原有租户和分页插件、完整客服 Flyway 迁移。随机库中含本租户 10,000 条工单、其他租户 500 条对照，以及两个各有 200 条消息的会话。
- 登录坐席、菜单、WS 连接和辅助摘要为明确登记的夹具。辅助面板来源来自真实消息查询，摘要不调用模型。这些结果不用于证明登录性能、权限拦截器、WS 投递、模型推理或辅助摘要服务性能。
- Admin 测量端点仅绑定本机回环地址，要求本次随机令牌，仅允许指定 GET 接口。所有写方法被拒绝。启动失败或正常关闭后只清理本次创建的库，保留创建及清理标记。

## 运行条件

1. 使用 JDK 17 编译三个后端模块，完成本次要求的功能回归。运行 `customer-admin-web` 的生产构建。
2. 用 `prepare-classpath.py` 从已完成的后端 Surefire 报告提取实际运行依赖，并优先绑定三个模块的 `target/classes`，移除测试类目录和旧模块 jar。测量入口会核对编译工作树的后端源码与当前分支一致，记录源码与编译产物指纹。
3. 停止本任务的 Maven、其他 E2E 和构建负载，释放 4174 端口。保留设备、浏览器和源码指纹。MySQL 使用 `MYSQL_HOST`、`MYSQL_PORT`、`ADMIN_MYSQL_USERNAME`、`ADMIN_MYSQL_PASSWORD`；账号需要创建本次随机数据库的权限。
4. 首次使用新的输出目录执行 smoke；检查两个视口、真实总数/分页、跨租户对照、200 条消息及自有库清理。修正测量器后重新 smoke，不混入正式样本。
5. smoke 通过后以另一个新目录执行 measure。每场景 10 次预热、50 次有效采样，保留错误样本。正式结果另行审核预算和完成结论。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
# 生产构建不会读取 .env.development，需显式使用本测量代理的 /api 路径。
(cd customer-admin-web && VITE_API_BASE_URL=/api npm run build)

python3 scripts/performance/prepare-classpath.py \
  --backend-tree . \
  --output /fyoung/tmp/customer-performance-current.classpath

python3 scripts/performance/run-baseline.py \
  --classpath-file /fyoung/tmp/customer-performance-current.classpath \
  --output /fyoung/tmp/customer-performance-smoke \
  --mode smoke

python3 scripts/performance/run-baseline.py \
  --classpath-file /fyoung/tmp/customer-performance-current.classpath \
  --output /fyoung/tmp/customer-performance-measured \
  --mode measure

python3 scripts/performance/summarize-baseline.py /fyoung/tmp/customer-performance-measured
python3 scripts/performance/check-budget.py /fyoung/tmp/customer-performance-measured
```

输出包括源码及产物 SHA-256、设备/浏览器版本、实际 API 响应和耗时、动作与绘制就绪时间、逐帧间隔、长任务、截图、SQL 查询 CSV、进程退出码及清理证据。API 响应仅包含生成的测试数据。

SQL 基线独立记录总数查询、分页及对象映射；浏览器保留完整 HTTP 调用与 UI 呈现时间，两者不混为一个指标。滚动按真实消息分页逐步加载到 200 条后采样，未把后端 limit 忽略后一次返回 200 条。浏览器视口 390×844 的焦点检查属于桌面 Chrome 模拟，不登记为已暂缓的 H5 真机验收。

预算对应本次设备和数据规模：队列分页／筛选 P95 ≤150ms、200 消息切换 P95 ≤200ms、SQL 查询 P95 ≤50ms；滚动帧间隔 P95 ≤20ms、超过50ms的帧占比≤1%，采样窗口重叠的长任务最大≤100ms。一秒滚动时长是采样窗口，不作为页面响应时间。长任务由视口结束时的完整观察记录按时间窗口关联，覆盖延后送达的观察事件。更换机器、浏览器或数据规模时需重新记录条件与基线。
