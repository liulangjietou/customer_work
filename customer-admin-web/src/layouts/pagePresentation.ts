export interface PagePresentation {
  eyebrow: string
  title: string
  description: string
}

export type PageTemplate = 'list' | 'dashboard' | 'console'

/**
 * 路由级页面文案只回答“这里负责什么”，不重复按钮或表格字段。
 * 动态菜单仍以服务端下发的名称为准；这里提供稳定的视觉语境与未知路由兜底。
 */
export const PAGE_PRESENTATIONS: Readonly<Record<string, PagePresentation>> = {
  '/system/user': { eyebrow: 'IDENTITY & ACCESS', title: '用户管理', description: '管理平台身份、角色归属、账号状态与最近活动。' },
  '/system/role': { eyebrow: 'IDENTITY & ACCESS', title: '角色管理', description: '审查角色权限边界、数据范围及其影响成员。' },
  '/system/log': { eyebrow: 'AUDIT EVIDENCE', title: '操作日志', description: '按主体、动作、对象和结果还原真实操作轨迹。' },
  '/system/ai-audit': { eyebrow: 'AUDIT EVIDENCE', title: 'AI 审计', description: '串联输入、模型、工具、输出与业务结果的完整证据。' },
  '/system/agent-call-stats': { eyebrow: 'RUNTIME EVIDENCE', title: '调用统计', description: '在调用量、成功率、延迟与成本之间建立可行动关联。' },
  '/system/menu': { eyebrow: 'SYSTEM STRUCTURE', title: '菜单管理', description: '维护平台信息架构、访问入口与菜单发布状态。' },
  '/system/devtools': { eyebrow: 'MY WORKBENCH', title: '开发者工具箱', description: '在本地完成常用编码、签名、时间与文本处理任务。' },
  '/system/login-image': { eyebrow: 'BRAND SETTINGS', title: '登录页图片', description: '管理登录场景视觉资产及桌面、移动端裁切效果。' },
  '/system/dict': { eyebrow: 'SYSTEM CONTRACTS', title: '字典管理', description: '维护跨功能复用的稳定枚举、排序与启停状态。' },
  '/system/tenant': { eyebrow: 'TENANT OPERATIONS', title: '租户管理', description: '管理租户生命周期、套餐、成员配额与到期风险。' },
  '/system/billing': { eyebrow: 'COST GOVERNANCE', title: '计费中心', description: '连接预算、实际消耗、趋势预测与成本归属。' },
  '/system/config-version': { eyebrow: 'CHANGE GOVERNANCE', title: '配置版本', description: '在比较差异和影响范围后发布或回滚配置。' },
  '/system/slo': { eyebrow: 'RELIABILITY GOVERNANCE', title: 'SLO 管理', description: '用服务目标和错误预算驱动可靠性决策。' },
  '/aiconfig/model': { eyebrow: 'MODEL ASSETS', title: '模型管理', description: '管理模型资产、路由策略、能力验证与生产认证。' },
  '/aiconfig/mcp': { eyebrow: 'CAPABILITY ASSETS', title: 'MCP 管理', description: '登记、测试并治理智能体可调用的 MCP 服务。' },
  '/aiconfig/skill': { eyebrow: 'CAPABILITY ASSETS', title: 'Skill 管理', description: '沉淀可复用技能包，并明确版本、依赖与可见范围。' },
  '/aiconfig/agent': { eyebrow: 'AGENT ASSETS', title: '智能体管理', description: '从能力装配到发布状态，管理可运行的智能体资产。' },
  '/aiconfig/system-tool': { eyebrow: 'CAPABILITY ASSETS', title: '系统工具', description: '管理平台内置工具的参数、安全边界与启用范围。' },
  '/aiconfig/knowledge-base': { eyebrow: 'KNOWLEDGE ASSETS', title: '知识库', description: '管理知识来源、同步进度与召回健康度。' },
  '/aiconfig/scheduled-task': { eyebrow: 'AUTOMATION', title: '定时任务', description: '管理任务计划、下次运行、最近结果和失败责任。' },
  '/aiconfig/agent-task': { eyebrow: 'AGENT RUNS', title: '智能体任务', description: '用任务状态、耗时和执行证据定位运行异常。' },
  '/aiconfig/channel-robot': { eyebrow: 'CHANNEL DELIVERY', title: '渠道机器人', description: '连接渠道身份、智能体版本与投放健康度。' },
  '/project': { eyebrow: 'DELIVERY PROJECTS', title: '项目管理', description: '组织项目目标、环境、成员与最近交付进展。' },
  '/sql/datasource': { eyebrow: 'DATA ASSETS', title: '数据源', description: '管理连接可用性、授权范围与最近探测结果。' },
  '/sql/define': { eyebrow: 'DATA CONTRACTS', title: 'SQL 定义', description: '以参数契约、权限范围与验证样例管理可复用查询。' },
  '/sql/query': { eyebrow: 'DATA EXECUTION', title: 'SQL 查询', description: '按已批准契约输入参数，并保留执行边界和结果证据。' },
  '/ops/eval': { eyebrow: 'QUALITY OPERATIONS', title: '评测中心', description: '从总体质量下钻到数据集、评测维度与失败样本。' },
  '/ops/badcase': { eyebrow: 'QUALITY OPERATIONS', title: 'Badcase 管理', description: '将失败样本变成有负责人、有结论、有回归结果的闭环。' },
  '/ops/semantic-cache': { eyebrow: 'RUNTIME OPERATIONS', title: '语义缓存', description: '判断缓存是否真正降低成本，同时守住答案时效。' },
  '/ops/prompt-version': { eyebrow: 'QUALITY OPERATIONS', title: 'Prompt 版本', description: '比较运行时快照、全文差异与变更证据。' },
  '/ops/csat': { eyebrow: 'EXPERIENCE OPERATIONS', title: '满意度分析', description: '按时间窗口和评价明细定位满意度波动。' },
  '/ops/knowledge-gap': { eyebrow: 'KNOWLEDGE OPERATIONS', title: '知识缺口', description: '把未命中问题聚类为知识补充与验证任务。' },
  '/ops/dead-letter': { eyebrow: 'RUNTIME OPERATIONS', title: '死信处理', description: '呈现失败原因、影响范围、重试条件与处理记录。' },
  '/ops/business-outcome': { eyebrow: 'OUTCOME OPERATIONS', title: '业务结果', description: '聚焦自动解决代理、转人工、满意度与成本证据。' },
  '/ticket/user-ticket': { eyebrow: 'SERVICE OPERATIONS', title: '客服工单', description: '集中处理工单状态、服务问题与完整沟通轨迹。' },
  '/ticket/user-order': { eyebrow: 'SERVICE OPERATIONS', title: '用户订单', description: '按用户、订单状态与服务节点定位履约问题。' },
  '/contentguard/sensitive-word': { eyebrow: 'CONTENT GOVERNANCE', title: '敏感词库', description: '按风险级别、适用范围与版本管理内容规则。' },
  '/contentguard/rate-limit': { eyebrow: 'RUNTIME GOVERNANCE', title: '限流规则', description: '明确保护主体、阈值窗口与触发后的处置动作。' },
  '/contentguard/hit-log': { eyebrow: 'CONTENT GOVERNANCE', title: '命中看板', description: '识别风险趋势，并下钻到归并后的命中证据。' },
  '/contentguard/subject-quota': { eyebrow: 'CAPACITY GOVERNANCE', title: '主体配额', description: '连接配额消耗、耗尽预测与容量调整责任。' },
  '/workbench/site': { eyebrow: 'MY WORKBENCH', title: '内网工作台', description: '集中访问已授权站点，并保留最近工作上下文。' },
  '/workbench/sql-console': { eyebrow: 'MY WORKBENCH', title: 'SQL 客户端', description: '以键盘友好的库表树、编辑器和结果区诊断数据。' },
}

