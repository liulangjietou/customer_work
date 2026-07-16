# customer-admin-web Element Plus 改造方案

> 状态：待评审（未动代码）。评审通过后按"实施顺序"分 PR 落地。
> 前提结论：项目**已深度基于 Element Plus 2.14.2**（39 个 .vue、约 900+ 个 el-* 组件实例），
> 本方案不是"引入 EP"，而是三件事：**A 按需引入优化体积、B 视觉升级+暗色模式、C CRUD 模式收敛**。
> 版本升级无收益（2.14.2 已接近最新），不在本方案范围内。

## 一、现状盘点（2026-07-16 调研）

| 项 | 现状 | 对方案的影响 |
|---|---|---|
| 引入方式 | `main.ts` 全量 `app.use(ElementPlus)` + 全量 CSS + `for...of` 全局注册全部图标 | 方向 A 的改造对象 |
| 函数式 API | `ElMessage`/`ElMessageBox`/`ElLoading` 共 145 处、分布在 24 个文件，均为**显式 import** | 方向 A 最大的坑（见 4.2） |
| 动态图标 | `MenuTree.vue`、`MenuManage.vue`、`IconPicker.vue` 用 `<component :is="字符串">` 按名渲染，依赖全局注册 | 图标**不能**按需引入（见 4.3） |
| 主题机制 | `store/theme.ts` 运行时覆盖 `--el-color-primary*` CSS 变量换肤，8 色盘；提亮算法为"向白色混合" | 方向 B 必须沿 CSS 变量路线；混白算法与暗色模式冲突（见 5.2） |
| 布局 | `MainLayout.vue` 侧边栏 `el-menu` 用 props 写死 `background-color="#001529"` 等深色值 | 暗色改造点（见 5.3） |
| 代码高亮 | `main.ts` 固定引 `highlight.js/styles/github.css`（亮色） | 暗色下代码块刺眼（见 5.4） |
| 样式覆盖 | `:deep()` 28 处，其中 13 处直指 `.el-*` 内部类名 | 视觉升级/暗色的脆弱点，逐个核对 |
| CRUD 页面 | 14 个管理页同构：搜索栏 + el-table + el-pagination + 新增/编辑 el-dialog（ModelManage.vue 196 行为标准样本） | 方向 C 的收敛对象 |
| 错误处理约定 | `api/request.ts` 拦截器统一拆箱 `Result<T>`、统一 `ElMessage.error`，页面层不写 try/catch，靠 Promise reject 中断流程 | 方向 C 的封装必须保持该约定，不引入第二层防御 |
| 构建 | `vue-tsc -b && vite build`（带类型检查） | 方向 A 生成的 d.ts 必须过 vue-tsc |
| 测试 | 前端无自动化测试 | 回归只能靠页面级验证（见 7） |

## 二、目标与非目标

**目标**
- A：构建产物体积明显下降（以 `vite build` 后 dist 体积前后对比为准），运行行为零变化。
- B：全站视觉统一升级 + 暗色模式（light / dark / 跟随系统），与现有 8 色盘换肤共存。
- C：14 个管理页的 CRUD 样板代码收敛进一个 composable，新页面开发成本减半，行为不变。

**非目标（明确不做）**
- 不升级 Element Plus / Vue / Vite 版本。
- 不做 avue / pro-components 式的"列配置化 ProTable"大而全封装（理由见 6.1）。
- 不改 API 层协议、路由结构、权限模型（`v-permission` 指令原样保留）。
- 不动 workspace（ChatPanel/VibeCoding）等非 CRUD 页面的内部逻辑。

## 三、实施顺序与 PR 切分

依赖关系决定顺序：**A → C → B**。

1. **PR-1（方向 A）**：改动集中在 `main.ts`/`vite.config.ts`/存量 import 清理，与页面逻辑正交，先做、快速验证。
2. **PR-2~4（方向 C）**：先抽 `useCrudPage` 并只迁移 2 个试点页（ModelManage + SystemToolManage），验证抽象成立后再分批迁移其余页面。页面骨架类名在此阶段统一。
3. **PR-5~6（方向 B）**:视觉升级建立在 C 统一后的骨架上，改一处全站生效；暗色模式最后做（依赖硬编码色值清理完成）。

每个 PR 附"页面验证清单"（见 7）通过截图/记录。

## 四、方向 A：按需引入

### 4.1 方案

引入 `unplugin-auto-import` + `unplugin-vue-components` + `ElementPlusResolver`：

```ts
// vite.config.ts
AutoImport({ resolvers: [ElementPlusResolver()], dts: 'src/types/auto-imports.d.ts' }),
Components({ resolvers: [ElementPlusResolver()], dts: 'src/types/components.d.ts' }),
```

移除 `main.ts` 中的 `app.use(ElementPlus)` 与 `import 'element-plus/dist/index.css'`。

### 4.2 坑一：145 处显式 import 的函数式 API

