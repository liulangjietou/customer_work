# 知识缺口统计使用实际检索结果

## 问题与失败复现

未配置可用知识库、检索失败和正常未命中原先都由 KnowledgeRetrievalService 返回 null。KnowledgeInjectionMiddleware 只在异常向上抛出时识别故障，因此把前两种情况写成了知识缺口；外部 KnowledgeSearchOps 还会在 HTTP 503 或超时后返回空列表，异常在更早的一层已被消化。

原测试分别验证服务“吞异常不打断对话”和中间件“抛异常不计缺口”，没有接起实际两层。先增加服务与中间件的组合用例，3 项中 2 项断言失败；再连接 JDK HTTP 客户端与随机端口服务器，包含 HTTP 503 的 4 项中 3 项断言失败、0 错误。证明不是仅由测试桩抛异常制造的现象。

## 调用链与职责

调用方为后台工作区的 KnowledgeRetrievalMiddleware；starter KnowledgeInjectionMiddleware 通过 KnowledgeRetrievalProvider 调用 Admin KnowledgeRetrievalService，后者按原有 Agent 版本绑定读取知识，外部请求经 KnowledgeSearchClient → KnowledgeSearchOps。统计继续使用原来的 KnowledgeGapRecorder 和请求线程捕获的可信来源，持久化、分类和治理接口不变。

- HTTP 执行核心返回节点快照与完整性：每库失败、整体超时或等待中断都留下不完整状态，已成功的节点仍按原阈值与排序截断返回；返回后的迟到任务不能改变这份结果。
- Admin 编排层确定是否存在可用目标，将整体结果表述为 HIT、MISS、SKIPPED 或 DEGRADED。可用目标与版本选择规则不变；托管检索的异常仍遵循既有整体降级行为。
- 中间件只对明确 MISS 记录一次缺口，SKIPPED 不伪造检索回放，DEGRADED 在既有回放里记 ERROR，并继续使用已经取得的部分内容。每轮缓存、可信身份传递、瞬态隔离与后台线程调度保留。
- 原有二参函数式 SPI、三参文本入口以及 searchAll 的节点入口保持可调用。默认结构化入口保留旧自定义 Provider 的空值语义；会在内部处理失败或跳过的宿主需覆写 retrieveResult。仓库实际生产实现 Admin 已完成覆写。

没有新增表、迁移、接口返回字段或前端操作；也不根据旧排行条目反推历史故障并删除用户数据。此批修正后续事实采集，聊天引用持久化和知识候选流程继续另批完成。

## 定向验证

65 项定向测试全部通过：starter 42 项、Admin 23 项，0 失败、0 错误、0 跳过。覆盖真实 HTTP 503 与 HTTP 200 空响应的区别、部分成功与阈值过滤、超时后迟到结果、保留中断信号、跳过检索、缺口去重、故障回放、版本冻结、可信主体、原有文本接口和隔离注入。

反向验证临时忽略外部结果完整性，3 项专用测试中 2 项断言失败、0 错误：HTTP 503 再次误记未命中，部分召回被误报为完整命中。源文件已按备份逐字恢复；提交前全量后端回归重新 clean 编译。

开发过程中，旧 Mockito 桩未执行新增接口 default 方法导致定向测试失败；已在 starter 使用真实 default 方法核对旧 SPI 兼容，在 Admin 壳测试接起结构化结果。没有以修改断言放宽线程、身份或注入要求。

日志位于 /fyoung/tmp/customer-work-knowledge-retrieval-*.log。修复会修改共享 starter，因此提交前运行整个 Maven 反应堆；Admin/H5 前端未改动且没有协议变化，本地不重复其 UI 套件，远端保持仓库要求的完整 CI。

## 提交前后端回归

整个 Maven 反应堆 clean test 通过，共 4,076 项，0 失败、0 错误、7 项环境条件跳过：starter 1,959、客服应用 177、渠道 82、Admin 1,857、gateway 1。Admin 的 Redis 登录持久化测试未被排除。源码和测试冻结的 13 个 Java 文件在回归前后 SHA-256 全部一致。

完整日志 customer-work-knowledge-retrieval-backend-full.log，模块计数 customer-work-knowledge-retrieval-backend-summary.json。前端及协议均未改变，本地按影响范围没有重复 Admin/H5 的 UI 套件。远端 CI 状态以 GitHub PR 为准。
