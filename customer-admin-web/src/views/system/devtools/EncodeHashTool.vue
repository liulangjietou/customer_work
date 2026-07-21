<script setup lang="ts">
// 编解码/哈希工具：Base64/URL/Hex 复用同一套"编码↔解码"展示壳（CodecModePanel），
// 哈希与 UUID 各自逻辑差异较大，单独成组件；本文件只负责 Tab 布局。
import { usePersistedRef } from './composables/useToolStorage'
import { base64DecodeUtf8, base64EncodeUtf8, hexToText, textToHex, urlDecode, urlEncode } from './composables/codecUtils'
import CodecModePanel from './CodecModePanel.vue'
import HashPanel from './HashPanel.vue'
import UuidPanel from './UuidPanel.vue'

const activeTab = usePersistedRef('codec:activeTab', 'base64')
</script>

<template>
  <div class="encode-hash-tool">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="Base64" name="base64">
        <CodecModePanel
          tool-key="base64"
          :encode-fn="base64EncodeUtf8"
          :decode-fn="base64DecodeUtf8"
          placeholder="输入原文，UTF-8 安全，支持中文…"
        />
      </el-tab-pane>
      <el-tab-pane label="URL" name="url">
        <CodecModePanel tool-key="url" :encode-fn="urlEncode" :decode-fn="urlDecode" />
      </el-tab-pane>
      <el-tab-pane label="Hex" name="hex">
        <CodecModePanel tool-key="hex" :encode-fn="textToHex" :decode-fn="hexToText" placeholder="编码：输入原文；解码：输入十六进制字符串…" />
      </el-tab-pane>
      <el-tab-pane label="哈希 / HMAC" name="hash">
        <HashPanel />
      </el-tab-pane>
      <el-tab-pane label="UUID" name="uuid">
        <UuidPanel />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
