-- 开发者工具箱系统工具补充一批能力（Flyway V48）。
--
-- devtoolbox 这个 @Tool Bean 新增 9 个工具方法，实现全部在 starter 的 Ops 里，与管理台
-- "开发者工具箱"页面共用同一套逻辑：
--   hex_encode / hex_decode                                   十六进制编解码（页面早有、智能体侧此前缺）
--   json_escape / json_unescape / json_unicode_decode         JSON 转义、去转义、Unicode 解码（同上）
--   cron_explain                                              cron 逐字段释义 + 推算后续执行时间
--   jwt_decode                                                JWT 解码、有效期判定、HS* 签名校验
--   text_diff                                                 行级文本比对
--   data_convert                                              JSON / YAML / XML 互转
--
-- 同时 aes_encrypt / aes_decrypt 补齐了 CTR 模式与 padding、密钥/IV 编码、密文输出格式等参数，
-- 使智能体侧成为页面版能力的严格超集——此前两边模式集合互不包含（页面 CBC/ECB/CTR、智能体
-- CBC/ECB/GCM），且 IV 与密文编码默认值也不同，同一份密文换一侧就解不开。
--
-- 工具目录是代码定义的，本迁移只更新描述文案，让智能体配置页勾选时能看到新能力
-- （沿用 V15/V27/V47 定的"系统工具目录"范式：无新增/删除，只改描述）。
--
-- jwt_decode 的令牌与密钥会进入模型上下文并落进对话历史，约束已写进工具描述（仅在用户明确要求时
-- 调用、不主动索取密钥），remark 同步标注，供配置智能体的人决策是否勾选。

SET NAMES utf8mb4;

UPDATE `ai_system_tool`
SET `description` = '开发者常用本地工具集：JSON格式化/压缩/校验/转义/去转义/Unicode解码、时间戳转换、Base64/URL/Hex编解码、哈希(HMAC)、UUID生成、AES加解密(CBC/ECB/CTR/GCM)、正则测试、X.509证书/CSR解析、私钥与证书配对校验、cron表达式解析、JWT解析、文本比对、JSON/YAML/XML互转',
    `remark`      = '纯本地计算，无外部依赖；注意 cert_match 需传入私钥明文、jwt_decode 可能传入令牌与签名密钥，均会进入模型上下文与对话历史'
WHERE `tool_code` = 'devtoolbox';
