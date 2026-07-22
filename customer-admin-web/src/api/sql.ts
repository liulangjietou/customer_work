import { download, request } from './request'
import type {
  PageQuery,
  PageResult,
  SqlDatasourceSaveRequest,
  SqlDatasourceVO,
  SqlDefineParamSaveRequest,
  SqlDefineParamVO,
  SqlDefineSaveRequest,
  SqlDefineVO,
  SqlAdhocQueryRequest,
  SqlFieldTransformSaveRequest,
  SqlFieldTransformVO,
  SqlQueryExecuteRequest,
  SqlQueryMetaVO,
  SqlQueryResultVO,
} from '@/types/api'

// ---------- 数据源管理 ----------
export function pageSqlDatasources(query: PageQuery) {
  return request<PageResult<SqlDatasourceVO>>({ url: '/sql/datasource', method: 'get', params: query })
}

/** 启用中的数据源全量列表，供 SQL 定义表单下拉选择用。 */
export function listAllSqlDatasources() {
  return request<SqlDatasourceVO[]>({ url: '/sql/datasource/all', method: 'get' })
}

export function getSqlDatasource(id: number) {
  return request<SqlDatasourceVO>({ url: `/sql/datasource/${id}`, method: 'get' })
}

export function createSqlDatasource(data: SqlDatasourceSaveRequest) {
  return request<void>({ url: '/sql/datasource', method: 'post', data })
}

export function updateSqlDatasource(id: number, data: SqlDatasourceSaveRequest) {
  return request<void>({ url: `/sql/datasource/${id}`, method: 'put', data })
}

export function deleteSqlDatasource(id: number) {
  return request<void>({ url: `/sql/datasource/${id}`, method: 'delete' })
}

export function testSqlDatasourceConnection(id: number) {
  return request<void>({ url: `/sql/datasource/${id}/test`, method: 'post' })
}

// ---------- SQL 定义管理 ----------
export function pageSqlDefines(query: PageQuery) {
  return request<PageResult<SqlDefineVO>>({ url: '/sql/define', method: 'get', params: query })
}

export function getSqlDefine(id: number) {
  return request<SqlDefineVO>({ url: `/sql/define/${id}`, method: 'get' })
}

export function createSqlDefine(data: SqlDefineSaveRequest) {
  return request<void>({ url: '/sql/define', method: 'post', data })
}

export function updateSqlDefine(id: number, data: SqlDefineSaveRequest) {
  return request<void>({ url: `/sql/define/${id}`, method: 'put', data })
}

export function deleteSqlDefine(id: number) {
  return request<void>({ url: `/sql/define/${id}`, method: 'delete' })
}

/** 复制 SQL 定义（含参数、列转换器），后端自动给 defineKey 加后缀。 */
export function copySqlDefine(id: number) {
  return request<void>({ url: `/sql/define/${id}/copy`, method: 'post' })
}

// ---------- 参数元数据（SQL 定义的子资源） ----------
export function listSqlDefineParams(defineId: number) {
  return request<SqlDefineParamVO[]>({ url: `/sql/define/${defineId}/params`, method: 'get' })
}

export function createSqlDefineParam(defineId: number, data: SqlDefineParamSaveRequest) {
  return request<void>({ url: `/sql/define/${defineId}/params`, method: 'post', data })
}

export function updateSqlDefineParam(defineId: number, paramId: number, data: SqlDefineParamSaveRequest) {
  return request<void>({ url: `/sql/define/${defineId}/params/${paramId}`, method: 'put', data })
}

export function deleteSqlDefineParam(defineId: number, paramId: number) {
  return request<void>({ url: `/sql/define/${defineId}/params/${paramId}`, method: 'delete' })
}

// ---------- 列转换器（SQL 定义的子资源） ----------
export function listSqlFieldTransforms(defineId: number) {
  return request<SqlFieldTransformVO[]>({ url: `/sql/define/${defineId}/transforms`, method: 'get' })
}

export function createSqlFieldTransform(defineId: number, data: SqlFieldTransformSaveRequest) {
  return request<void>({ url: `/sql/define/${defineId}/transforms`, method: 'post', data })
}

export function updateSqlFieldTransform(defineId: number, transformId: number, data: SqlFieldTransformSaveRequest) {
  return request<void>({ url: `/sql/define/${defineId}/transforms/${transformId}`, method: 'put', data })
}

export function deleteSqlFieldTransform(defineId: number, transformId: number) {
  return request<void>({ url: `/sql/define/${defineId}/transforms/${transformId}`, method: 'delete' })
}

// ---------- 通用查询页 ----------
export function fetchSqlQueryMeta(defineKey: string) {
  return request<SqlQueryMetaVO>({ url: '/sql/query/meta', method: 'get', params: { defineKey } })
}

export function executeSqlQuery(data: SqlQueryExecuteRequest) {
  return request<SqlQueryResultVO>({ url: '/sql/query/execute', method: 'post', data })
}

/** 导出走 blob 下载，文件名兜底用 defineKey + 时间戳（响应头解析不到时）。 */
export function exportSqlQuery(data: SqlQueryExecuteRequest) {
  const fallbackFilename = `${data.defineKey}_${Date.now()}.xlsx`
  return download({ url: '/sql/query/export', method: 'post', data }, fallbackFilename)
}

// ---------- 即席 SQL 客户端 ----------
export function executeAdhocSql(data: SqlAdhocQueryRequest) {
  return request<SqlQueryResultVO>({ url: '/sql/query/adhoc', method: 'post', data })
}

export function exportAdhocSql(data: SqlAdhocQueryRequest) {
  const fallbackFilename = `sql-console_${Date.now()}.xlsx`
  return download({ url: '/sql/query/adhoc/export', method: 'post', data }, fallbackFilename)
}
