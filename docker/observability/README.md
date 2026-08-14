# 可观测性监控栈（Prometheus + Grafana + Alertmanager + 钉钉 + Tempo）

面向 AgentScope 智能客服系统的一键监控栈：抓取 `customer-work-app-server`(8080) 与
`customer-admin-server`(8082) 暴露的 Micrometer 指标，告警经钉钉群机器人推送，并预留链路追踪
（Tempo）对接位。本目录只是"观测端"编排，不修改任何业务代码/配置。

## 启动方式

```bash
cd docker/observability
cp .env.example .env        # 首次使用：按需改 Grafana 密码 / 钉钉 webhook / MySQL 只读账号
docker compose up -d        # 后台启动全部 5 个服务
docker compose ps           # 确认全部 healthy / running
docker compose logs -f prometheus   # 看抓取日志（排查 up==0 时常用）
docker compose down         # 停止并移除容器（数据卷保留，下次启动数据还在）
docker compose down -v      # 连数据卷一起清空（彻底重来）
```

## 端口一览

| 服务 | 端口 | 用途 |
|---|---|---|
| Prometheus | 9090 | Web UI / HTTP API，查表达式、看 targets 状态 |
| Grafana | 3000 | 看板 UI，默认账号 `admin`，密码见 `.env` 的 `GRAFANA_ADMIN_PASSWORD` |
| Alertmanager | 9093 | Web UI，查看当前告警 / 静默规则 |
| Tempo | 4317 | OTLP gRPC 接收（链路追踪数据写入端口） |
| Tempo | 4318 | OTLP HTTP 接收 |
| Tempo | 3200 | Tempo 查询 API，供 Grafana Tempo 数据源对接 |
| prometheus-webhook-dingtalk | 8060 | 内部端口，把 Alertmanager 的 webhook 转成钉钉消息格式（也对外暴露便于调试） |

以上均为监控栈专用端口，与本项目已用端口
（3306/6379/8848/8088/8868/9000/9001/13306/9200/7474/7687/19000/19001/6333/6334/5601/20002/8080/8081/8082/5174/5175）
均不冲突，已核对。

## 数据持久化

全部走 named volume（`docker compose down` 不删，`down -v` 才清空）：

| Volume | 内容 | 留存策略 |
|---|---|---|
| `prometheus-data` | 指标 TSDB | `--storage.tsdb.retention.time=30d`，超期自动清理 |
| `grafana-data` | 用户/会话/星标等 Grafana 自身状态 | 不过期，随卷保留 |
| `alertmanager-data` | 静默规则 / 通知发送状态 | 不过期，随卷保留 |
| `tempo-data` | trace 数据 | `compactor.compaction.block_retention: 168h`（7天），超期自动清理 |
| `dingtalk-config` | 由 `dingtalk-config-init` 一次性容器生成的真实 webhook 配置 | 改了 `.env` 里的 `DINGTALK_WEBHOOK_URL` 后需 `docker compose up -d --force-recreate dingtalk-config-init dingtalk-webhook` 重新生成 |

## 钉钉 webhook 接入步骤

1. 钉钉群 → 群设置 → 智能群助手 → 添加机器人 → 自定义(Webhook)，复制生成的 Webhook 地址。
2. 把地址填进 `.env` 的 `DINGTALK_WEBHOOK_URL`。
3. 若机器人安全设置选择"加签"（而非关键词/IP白名单），把签名密钥填进 `dingtalk/config.yml` 的
   `secret` 字段（钉钉后台复制的以 `SEC` 开头的字符串）。
4. `docker compose up -d` 或重建 `dingtalk-config-init` + `dingtalk-webhook` 两个服务生效。
5. 验证：Alertmanager UI（9093）手工发一条测试告警，或等真实告警触发，群里应收到消息。

**实现说明**：`timonwong/prometheus-webhook-dingtalk` 镜像的 `config.yml` 不支持 `${ENV_VAR}`
展开（这点和 Grafana provisioning 不一样），所以 `dingtalk/config.yml` 是一份带
`__DINGTALK_WEBHOOK_URL__` 占位符的模板。`docker-compose.yml` 里的一次性容器
`dingtalk-config-init` 在启动时用 `sed` 把占位符替换成 `.env` 里的真实值，写进
`dingtalk-config` 数据卷，`dingtalk-webhook` 服务再挂载这个卷读取——这样只需维护一份模板，
不会出现"改了 `.env` 忘记同步改 `config.yml`"的漂移。如果不想用这套自动替换（比如 CI 环境
不方便传密钥当 sed 参数），可以按 `dingtalk/config.yml` 文件头注释里写的手工方式：复制一份
`config.local.yml` 填真实地址，改 `docker-compose.yml` 里 `dingtalk-webhook` 挂载的文件路径。

## 与 app-server(8080) / admin-server(8082) 的关系

