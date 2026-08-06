import type { Component } from 'vue'

export interface DevTool {
  key: string
  label: string
  description: string
  component: () => Promise<Component>
}

/**
 * 开发者工具箱的工具清单，供左侧导航渲染与搜索过滤，以及右侧按 key 动态加载对应组件。
 * 第一项是默认工具（route query 缺失/非法时兜底展示）。
 *
 * 工具分两类实现：
 * - 早期几项（JSON/时间戳/编解码/AES/正则）在浏览器本地算；其中 AES 是刻意保留的——密钥不出浏览器。
 * - HTTP 代理、证书解析，以及 cron/JWT/文本比对/格式互转，一律走后端接口：算法只在 starter 的 Ops
 *   里实现一份，与智能体的 devtoolbox 系统工具共用，从根上避免两端语义分叉（前端各实现一套正是
 *   早期留下的坑，AES 两侧模式集合曾一度互不包含）。
 */
export const devTools: DevTool[] = [
  {
    key: 'json',
    label: 'JSON 工具',
    description: '格式化/压缩/转义/去转义/Unicode 转中文',
    component: () => import('./JsonTool.vue'),
  },
  {
    key: 'timestamp',
    label: '时间戳转换',
    description: '时间戳与日期时间互转，多时区对照',
    component: () => import('./TimestampTool.vue'),
  },
  {
    key: 'codec',
    label: '编解码 / 哈希',
    description: 'Base64、URL、Hex、MD5/SHA/HMAC、UUID 生成',
    component: () => import('./EncodeHashTool.vue'),
  },
  {
    key: 'aes',
    label: 'AES 加解密',
    description: 'CBC/ECB/CTR，PKCS7/NoPadding',
    component: () => import('./AesTool.vue'),
  },
  {
    key: 'regex',
    label: '正则测试',
    description: '匹配高亮、捕获组列表、替换预览',
    component: () => import('./RegexTool.vue'),
  },
  {
    key: 'http',
    label: 'HTTP 请求',
    description: 'GET/POST/PUT/DELETE/PATCH 等，后端代理发送免 CORS',
    component: () => import('./HttpRequestTool.vue'),
  },
  {
    key: 'cert',
    label: '证书解析',
    description: 'X.509 证书/证书链、CSR、私钥匹配校验、PFX/JKS 密钥库',
    component: () => import('./CertTool.vue'),
  },
  {
    key: 'cron',
    label: 'cron 解析',
    description: '逐字段释义、推算后续执行时间，与 XXL-JOB 同语义',
    component: () => import('./CronTool.vue'),
  },
  {
    key: 'jwt',
    label: 'JWT 解析',
    description: '解码 header/payload、过期判断、HS* 签名校验',
    component: () => import('./JwtTool.vue'),
  },
  {
    key: 'diff',
    label: '文本比对',
    description: '行级差异高亮，可忽略空白与大小写',
    component: () => import('./DiffTool.vue'),
  },
  {
    key: 'convert',
    label: '格式互转',
    description: 'JSON、YAML、XML 三者任意方向转换',
    component: () => import('./DataConvertTool.vue'),
  },
]

export const defaultToolKey = devTools[0].key
