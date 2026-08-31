<script setup lang="ts">
defineProps<{
  eyebrow: string
  title: string
  description: string
  currentUser?: string
}>()
</script>

<template>
  <figure class="home-scene">
    <img src="/home-cover.jpg" alt="客服人员在服务中心与客户沟通的场景" />
    <div class="home-scene__scrim" aria-hidden="true" />
    <figcaption>
      <span>{{ eyebrow }}</span>
      <strong>{{ title }}</strong>
      <p>{{ description }}</p>
      <small v-if="currentUser">当前登录 · {{ currentUser }}</small>
    </figcaption>
  </figure>
</template>

<style scoped>
.home-scene {
  position: relative;
  min-width: 0;
  min-height: 100%;
  margin: 0;
  overflow: hidden;
  background: var(--home-ink-soft, var(--cw-ink-elevated));
}

.home-scene img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 58% center;
  transform: scale(1.01);
}

.home-scene__scrim {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgb(8 19 33 / 10%) 20%, rgb(8 19 33 / 88%) 100%),
    linear-gradient(90deg, rgb(8 19 33 / 24%), transparent 44%);
}

.home-scene figcaption {
  position: absolute;
  right: clamp(24px, 3vw, 44px);
  bottom: clamp(26px, 4vw, 50px);
  left: clamp(24px, 3vw, 44px);
  color: #ffffff;
}

.home-scene figcaption > span {
  display: block;
  margin-bottom: 12px;
  color: var(--home-signal, var(--home-amber));
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.16em;
}

.home-scene figcaption strong {
  display: block;
  max-width: 360px;
  font-size: clamp(24px, 2.3vw, 36px);
  font-weight: 740;
  line-height: 1.18;
  letter-spacing: -0.035em;
}

.home-scene figcaption p {
  max-width: 380px;
  margin: 14px 0 0;
  color: rgb(240 246 251 / 72%);
  font-size: 12px;
  line-height: 1.7;
}

.home-scene figcaption small {
  display: inline-block;
  margin-top: 24px;
  padding-top: 12px;
  border-top: 1px solid rgb(255 255 255 / 24%);
  color: rgb(240 246 251 / 62%);
  font-size: 10px;
  letter-spacing: 0.08em;
}

@media (max-width: 820px) {
  .home-scene {
    min-height: 340px;
  }
}

@media (max-width: 520px) {
  .home-scene {
    min-height: 280px;
  }

  .home-scene figcaption {
    right: 22px;
    bottom: 24px;
    left: 22px;
  }

  .home-scene figcaption strong {
    font-size: 24px;
  }
}
</style>
