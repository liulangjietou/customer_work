# customer-user-mobile · 智能客服用户端 H5

Vue3 + TypeScript + Vite + Vant4，面向终端用户的客服入口，配套后端 `customer-work-app`（8080）。
支持注册登录、机器人流式对话（打字机效果）、一键/关键词转人工、与人工客服实时聊天（WebSocket）、
我的工单（状态时间线、确认解决/驳回/重开/关闭）、历史消息加载。

## 本地开发

```bash
npm install
npm run dev        # 端口 5175；/api 与 /ws 经 vite proxy 转发到 localhost:8080
```

- 需先启动 customer-work-app（8080，依赖本机 MySQL，表启动自动建）。
- 电脑浏览器直接访问即可验证（内置 @vant/touch-emulator 鼠标模拟触摸），页面为 480px 移动端壳。

## 构建与部署

```bash
npm run build      # vue-tsc 类型检查 + vite build，产物 dist/
```

生产用 Nginx 静态托管 dist/ 并反代 `/api`、`/ws`（升级 WebSocket 头）到 8080，示例见
[docs/部署手册.md](../docs/部署手册.md)。接口与 WS 帧协议见 [docs/生产接口使用手册.md](../docs/生产接口使用手册.md)。