/**
 * 五类页面母版中的三类业务内容页。首页、工作区与认证页由各自组件接管布局，
 * 因此不在这里重复登记。显式映射让新增路由必须先决定信息层级，避免悄悄退回卡片堆叠。
 */
export const PAGE_TEMPLATE_BY_PATH: Readonly<Record<string, PageTemplate>> = {
  '/system/user': 'list',
  '/system/role': 'list',
  '/system/log': 'list',
  '/system/ai-audit': 'list',
  '/system/login-image': 'list',
  '/system/tenant': 'list',
  '/aiconfig/mcp': 'list',
  '/aiconfig/skill': 'list',
  '/aiconfig/agent': 'list',
  '/aiconfig/system-tool': 'list',
  '/aiconfig/knowledge-base': 'list',
  '/aiconfig/scheduled-task': 'list',
  '/aiconfig/agent-task': 'list',
  '/aiconfig/channel-robot': 'list',
  '/contentguard/sensitive-word': 'list',
  '/contentguard/rate-limit': 'list',
  '/ticket/user-ticket': 'list',
  '/ticket/user-order': 'list',
  '/project': 'list',
  '/sql/datasource': 'list',
  '/workbench/site': 'list',

  '/system/agent-call-stats': 'dashboard',
  '/system/slo': 'dashboard',
  '/ops/eval': 'dashboard',
  '/ops/badcase': 'dashboard',
  '/ops/semantic-cache': 'dashboard',
  '/ops/prompt-version': 'dashboard',
  '/ops/csat': 'dashboard',
  '/ops/knowledge-gap': 'dashboard',
  '/ops/dead-letter': 'dashboard',
  '/ops/business-outcome': 'dashboard',
  '/contentguard/hit-log': 'dashboard',

  '/system/menu': 'console',
  '/system/devtools': 'console',
  '/system/dict': 'console',
  '/system/billing': 'console',
  '/system/config-version': 'console',
  '/aiconfig/model': 'console',
  '/contentguard/subject-quota': 'console',
  '/sql/define': 'console',
  '/sql/query': 'console',
  '/workbench/sql-console': 'console',
}

const DEFAULT_PRESENTATION: PagePresentation = {
  eyebrow: 'CUSTOMER WORK',
  title: '工作页面',
  description: '在当前租户和权限范围内完成这项工作。',
}

export function resolvePagePresentation(path: string): PagePresentation {
  return PAGE_PRESENTATIONS[path] ?? DEFAULT_PRESENTATION
}

export function resolvePageTemplate(path: string): PageTemplate | undefined {
  return PAGE_TEMPLATE_BY_PATH[path]
}
