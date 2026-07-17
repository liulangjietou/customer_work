<script setup lang="ts">
// 底部导航：route 模式，van-tabbar 按当前路由高亮。固定在视口底部（fixed），
// 各页面自行预留底部空间防遮挡（聊天页用 padding-bottom，滚动页在滚动区留白）。
</script>

<template>
  <van-tabbar route>
    <van-tabbar-item to="/messages" icon="chat-o">消息</van-tabbar-item>
    <van-tabbar-item to="/orders" icon="orders-o">订单</van-tabbar-item>
    <van-tabbar-item to="/profile" icon="user-o">我的</van-tabbar-item>
  </van-tabbar>
</template>