`ElementPlusResolver` 只对**未 import 的标识符**生效。存量 24 个文件里 `import { ElMessage } from 'element-plus'` 是显式 import——保留它们则样式不会被按需带出，`ElMessage` 弹出裸 DOM。处理：

- 统一删除存量的 `import { ElMessage, ElMessageBox, ... } from 'element-plus'`（**类型 import 保留**，如 `type FormInstance`，类型不影响样式且 auto-import 不管类型）。
- 交给 auto-import resolver 解析，它会同时注入对应组件样式。
- 约定写入本文档 + 代码评审关注：后续新代码不再手写 EP 值 import。

### 4.3 坑二：动态字符串图标必须保留全局注册

`MenuTree.vue:36/47`、`MenuManage.vue:236/277/292`、`IconPicker.vue` 全部用 `<component :is="node.icon">` 按**运行时字符串**渲染图标，静态分析无法覆盖。处理：

- **图标维持全量全局注册不变**（把 main.ts 的注册循环抽到 `src/plugins/icons.ts`，语义化）。
- `@element-plus/icons-vue` 全量体积可接受（纯 SVG 组件，tree-shake 前也仅几十 KB gzip），不值得为它引入"图标白名单"这种维护负担。
- `IconsResolver` 之类的图标按需方案**不采用**——与动态场景冲突。

### 4.4 坑三：vue-tsc 与生成的 d.ts

`auto-imports.d.ts` / `components.d.ts` 必须落在 tsconfig include 范围内且**提交进仓库**（CI/他人首次 clone 后 `vue-tsc -b` 才能过）。首次生成后跑一遍 `npm run build` 验证类型检查。

### 4.5 验收标准

- `npm run build` 通过；dist 体积对比记录进 PR 描述。
- 重点回归：任意页面的 `ElMessage`（成功/失败提示）、`ElMessageBox.confirm`（删除确认）弹出**有完整样式**；登录失效跳转提示（request.ts 里的 ElMessage 在 .ts 文件中，确认 auto-import 对 ts 生效）；菜单树图标、菜单管理的图标选择器正常渲染。

## 五、方向 B：视觉升级 + 暗色模式

### 5.1 总原则：只走运行时 CSS 变量，禁用 SCSS 编译期定制

现有换肤是运行时覆盖 `--el-color-primary*`。SCSS 变量编译期定制（`@use "element-plus/theme-chalk/src/index.scss" with (...)`）会与之打架且把换肤功能干掉——**明确禁止**。所有视觉定制通过：
- 覆盖 EP 的 CSS 变量（`--el-border-radius-base`、`--el-font-size-base`、`--el-bg-color` 等）；
- 一个全局 `theme.css` 收敛设计 token（间距、圆角、阴影、页面背景），替代散落的硬编码值。

### 5.2 暗色模式与现有换肤算法的冲突

上暗色的标准做法：`import 'element-plus/theme-chalk/dark/css-vars.css'` + `<html class="dark">`。冲突点：

`theme.ts.apply()` 计算 `--el-color-primary-light-3~9` 用的是"向白混合"。EP 暗色语义下 light-9 是**最接近背景**的色阶（应向黑混），直接套用会导致暗色下按钮 hover/禁用态/浅底标签发白刺眼。改造：

```
theme store 扩展 state: { primaryColor, mode: 'light' | 'dark' | 'auto' }
apply() 感知实际暗色状态：dark 时 light-N 改为向黑混合（darken），dark-2 改为向白混合
mode 持久化 localStorage；'auto' 监听 prefers-color-scheme 变化
```

`ThemeToolbar.vue` 增加明暗切换入口（图标按钮即可）。

### 5.3 硬编码色值清理

暗色模式的前置工作，逐处清理为 EP 语义变量：
- `MainLayout.vue`：el-menu 的 `background-color="#001529"` 等 **props 传色改为 CSS 变量方案**（EP 官方已推荐用 `--el-menu-bg-color` 等变量替代这三个 props）；侧边栏深色本身可保留为"品牌侧边栏"设计（很多后台暗侧边栏在明暗两态下不变），这样改动最小。
- `IconPicker.vue` 的 `#f0f2f5`/`#ecf5ff`/`#409eff` → `var(--el-fill-color-light)`/`var(--el-color-primary-light-9)`/`var(--el-color-primary)`。
- 全局 grep `#[0-9a-fA-F]{3,6}` 建立清理清单，PR 中逐项核销；13 处 `:deep(.el-*)` 在暗色下逐个人工核对。

### 5.4 highlight.js 主题跟随

`main.ts` 固定引 `github.css`。改为两套主题按 `html.dark` 切换（两个 css 都引入、用 `html.dark .hljs` 作用域包裹 dark 版；或运行时切 `<link>`）。MarkdownRenderer 输出的代码块是主要受益点（工单聊天、workspace 会话）。

### 5.5 视觉升级的度

克制：调 token（圆角 4→6、统一卡片阴影、页面背景用主题色极浅底、表格斑马纹/hover 态、统一 `el-dialog` 圆角与间距），**不重写任何组件结构**。所有调整落在 `theme.css` 一个文件，随时可整体回滚。

