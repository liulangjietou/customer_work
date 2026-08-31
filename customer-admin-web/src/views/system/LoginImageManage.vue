<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { UploadRequestOptions } from 'element-plus'
import {
  deleteLoginImage,
  fetchLoginImages,
  reorderLoginImages,
  updateLoginImageEnabled,
  uploadLoginImage,
  type LoginCarouselImageVO,
} from '@/api/login-image'

const loading = ref(false)
const images = ref<LoginCarouselImageVO[]>([])

/** 与后端 LoginImageStorageService 的最低分辨率门槛保持一致，改这里要同步改那边 */
const MIN_WIDTH = 1280
const MIN_HEIGHT = 720

/** imageUrl -> "宽×高"，纯前端用 Image 对象读实际像素，不占后端接口 */
const resolutions = ref<Record<string, { width: number; height: number }>>({})

function isLowResolution(url: string) {
  const size = resolutions.value[url]
  return !!size && (size.width < MIN_WIDTH || size.height < MIN_HEIGHT)
}

/**
 * 读取每张图的真实像素并缓存。校验门槛上线前存量的小图不会被拦，这里标出来方便识别替换；
 * 单张读失败不影响其他图（不写入即不展示尺寸）。
 */
function loadResolutions(list: LoginCarouselImageVO[]) {
  list.forEach((row) => {
    const img = new Image()
    img.onload = () => {
      resolutions.value = {
        ...resolutions.value,
        [row.imageUrl]: { width: img.naturalWidth, height: img.naturalHeight },
      }
    }
    img.src = row.imageUrl
  })
}

async function loadImages() {
  loading.value = true
  try {
    images.value = await fetchLoginImages()
    loadResolutions(images.value)
  } finally {
    loading.value = false
  }
}

onMounted(loadImages)

async function handleUpload(options: UploadRequestOptions) {
  try {
    await uploadLoginImage(options.file as File)
    ElMessage.success('上传成功，登录页已实时生效')
    await loadImages()
  } catch {
    // 全局 axios 拦截器已经弹过错误提示，这里不用重复弹
  }
}

async function handleToggleEnabled(row: LoginCarouselImageVO) {
  try {
    await updateLoginImageEnabled(row.id, row.enabled)
    ElMessage.success(row.enabled ? '已启用' : '已禁用')
  } catch {
    row.enabled = !row.enabled
  }
}

/** 上移/下移：本地交换后把完整 id 顺序提交后端重写 sortOrder */
async function handleMove(index: number, offset: -1 | 1) {
  const target = index + offset
  if (target < 0 || target >= images.value.length) {
    return
  }
  const list = [...images.value]
  ;[list[index], list[target]] = [list[target], list[index]]
  try {
    await reorderLoginImages(list.map((item) => item.id))
    images.value = list
  } catch {
    await loadImages()
  }
}

async function handleDelete(row: LoginCarouselImageVO) {
  await ElMessageBox.confirm(`确定删除「${row.imageName}」吗？删除后登录页立即不再展示该图。`, '删除确认', {
    type: 'warning',
  })
  await deleteLoginImage(row.id)
  ElMessage.success('删除成功')
  await loadImages()
}
</script>

<template>
  <div class="login-image-page">
    <el-card>
      <template #header>
        <div class="page-header">
          <div>
            <span class="page-title">轮播素材</span>
            <span class="page-subtitle">上传多张图片作为登录页轮播背景，登录页实时获取；全部禁用或为空时回退内置默认图</span>
          </div>
          <el-upload
            :show-file-list="false"
            :http-request="handleUpload"
            accept="image/png,image/jpeg,image/webp"
          >
            <el-button v-permission="'login-image:add'" class="cw-final-action" type="primary">上传图片</el-button>
          </el-upload>
        </div>
      </template>

      <el-empty v-if="!loading && images.length === 0" description="暂无轮播图，登录页正在使用内置默认图" />

      <div v-else v-loading="loading" class="image-grid">
        <el-card v-for="(row, index) in images" :key="row.id" shadow="hover" class="image-card">
          <el-image :src="row.imageUrl" fit="cover" class="image-preview" :preview-src-list="[row.imageUrl]" preview-teleported>
            <template #error>
              <div class="image-error">图片加载失败</div>
            </template>
          </el-image>
          <div class="image-meta">
            <span class="image-name" :title="row.imageName">{{ index + 1 }}. {{ row.imageName }}</span>
            <el-tag v-if="!row.enabled" type="info" size="small">已禁用</el-tag>
          </div>
          <div v-if="resolutions[row.imageUrl]" class="image-resolution">
            <span>{{ resolutions[row.imageUrl].width }} × {{ resolutions[row.imageUrl].height }}</span>
            <el-tag v-if="isLowResolution(row.imageUrl)" type="danger" size="small">
              分辨率偏低，登录页会拉伸模糊
            </el-tag>
          </div>
          <div class="image-actions">
            <el-switch
              v-model="row.enabled"
              v-permission="'login-image:edit'"
              inline-prompt
              active-text="启用"
              inactive-text="禁用"
              @change="handleToggleEnabled(row)"
            />
            <span>
              <el-button v-permission="'login-image:edit'" link :disabled="index === 0" @click="handleMove(index, -1)">
                上移
              </el-button>
              <el-button
                v-permission="'login-image:edit'"
                link
                :disabled="index === images.length - 1"
                @click="handleMove(index, 1)"
              >
                下移
              </el-button>
              <el-button v-permission="'login-image:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
            </span>
          </div>
        </el-card>
      </div>

      <div class="page-tip">
      支持 png/jpg/jpeg/webp，单张不超过 5MB，最多 10 张；轮播按序号顺序播放。
      背景图会铺满整屏，建议上传不低于 1920×1080 的图片，分辨率过低会被拉伸放大导致模糊。
    </div>
    </el-card>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
}

.page-subtitle {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.image-preview {
  width: 100%;
  height: 160px;
  border-radius: var(--cw-radius-sm);
  display: block;
}

.image-error {
  height: 160px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
}

.image-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.image-name {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.image-resolution {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.image-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.page-tip {
  margin-top: 16px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

@media (max-width: 767px) {
  .page-header {
    align-items: stretch;
    flex-direction: column;
  }

  .page-subtitle {
    display: block;
    margin: 6px 0 0;
    line-height: 1.5;
  }

  .page-header :deep(.el-upload),
  .page-header :deep(.el-button) {
    width: 100%;
  }

  .image-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .image-actions {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
}
</style>
