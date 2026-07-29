import { request } from './request'

// 字典管理 API，与 admin-server /api/dict/** 契约对应。
// 字典数据落在客服端库 cw_dict_type / cw_dict_item（单一数据真源），admin 直连维护。

export interface DictTypeVO {
  id: number
  dictType: string
  typeName: string
  remark: string | null
  enabled: boolean
  itemCount: number
  createdAtMs: number | null
  updatedAtMs: number | null
}

export interface DictTypeSaveRequest {
  dictType: string
  typeName: string
  remark?: string | null
  enabled?: boolean
}

export interface DictItemVO {
  id: number
  dictType: string
  itemKey: string
  itemLabel: string
  sort: number
  enabled: boolean
  remark: string | null
  createdAtMs: number | null
  updatedAtMs: number | null
}

export interface DictItemSaveRequest {
  itemKey: string
  itemLabel: string
  sort?: number
  enabled?: boolean
  remark?: string | null
}

/** 消费端下拉选项（仅启用项）。 */
export interface DictOption {
  value: string
  label: string
}

// ---------- 字典类型 ----------

export function listDictTypes() {
  return request<DictTypeVO[]>({ url: '/dict/types', method: 'get' })
}

export function createDictType(data: DictTypeSaveRequest) {
  return request<void>({ url: '/dict/types', method: 'post', data })
}

export function updateDictType(id: number, data: DictTypeSaveRequest) {
  return request<void>({ url: `/dict/types/${id}`, method: 'put', data })
}

export function deleteDictType(id: number) {
  return request<void>({ url: `/dict/types/${id}`, method: 'delete' })
}

// ---------- 字典项 ----------

export function listDictItems(dictType: string) {
  return request<DictItemVO[]>({ url: '/dict/items', method: 'get', params: { dictType } })
}

export function createDictItem(dictType: string, data: DictItemSaveRequest) {
  return request<void>({ url: '/dict/items', method: 'post', params: { dictType }, data })
}

export function updateDictItem(id: number, data: DictItemSaveRequest) {
  return request<void>({ url: `/dict/items/${id}`, method: 'put', data })
}

export function deleteDictItem(id: number) {
  return request<void>({ url: `/dict/items/${id}`, method: 'delete' })
}

// ---------- 消费端 ----------

/** 业务页面下拉选项：仅启用项；类型停用/不存在返回空数组（调用方自带硬编码兜底）。 */
export function fetchDictOptions(dictType: string) {
  return request<DictOption[]>({ url: `/dict/options/${dictType}`, method: 'get' })
}