## 六、方向 C：CRUD 模式收敛

### 6.1 设计取舍：抽"逻辑"不抽"模板"

两条路线对比后选**composable 路线**：

- ~~列配置化 ProTable~~：14 个页面的表格列大量使用 slot（el-tag 状态映射、操作列按钮组、`v-permission` 指令、行内 loading），配置化后 slot/权限/自定义渲染会变成 render 函数地狱，可读性不升反降，且属于从别人的框架"搬进来"的抽象，不是从本项目长出来的。**不做**。
- **`useCrudPage` composable**：只收敛行为逻辑（分页加载、搜索重置、新建/编辑弹窗状态机、提交校验、删除确认），模板保持显式的 el-table/el-form——列和表单是每页的**本质差异**，留在模板里才可读。

### 6.2 接口草图

```ts
// src/composables/useCrudPage.ts
interface CrudOptions<VO, Q extends PageQuery, F> {
  page: (q: Q) => Promise<PageResult<VO>>
  create?: (f: F) => Promise<unknown>
  update?: (id: number, f: F) => Promise<unknown>
  remove?: (row: VO) => Promise<unknown>
  initQuery: () => Q
  initForm: () => F
  toForm: (row: VO) => F            // 编辑回填（如 apiKey 置空的差异逻辑在此表达）
  rowKey?: (row: VO) => number      // 默认取 row.id
  deleteConfirm?: (row: VO) => string
  messages?: { created?: string; updated?: string; deleted?: string }  // 默认 新建/保存/删除成功
}

function useCrudPage<VO, Q extends PageQuery, F>(options: CrudOptions<VO, Q, F>): {
  loading, list, total, query,                    // 列表态
  dialogVisible, dialogMode, form, formRef,       // 弹窗态
  loadList, handleSearch,                          // 行为
  openCreate, openEdit, handleSubmit, handleDelete,
}
```

约束（与现有链路对齐，防止封装走样）：
- **不包 try/catch 做错误提示**——`request.ts` 拦截器已统一 `ElMessage.error`，composable 只需 `finally` 收 loading，保持全链路单点防御。
- `handleSubmit` 内做 `formRef.validate()` + create/update 分派 + 成功提示 + 关弹窗 + 刷新，与现有页面行为逐字对齐。
- 页面特有动作（测试连通性、启停、审批等）**不进 composable**，留在页面里调 `loadList` 刷新。

### 6.3 迁移批次

| 批次 | 页面 | 说明 |
|---|---|---|
| 试点（PR-2） | ModelManage、SystemToolManage | 一个标准样本 + 一个最简样本，验证抽象 |
| 第二批（PR-3） | SkillManage、McpManage、AgentManage、ScheduledTaskManage、UserManage、RoleManage | 标准 CRUD |
| 第三批（PR-4） | ProjectManage、SqlDatasourceManage、SqlDefineManage、UserTicketManage、UserOrderManage、AiCodingAudit | 含部分只读/特殊动作页，只接列表半套（loadList/query） |
| 不迁移 | MenuManage（树形无分页）、OperationLog（纯只读 70 行）、workspace/*、login/* | 结构不同或无收益，硬套反而增加复杂度 |

试点后若发现抽象不成立（差异逻辑 > 公共逻辑），**停下来回到本文档修订**，不带病铺开。

## 七、回归验证方案（三个方向共用）

前端无测试，"不影响功能"靠以下兜底：

1. **页面清单全过**：每个 PR 合并前，浏览器遍历全部菜单页，逐页确认「页面可打开、列表能加载、搜索/分页可用、新建-编辑-删除弹窗全流程可提交、消息提示有样式」。清单 = 路由表全部 19 个页面。
2. **构建门禁**：`npm run build`（vue-tsc 类型检查是这个项目唯一的静态防线，必须绿）。
3. **小步提交**：按第三节切 PR，单个 PR 出问题可独立 revert。
4. 验证使用独立预览端口（launch.json 的 customer-admin-web-verify / 5175），**不动用户 IDE 自启的 5174 进程**。

## 八、风险清单

| 风险 | 等级 | 缓解 |
|---|---|---|
| 按需引入后 ElMessage/MessageBox 裸样式 | 高（必踩） | 4.2 统一清理显式 import + 弹窗样式逐页回归 |
| 动态图标渲染失效 | 高 | 4.3 图标保持全局注册，明确不做图标按需 |
| 暗色下换肤色阶反向（发白刺眼） | 中 | 5.2 apply() 感知暗色换算法 |
| `:deep(.el-*)` 覆盖在新视觉/暗色下错位 | 中 | 13 处清单化逐个核对 |
| useCrudPage 抽象不贴合导致页面变难读 | 中 | 6.3 试点两页先行，不成立即止损 |
| auto-import d.ts 未提交导致他人构建失败 | 低 | 4.4 d.ts 进仓库 |
