# mysql/ — 数据库初始化脚本总目录

三个库相互独立，目录前缀即建议的执行顺序；**目录内文件按文件名字典序执行即为正确顺序**。

| 顺序 | 目录 | 目标库 | 使用方 |
|---|---|---|---|
| 01 | `01-agent-scope-customer-work/` | `agent_scope_customer_work` | customer-work-app-server（8080，客服/工单/订单业务库） |
| 02 | `02-customer-admin/` | `customer_admin` | customer-admin-server（8082，后台管理库） |
| 03 | `03-xxl-job/` | `xxl_job` | XXL-JOB 调度中心（可选，未部署调度中心可跳过） |

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
| `01-agent-scope-customer-work/customer-work-schema.sql` | starter `src/main/resources/customerwork/schema/customer-work-schema.sql`（SchemaInitializer 启动自动建表用） | 每次改业务表结构 |
| `02-customer-admin/NN-V*.sql` | customer-admin-server `src/main/resources/db/migration/V*.sql`（Flyway，dev 环境自动执行） | 每新增一个迁移，复制过来并加两位序号前缀 |
| `03-xxl-job/xxl-job-schema.sql` | XXL-JOB 官方发行包 | 升级调度中心版本时 |

- `customer_admin` 库不再维护合并版 schema（历史的 `admin-schema.sql` 已删除——它欠账 V12~V18，
  内容不完整且易误导）；需要全新建库就按序执行 20 个迁移副本。
- 已有环境增量升级：只执行比当前库版本号大的迁移（Flyway 环境看 `flyway_schema_history` 表）。
