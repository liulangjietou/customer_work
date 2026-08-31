import { describe, expect, it } from 'vitest'
import { staticRouteComponents } from '@/router/component-map'
import {
  PAGE_PRESENTATIONS,
  PAGE_TEMPLATE_BY_PATH,
  resolvePagePresentation,
  resolvePageTemplate,
} from './pagePresentation'

describe('pagePresentation', () => {
  it('覆盖全部静态业务路由，避免新页面回退成无语境标题', () => {
    expect(Object.keys(staticRouteComponents).every((path) => PAGE_PRESENTATIONS[path])).toBe(true)
    expect(Object.keys(PAGE_PRESENTATIONS).sort()).toEqual([
      ...Object.keys(staticRouteComponents),
      '/sql/query',
    ].sort())
  })

  it('SQL 报表按 path 复用展示语境，不依赖 defineKey', () => {
    expect(resolvePagePresentation('/sql/query')).toMatchObject({
      eyebrow: 'DATA EXECUTION',
      title: 'SQL 查询',
    })
  })

  it('全部业务页面明确归入三类内容母版', () => {
    expect(Object.keys(PAGE_TEMPLATE_BY_PATH).sort()).toEqual(Object.keys(PAGE_PRESENTATIONS).sort())
    expect(Object.values(PAGE_TEMPLATE_BY_PATH).filter((item) => item === 'list')).toHaveLength(21)
    expect(Object.values(PAGE_TEMPLATE_BY_PATH).filter((item) => item === 'dashboard')).toHaveLength(11)
    expect(Object.values(PAGE_TEMPLATE_BY_PATH).filter((item) => item === 'console')).toHaveLength(10)
    expect(resolvePageTemplate('/aiconfig/model')).toBe('console')
    expect(resolvePageTemplate('/home')).toBeUndefined()
  })

  it('未知动态页面安全回退，不暴露内部路径', () => {
    expect(resolvePagePresentation('/future/custom')).toEqual({
      eyebrow: 'CUSTOMER WORK',
      title: '工作页面',
      description: '在当前租户和权限范围内完成这项工作。',
    })
  })
})
