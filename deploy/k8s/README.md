# Kubernetes 部署清单

多副本部署的最小可用清单。按序号顺序 apply 即可：

```bash
kubectl apply -f deploy/k8s/00-namespace.yaml
kubectl apply -f deploy/k8s/10-configmap.yaml
# 先按下方说明改好真实密钥再 apply
kubectl apply -f deploy/k8s/20-secret.example.yaml
kubectl apply -f deploy/k8s/30-app-server.yaml
kubectl apply -f deploy/k8s/40-admin-server.yaml
kubectl apply -f deploy/k8s/50-hpa.yaml
```

构建两套镜像（admin 不是 app 镜像改端口，两者主类和依赖都不同）：

```bash
docker build -f Dockerfile -t customer-work/app-server:latest .
docker build -f Dockerfile.admin -t customer-work/admin-server:latest .
```

应用清单前必须把 `10-configmap.yaml` 中的 `CW_WS_URL=wss://REPLACE_ME/ws/agent` 换成浏览器可达的
网关地址。集群内 Service DNS 只能给 admin 后端访问，不能下发给集群外浏览器。

## 多副本的前提（不做就等于没扩容）

单副本能跑不代表多副本正确。以下三项默认是进程内实现，`10-configmap.yaml` 里已全部切成 Redis，
**改成多副本前务必确认它们生效**，否则会出现"扩容后配额翻倍、同一会话历史被写坏"这类问题：

| 能力 | 进程内的后果 | 配置项 |
|---|---|---|
| 限流 | N 个副本各算各的，实际放行量是配置值的 N 倍 | `customer-work.distributed.counter-mode=redis` |
| 成本熔断 | 同上，token 预算被放大 N 倍，"熔断"形同虚设 | 同上（与限流共用计数器） |
| 会话串行锁 | 同一会话的并发请求落到不同副本，对话历史交叉覆盖 | `customer-work.distributed.session-lock-mode=redis` |

会话锁切到 Redis 后，网关不再需要按会话做 sticky 路由——这正是能水平扩缩容的前提。

## 依赖的外部服务

清单只覆盖应用本身。MySQL / Redis / MinIO / Nacos / XXL-JOB 按各自的运维方式部署（云托管或独立 StatefulSet），
在 `10-configmap.yaml` 与 `20-secret.example.yaml` 里填地址与凭据。

## 密钥

`20-secret.example.yaml` 是**模板**，不要直接把真实密钥提交进仓库。生产建议用
外部密钥管理（External Secrets Operator / Vault / 云厂商 KMS）注入，或至少：

```bash
kubectl create secret generic customer-work-secret -n customer-work \
  --from-literal=DASHSCOPE_API_KEY='真实值' \
  --from-literal=MYSQL_PASSWORD='真实值' \
  --from-literal=REDIS_PASSWORD='真实值' \
  --from-literal=NACOS_PASSWORD='真实值' \
  --from-literal=AUTH_KEY_ID='partner-primary' \
  --from-literal=AUTH_KEY_HASH='原始 API Key 的 64 位 SHA-256 小写摘要' \
  --from-literal=AUTH_KEY_TENANT_ID='default' \
  --from-literal=AUTH_KEY_SCOPES='*' \
  --from-literal=AUTH_KEY_EPOCH='1' \
  --from-literal=CW_USER_JWT_SECRET='真实值' \
  --from-literal=CW_AGENT_WS_SECRET='真实值' \
  --from-literal=MINIO_ACCESS_KEY='真实值' \
  --from-literal=MINIO_SECRET_KEY='真实值' \
  --from-literal=SPRING_APPLICATION_JSON='{"customer-work":{"security":{"approval-auth":{"operators":{"审批token":"操作员姓名"}}}}}' \
  --from-literal=ADMIN_AES_SECRET_KEY='真实值' \
  --from-literal=ADMIN_MYSQL_PASSWORD='真实值' \
  --from-literal=ADMIN_REDIS_PASSWORD='真实值' \
  --from-literal=ADMIN_CUSTOMER_WORK_API_KEY_ID='与 AUTH_KEY_ID 相同' \
  --from-literal=ADMIN_CUSTOMER_WORK_API_KEY='与 8080 鉴权配置一致的真实值' \
  --from-literal=CW_RUNTIME_CONFIG_ACK_TOKEN_0='app-0 ACK 专用高强度令牌' \
  --from-literal=CW_RUNTIME_CONFIG_ACK_TOKEN_1='app-1 ACK 专用高强度令牌' \
  --from-literal=ADMIN_RUNTIME_PUBLISH_ACK_IDENTITIES='default|customer-work-app-0|app-0 ACK 令牌,default|customer-work-app-1|app-1 ACK 令牌' \
  --from-literal=ADMIN_RUNTIME_PUBLISH_SIGNING_SECRET='运行时配置 HMAC 密钥' \
  --from-literal=CW_RUNTIME_CONFIG_SIGNING_SECRET='与发布端相同的运行时配置 HMAC 密钥' \
  --from-literal=ADMIN_OPEN_API_TOKEN='独立的 admin Open API 令牌' \
  --from-literal=ADMIN_RUNTIME_PUBLISH_NACOS_PASSWORD='与 Nacos 密码相同' \
  --from-literal=CUSTOMER_WORK_CONFIG_AES_KEY='与 ADMIN_AES_SECRET_KEY 相同' \
  --from-literal=CW_NOTIFICATION_AUTH_TOKEN='通知网关 Bearer 令牌'
```

摘要可用 `printf %s "$RAW_API_KEY" | shasum -a 256` 生成；8080 不接收明文配置。调用方保管原始值，
请求同时发送 `X-API-Key-Id: partner-primary` 与 `X-API-Key: <原始值>`。多版本轮换、到期时间和
`minimum-epochs` 请通过挂载的 Secret yml 或 `SPRING_APPLICATION_JSON` 下发，先并存新旧 epoch，
确认流量切换后再提高最小 epoch 撤销旧凭据。

`prod` profile 会在启动时执行硬门禁：上面的 API Key、JWT、坐席密钥、审批操作员映射、MinIO 凭据，
运行时配置 ACK、通知 Webhook，以及 MySQL/Redis/Flyway/技能库配置任一缺失，Pod 都不会进入 Ready。模板中的 `REPLACE_ME` 只是占位符，
不能用于真实环境。

app 使用 StatefulSet 固定为 `customer-work-app-0/1`。initContainer 根据序号只写入本实例的
`runtime-config-instance-id` 与 ACK token，admin 则在任务入队时把这两个目标冻结。当前 HPA 上限因此固定为 2；
扩容前必须先增加新序号的独立 token、`ADMIN_RUNTIME_PUBLISH_ACK_IDENTITIES` 和 initContainer 映射，再提高上限，
否则新实例无法可靠回执，旧发布任务的完成条件也不应被事后改变。

## HPA 的伸缩依据

默认按 CPU 70% / 内存 80% 伸缩。对话服务的实际瓶颈通常是**等待模型响应**而非 CPU——
真正贴合负载的指标是并发请求数或队列深度，那需要 Prometheus Adapter 提供自定义指标
（`50-hpa.yaml` 末尾附了示例，装了 adapter 再启用）。CPU/内存只是在没有 adapter 时的兜底依据。

## 滚动发布与优雅停机

`terminationGracePeriodSeconds` 与应用的 `customer-work.runtime.shutdown-timeout-seconds`（默认 30）
需要匹配：K8s 的宽限期必须**大于**应用自身的停机超时，否则正在处理的对话会被 SIGKILL 打断。
当前设为 45 秒，改应用配置时记得同步这里。
