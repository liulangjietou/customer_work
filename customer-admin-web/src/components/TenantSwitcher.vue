<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchCurrentView, listTenantOptions, switchTenantView, type TenantVO } from '@/api/tenant'

// 顶栏租户切换器：只有具备控制面跨租户能力的用户看得到。
// 普通租户管理员的视角恒等于自己所属租户，无需渲染只有一项的下拉。
const crossTenantAuthority = ref(false)
const userTenant = ref<string | null>(null)
const effectiveTenant = ref<string | null>(null)
const options = ref<TenantVO[]>([])
const switching = ref(false)

const selected = computed({
  get: () => effectiveTenant.value ?? userTenant.value ?? '',
  set: (value: string) => void handleSwitch(value),
})

async function load() {
  const view = await fetchCurrentView()
  crossTenantAuthority.value = view.crossTenantAuthority === true
  userTenant.value = view.userTenantId
  effectiveTenant.value = view.effectiveTenantId
  if (crossTenantAuthority.value) {
    options.value = (await listTenantOptions())
      .filter((tenant) => tenant.tenantCode !== userTenant.value)
  }
}

async function handleSwitch(tenantCode: string) {
  if (switching.value || tenantCode === (effectiveTenant.value ?? userTenant.value)) return
  switching.value = true
  try {
    const returnToOwnTenant = tenantCode === userTenant.value
    await switchTenantView(returnToOwnTenant ? undefined : tenantCode)
    ElMessage.success(returnToOwnTenant ? '已回到自身租户视角' : `已切换到租户 ${tenantCode}`)
    // 整页重载：当前页面上的列表、详情、缓存都属于上一个租户，逐个刷新既繁琐又容易漏掉一处
    window.location.reload()
  } finally {
    switching.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-select
    v-if="crossTenantAuthority"
    v-model="selected"
    class="tenant-switcher"
    size="default"
    :loading="switching"
    filterable
    title="切换租户视角"
    aria-label="租户视角"
  >
    <template #prefix>
      <el-icon><OfficeBuilding /></el-icon>
    </template>
    <el-option
      v-if="userTenant"
      :label="`自身租户（${userTenant}）`"
      :value="userTenant"
    />
    <el-option
      v-for="item in options"
      :key="item.tenantCode"
      :label="`${item.tenantName}（${item.tenantCode}）`"
      :value="item.tenantCode"
    />
  </el-select>
</template>

<style scoped>
.tenant-switcher {
  width: 220px;
  margin-right: 8px;
}

.tenant-switcher :deep(.el-select__wrapper) {
  min-height: 34px;
  border-radius: var(--cw-radius-md, 8px);
  background: var(--cw-canvas, var(--el-fill-color-extra-light));
  box-shadow: 0 0 0 1px var(--cw-line, var(--el-border-color-lighter)) inset;
}

.tenant-switcher:hover :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--cw-cobalt, var(--el-color-primary)) 45%, var(--cw-line, var(--el-border-color))) inset;
}

.tenant-switcher :deep(.el-select__prefix) {
  color: var(--cw-cobalt, var(--el-color-primary));
}

@media (max-width: 760px) {
  .tenant-switcher {
    width: 36px;
    margin-right: 0;
  }

  .tenant-switcher :deep(.el-select__wrapper) {
    min-height: 34px;
    justify-content: center;
    padding: 0;
    background: transparent;
    box-shadow: none;
  }

  .tenant-switcher:hover :deep(.el-select__wrapper) {
    background: color-mix(in srgb, var(--cw-cobalt, var(--el-color-primary)) 8%, transparent);
    box-shadow: none;
  }

  .tenant-switcher :deep(.el-select__selection),
  .tenant-switcher :deep(.el-select__suffix) {
    width: 0;
    min-width: 0;
    margin: 0;
    overflow: hidden;
    opacity: 0;
  }

  .tenant-switcher :deep(.el-select__prefix) {
    margin: 0;
    font-size: 16px;
  }
}
</style>
