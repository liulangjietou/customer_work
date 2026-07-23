import type { Component } from 'vue'

export interface DevTool {
  key: string
  label: string
  description: string
  component: () => Promise<Component>
}

/**
 * 开发者工具箱的工具清单，供左侧导航渲染与搜索过滤，以及右侧按 key 动态加载对应组件。
 * 除 HTTP 请求工具经后端代理发送外，其余工具全部本地计算不调后端接口。
 * 第一项是默认工具（route query 缺失/非法时兜底展示）。
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
]

export const defaultToolKey = devTools[0].key
