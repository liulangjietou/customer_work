# 效果评估与回归（Eval）

AI 应用的"测试"从确定性的代码测试，演变为非确定性的**效果评估**。本文给出本项目的评估约定。

## 1. 提示词版本化

- 系统提示词不写死在代码里发布：通过 **Nacos 配置中心**（`customer-work.nacos`）托管，
  以 dataId 维护版本；改动有记录、可回滚、可灰度。
- 每次提示词变更，应跑一遍下方评测集，确认关键意图/话术不回退。

## 2. 评测集

在 `docs/eval-set.jsonl`（自建）维护小而精的用例，每行一条：
```json
{"input": "这个订单 20260613001 我要退款", "expectIntent": "refund"}
{"input": "能开发票吗", "expectIntent": "consult"}
{"input": "我要投诉，态度太差了", "expectIntent": "complaint"}
```

## 3. 运行评测

评测需调用真实模型（消耗额度），因此作为**条件执行**，不随 `mvn test` 默认运行：

```bash
export RUN_BAILIAN_IT=true
export DASHSCOPE_API_KEY=sk-xxx
# 复用结构化意图接口 POST /api/customer/intent 逐条比对 expectIntent，统计准确率
```

建议指标：意图准确率、关键工具调用率、平均时延、平均 token 成本。准确率低于阈值（如 90%）即视为回退。

## 4. 数据飞轮（进阶）

把全链路数据（输入 / Prompt / 输出 / 时延 / token）经 `ObservabilityHook` 上报，
用 RM Gallery 设计奖励函数评估、筛高质量数据，交 Trinity-RFT 做强化学习迭代——
这部分需训练平台，属扩展点（见 README 第十节）。