本栈是纯只读观测方，通过 Prometheus 定时拉取两个业务服务的 `/actuator/prometheus` 端点，
不需要业务服务反向感知监控栈的存在，也不修改任何业务配置文件：

- `customer-work-app-server` 的 `application.yml` 已默认开启
  `management.endpoints.web.exposure.include: health,info,metrics,prometheus`，开箱即被抓取。
- `customer-admin-server` 的默认 `application.yml` 与 `application-prod.yml` 均已开启
  `health,prometheus`，本地 IDE 启动同样开箱即被抓取（`/actuator/**` 在 Sa-Token 白名单里，
  抓取不需要登录态）。
- 链路追踪写入 Tempo 需打开 OTel 开关：starter 侧 `customer-work.observability.otel.enabled=true`、
  admin 侧 `ADMIN_OTEL_ENABLED=true`，导出地址默认已指向 `http://localhost:4317`（gRPC）。
  注意 `observability.tracing-enabled` 是另一回事——那个只注册打日志的 `LoggingTracer`，不出 span、
  不导出，两者不需要同时开（详见 `docs/功能与配置全量参考.md` §6.13c）。

## 生产注意事项

- **MySQL 只读账号必须单独建**：`.env.example` 里 Grafana 的 MySQL 数据源默认值是
  `root/root`（对齐本机开发环境约定），生产环境务必单独建一个只有 `SELECT` 权限的账号
  （例如 `GRANT SELECT ON agent_scope_customer_work.* TO 'grafana_ro'@'%'`），不要让报表
  查询账号具备写权限。
- **钉钉 webhook 地址与签名密钥是敏感信息**：`.env` 与 `dingtalk/config.yml`（若手工填了真实
  值）都不应提交进仓库；本目录的 `.env.example` 只放占位值。
- **Grafana 管理员密码**：`.env` 的 `GRAFANA_ADMIN_PASSWORD` 仅在首次启动（空数据卷）时生效，
  生产环境改密后不要指望改 `.env` 就能覆盖已落盘的密码，需要配合 Grafana 自身的改密流程。
- **告警阈值均可调**：见 `prometheus/rules/customerwork-alerts.yml` 每条规则旁的中文注释，
  按实际流量/SLO 调整后建议先在 `docker run --entrypoint promtool ... check rules` 校验语法
  再重启 Prometheus（`docker compose exec prometheus kill -HUP 1` 或
  `curl -X POST http://localhost:9090/-/reload`，已开 `--web.enable-lifecycle`）。
- **告警指标前提**：`customerwork.synthetic.probe`（合成监控）与
  `customerwork.sensitive.*`（敏感词护栏）默认关闭，需要业务侧分别打开
  `customer-work.synthetic-monitor.enabled=true` 与 `customer-work.sensitive-word.enabled=true`
  才会产生对应指标，否则相关告警规则永远不会触发（不是没生效，是没数据）。
- **可靠投递队列**：`customerwork_outbox_depth{status=...}` 与
  `customerwork_deadletter_depth{status=...}` 始终暴露 pending/processing/abandoned 三态；
  abandoned 大于 0 表示自动补偿已耗尽，不能只重启服务，必须按消息 ID 做人工核对与补偿。

## 面板显示 No data 怎么排查

先分清三种成因，绝大多数"没数据"属于前两种，不是栈坏了：

1. **该能力默认关着** —— 护栏类中间件默认关闭，没开就不会有任何计数。对应关系见
   `docs/功能与配置全量参考.md` §七配置项总表。
2. **该事件本来就没发生** —— 安全类指标（注入拦截、敏感词命中）是"攻击发生才计数"，
   空面板恰恰是正常状态。要验证生效，得主动造数，见下条。
3. **抓取周期没到** —— Prometheus 每 15s 拉一次，刚发生的事件等一个周期再看。

**主动造数验证**：注入防护（直接 / 间接）两块面板的完整自测步骤——含配置开关、可直接复制的
curl 与 SQL、以及每步的预期结果——见 `docs/功能与配置全量参考.md` **§6.14.3 注入防护自测**。
其中两个易踩的点先在这里点明：

- 直接注入拦截（`prompt.guard.blocked`）**只装配在 8080 客服链路**，admin(8082) 侧没有这道闸，
  在后台工作台发注入话术这个面板不会动。
- 间接注入的两个计数器（`spotlighted` / `detected`）都计"工具结果流进下一轮推理"，
  **会话里没有工具调用就恒为 0**；且 `detected` 刻意只告警不拦截，验证要看指标与 error 日志，
  不要看对话回复有没有变化。

排查顺序：Grafana 面板 → Prometheus 直查该 `_total` 指标（http://localhost:9090 输入表达式）→
业务服务 `/actuator/prometheus` 原始输出里 grep 指标名。哪一层开始有数，问题就在它的下一层。
