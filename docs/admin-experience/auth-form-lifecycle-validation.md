# 认证表单提交生命周期验证（G8）

## 根因与调用链

现有请求层能忽略旧身份的错误提示及失效响应，但成功结果仍按原契约返回给调用方。Login.vue 在 await 后直接记忆用户名、重置菜单并应用登录结果；ChangePassword.vue 在 await 后直接清空账号。组件退出或新登录进入后，这些旧回调仍可能污染全局登录状态。

真实调用链：登录/改密表单 → auth API → request 拦截器 → 后端 Result → 表单成功分支 → auth/menu/tabs/router。属于页面提交结果的消费权问题；在两种真实表单复用 useAuthSubmissionScope，检查页面生命周期、提交时的登录代次与 token，不修改通用请求的成功返回契约。

在参数校验之后、成功结果写入全局状态之前核验当前提交；登录失败后的验证码复位、异步焦点恢复也绑定相同提交。改密业务拒绝由已有请求层提示，表单捕获异常并保留输入，避免出现未处理的 Vue 事件异常。

## 失败证据

- `/fyoung/tmp/customer-auth-form-valid-red.log`：3 项稳定失败。旧改密响应将新 token 清空；本地登录和 OA 登录旧响应将新 token 替换为旧结果。
- `/fyoung/tmp/customer-auth-form-failure-red.log`：当前改密成功对照通过；业务拒绝后出现未处理异常，严格浏览器夹具失败。
- 初次登录复现测试的 checkbox 隐藏输入定位超时已作为夹具问题修正，不能用作产品失败证据。

## 当前结果

- 5 项首轮修复验证通过。
- 扩展 8 项表单行为与 15 项既有登录初始化隔离测试，共 23 项通过。
- 同 token 重登且表单未卸载也保留新身份，证明隔离不只依赖 token 不同或页面退出；当前成功和业务拒绝作为正向对照。
- 最终完整门禁已通过：后端 4,443 项，失败/错误均为 0，7 项外部服务用例跳过；Admin 303 项单测、264 项浏览器测试；H5 108 项单测、23 项浏览器测试；两端类型检查与构建通过。
- 7 项跳过分别依赖 Bailian（1）、Nacos（4）、PaddleOCR（1）和真实 RAG 服务（1），未计入已验证功能。
- 最终门禁使用两份独立临时数据库，结束后均清理；4 份产品源码/测试 SHA-256 在门禁前后及提交前保持一致。
- 最终日志：`/fyoung/tmp/customer-auth-form-final-all-gates.log`、各阶段 `customer-auth-form-final-*-full.log`；后端汇总 `customer-auth-form-final-backend-summary.json`；冻结清单 `customer-auth-form-final-frozen.json`。
- 本批基于 G7 `22571429`，交付为独立 PR；远端 CI 尚待提交后验证。
