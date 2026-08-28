<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    src?: string | null
    name?: string | null
    alt?: string
  }>(),
  {
    src: null,
    name: null,
    alt: '用户头像',
  },
)

const imageFailed = ref(false)

watch(
  () => props.src,
  () => {
    imageFailed.value = false
  },
)

const showImage = computed(() => Boolean(props.src) && !imageFailed.value)

const initials = computed(() => {
  const normalized = props.name?.trim()
  if (!normalized) {
    return ''
  }

  const words = normalized.split(/\s+/).filter(Boolean)
  if (words.length > 1) {
    return words
      .slice(0, 2)
      .map((word) => word.charAt(0))
      .join('')
      .toUpperCase()
  }

  const latinParts = normalized.match(/[A-Z]?[a-z]+|[A-Z]+(?![a-z])|\d+/g)
  if (latinParts && latinParts.length > 1) {
    return latinParts
      .slice(0, 2)
      .map((part) => part.charAt(0))
      .join('')
      .toUpperCase()
  }

  return normalized.charAt(0).toUpperCase()
})
</script>

<template>
  <span class="user-avatar" role="img" :aria-label="alt">
    <img v-if="showImage" :src="src || undefined" :alt="alt" @error="imageFailed = true" />
    <span v-else-if="initials" class="avatar-initials" aria-hidden="true">{{ initials }}</span>
    <van-icon v-else name="manager" class="avatar-icon" aria-hidden="true" />
  </span>
</template>

<style scoped>
.user-avatar {
  width: 100%;
  height: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--cw-signal, #316cff);
  color: #fff;
}

.user-avatar img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.avatar-initials {
  font-size: var(--user-avatar-initials-size, 22px);
  font-weight: 800;
  letter-spacing: 0.02em;
  line-height: 1;
}

.avatar-icon {
  font-size: var(--user-avatar-icon-size, 29px);
}
</style>
