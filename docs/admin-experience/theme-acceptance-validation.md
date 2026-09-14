# 原主题保留与全站图表验收

2026-09-15。承接已批准清单第 4 项。保留 System、Atlas、Ocean、Violet、Ember、Dawn、Night、Aurora、Graphite 的原色、工作面、用户自定义色及选择持久化。

## 调用链与变更

- 主题选择器 → theme store → 运行时 CSS 变量 → WorkspaceView 知识入口。入口图标原来写死为 `#2563eb`，在 Ember、Night、Violet 均先复现不跟随主题；修复仅改用现有 `--theme-primary`，未改预设值。
- HomeOperations/调用统计 → AgentCallTrendChart；评测中心 → EvalTrendChart；内容防护看板 → ContentGuardTrendChart。三个组件已有主题观察与 runtime palette，本次补实际画布和 tooltip 验收，不另建配色系统。
- 调用统计七个指标在 390px 下图例换行，挤压轴标题。改为单行分页图例并给轴标题留出间距，保留全部系列、默认 Token 不选中、原图形符号与线型。分页箭头/文本取当前图表主题，未修改业务数据。

## 证据范围

| 验证 | 覆盖 |
|---|---|
| 预设保留单测与订单浏览器检查 | 九套原始主色和工作面、操作按钮及悬停色、刷新持久化、旧自定义深色恢复 |
| 工作区知识入口 | Ember/Night/Violet 的实际图标强调色 |
| 四个图表宿主 | 每页九套主题、System 明暗切换、自定义浅/深色，实际画布图线与文字像素、tooltip 背景/边界/文字和视口范围 |
| 图表窄屏 | 四页 Ember/Night、390×844，画布重绘、文字/图线、tooltip 可见范围、文档不横向溢出 |
| 视觉复核 | 调用统计 Night、评测 Ember、命中图自定义深色，以及四宿主窄屏截图 |

Canvas 的图线只统计绘图区，避免图例变色却图线未更新的假通过。文字统计纳入抗锯齿边缘。tooltip 用新建且禁用过渡的元素解析期望色，避免同一帧连续改色读到上一次的计算值。首次取色与不透明文字数量失败属于测试测量问题，未据此修改产品颜色。

字面颜色扫描覆盖 Vue 组件；中性深色代码面板与健康概览仍保留。终端正文/背景静态对比度为 13.66，健康卡片次要文字在渐变较亮端为 6.47，主文字为 9.61；它们不是用户主题主色，未因出现十六进制颜色而替换。代码阴影、图片遮罩和验证码图形也未做无依据改色。

## 失败复现与回归

- `customer-theme-workspace-red.log`：3 项失败，均发现实际固定蓝色；`customer-theme-workspace-green.log` 3 项通过。
- `customer-theme-charts-all.log`：四宿主桌面主题绘制通过。`customer-theme-final-scope.log`：原色保留/图表/入口共 17 项通过，含四页窄屏；此时尚未加入调用图例分页修正。
- `customer-chart-legend-red.log` / `customer-chart-legend-gap-red.log`：复核窄屏截图后，组件用例分别捕获图例未分页、轴标题空间不足。修复后组件 6 项及调用图实际浏览器 1 项通过，完整门禁见下。

日志与截图位于 /fyoung/tmp。API 夹具验证真实 Vue/ECharts 行为，不代表 41 页业务写入、真实性能或 H5 真机已验收；这些继续归属清单第 8、9、10 项。

## 最终本地门禁

最终源码基于 `e226880b`，五个产品/测试文件的 SHA-256 在运行前冻结，运行结束后逐项一致。

| 检查 | 结果 | 日志 |
|---|---|---|
| Admin 类型检查与生产构建 | 退出 0 | `customer-theme-complete-admin-build.log` |
| Admin 完整单元测试 | 303 项通过，退出 0 | `customer-theme-complete-admin-unit.log` |
| Admin 完整浏览器回归 | 293 项通过，12.7 分钟，退出 0 | `customer-theme-final-admin-e2e.log` |
| 图表视觉证据 | 四宿主共 56 张最终截图，另建可切换宿主/主题/宽度的验收页 | `customer-work-experience-20260910/theme-review.html` |

本批未修改后端或 H5。父提交的最新完整后端门禁为 4,638 项、0 失败、0 错误、7 项环境条件跳过；H5 108 项单测、23 项浏览器测试及构建通过。父 PR #217 的后端、H5 与部署检查已通过，Admin 远端回归在本记录时仍运行。当前批次的远端 CI 需在推送后单独核对，不以父提交结果替代。

截图来自隔离 API 夹具下运行的真实页面；已复核四宿主 Ember/Night 窄屏、桌面代表主题及验收页自身的 390px 布局。验收页复制保留最终截图，清理本批临时工作树不会删除证据。
