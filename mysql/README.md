# mysql/ — 数据库初始化脚本总目录

三个库相互独立，目录前缀即建议的执行顺序；**目录内文件按文件名字典序执行即为正确顺序**。

| 顺序 | 目录 | 目标库 | 使用方 |
|---|---|---|---|
| 01 | `01-agent-scope-customer-work/` | `agent_scope_customer_work` | customer-work-app-server（8080，客服/工单/订单业务库） |
| 02 | `02-customer-admin/` | `customer_admin` | customer-admin-server（8082，后台管理库） |
| 03 | `03-xxl-job/` | `xxl_job` | XXL-JOB 调度中心（可选，未部署调度中心可跳过） |
| — | `schema-snapshot/` | 两个业务库 | **只读结构快照，不参与建库流程**，见下方专节 |

## 执行方式

```bash
# 库 1：客服业务库（单文件全量：DDL + 种子，可重复执行）
mysql -uroot -p --default-character-set=utf8mb4 agent_scope_customer_work \
  < 01-agent-scope-customer-work/customer-work-schema.sql

# 库 2：后台管理库（按序执行 01-V1 → 20-V20；文件名字典序即执行序）
for f in 02-customer-admin/*.sql; do
  mysql -uroot -p --default-character-set=utf8mb4 customer_admin < "$f"
done

# 库 3：XXL-JOB（如需）
mysql -uroot -p --default-character-set=utf8mb4 xxl_job < 03-xxl-job/xxl-job-schema.sql
```

> ⚠️ **中文字符集坑（实测踩过）**：脚本含中文表注释/菜单名，走 stdin 管道时客户端字符集可能协商回退到
> latin1，把中文 COMMENT **字节级写坏**（不是显示问题）。必须带 `--default-character-set=utf8mb4`，
> 或在脚本首行加 `SET NAMES utf8mb4;`。校验用
> `SET character_set_results = binary; SELECT HEX(...)` 比对真实字节，不要只看终端显示。

## 真源关系（改表先改真源，再同步这里）

| 本目录文件 | 真源 | 同步时机 |
|---|---|---|
| `01-agent-scope-customer-work/customer-work-schema.sql` | starter `src/main/resources/db/customerwork/migration/` 的 Flyway 迁移 + `infra/migration` 下的 V2/V9 两个 Java 迁移 | 每次改业务表结构 |
| `02-customer-admin/NN-V*.sql` | customer-admin-server `src/main/resources/db/migration/V*.sql`（Flyway，dev 环境自动执行） | 每新增一个迁移，复制过来并加两位序号前缀 |
| `03-xxl-job/xxl-job-schema.sql` | XXL-JOB 官方发行包 | 升级调度中心版本时 |

- `customer_admin` 库不再维护合并版 schema（历史的 `admin-schema.sql` 已删除——它欠账 V12~V18，
  内容不完整且易误导）；需要全新建库就按序执行 20 个迁移副本。
- 已有环境增量升级：只执行比当前库版本号大的迁移（Flyway 环境看 `flyway_schema_history` 表）。

## `schema-snapshot/` — 全量表结构快照

回答「这两个库现在长什么样」的直接答案。**只读，不参与任何建库流程**：

| 文件 | 内容 |
|---|---|
| `agent_scope_customer_work.sql` | 客服端业务库 47 张表（框架自建的 `agentscope_sessions` 不在内，见文件头说明） |
| `customer_admin.sql` | 后台管理库 88 张表 |

- **不要手工编辑**，改结构一律新增迁移；改快照文件不会对任何库生效。
- **不要对已有库执行**：里面是裸 `CREATE TABLE`，没有 `IF NOT EXISTS` 保护。
- 生产建库仍走 `01-`/`02-` 那两条路径——快照里没有种子数据，`02-` 那条还会留下
  `flyway_schema_history` 以便后续增量升级。

### 怎么刷新

```bash
./scripts/export-schema-snapshot.sh
```

各起一个带随机后缀的临时空库跑完全部迁移后逐表导出，最后删库。**刻意不从开发机的长期业务库导出**：
那个库被并行分支的迁移反复试跑过，沉积的结构与迁移产物无法区分，直接 dump 会把它们一并固化下来。

### 漏刷新会被拦下

`CustomerWorkSchemaSnapshotTest` / `CustomerAdminSchemaSnapshotTest` 每次跑测试都会重新迁移一个临时库、
把产物与快照逐字比对，不一致直接红并指出是哪张表的第几行。生成与校验共用同一段导出逻辑
（`SchemaSnapshotExporter`），不存在「脚本生成的和门禁期望的不是一回事」。

CI 跑全量 `mvn clean test`，因此这两个门禁在 PR 上自动生效，无需额外配置。MySQL 不可达时自动跳过。

> 快照内容取决于 MySQL 的 `SHOW CREATE TABLE` 输出格式。跨大版本（8.0 → 8.4）升级 MySQL 后
> 需要重新生成一次，属预期内的一次性刷新，不是漂移。

