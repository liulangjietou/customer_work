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
</style>
