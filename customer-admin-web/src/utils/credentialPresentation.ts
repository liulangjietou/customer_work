import type { SecretMetadataVO } from '@/types/api'

const CREDENTIAL_STATUS_LABELS: Record<SecretMetadataVO['status'], string> = {
  ACTIVE: '有效',
  EXPIRED: '已到期',
  DISABLED: '已停用',
  REVOKED: '已撤销',
  ERROR: '异常',
}

/** 仅翻译凭据元数据，不读取或展示密钥内容。 */
export function credentialStatusLabel(status: SecretMetadataVO['status']): string {
  return CREDENTIAL_STATUS_LABELS[status] ?? status
}
