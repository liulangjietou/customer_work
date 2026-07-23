import { request } from './request'
import type { ChannelRobotPageQuery, ChannelRobotSaveRequest, ChannelRobotVO, MpPageResult } from '@/types/api'

// 后端契约根路径：baseURL 为 /api，拼出 /api/channel-robots/*，与既有 /aiconfig/* 路径分属不同前缀。
const BASE_URL = '/channel-robots'

export function pageChannelRobots(query: ChannelRobotPageQuery) {
  return request<MpPageResult<ChannelRobotVO>>({ url: `${BASE_URL}/page`, method: 'get', params: query })
}

export function createChannelRobot(data: ChannelRobotSaveRequest) {
  return request<void>({ url: BASE_URL, method: 'post', data })
}

export function updateChannelRobot(id: number, data: ChannelRobotSaveRequest) {
  return request<void>({ url: `${BASE_URL}/${id}`, method: 'put', data })
}

export function deleteChannelRobot(id: number) {
  return request<void>({ url: `${BASE_URL}/${id}`, method: 'delete' })
}
