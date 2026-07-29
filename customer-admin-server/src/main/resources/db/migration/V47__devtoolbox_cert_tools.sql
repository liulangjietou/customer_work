-- 开发者工具箱系统工具补充证书解析能力（Flyway V47）。
--
-- devtoolbox 这个 @Tool Bean 新增了 cert_parse（X.509 证书/证书链/CSR 解析）与 cert_match
-- （私钥与证书配对校验）两个工具方法，实现在 starter 的 CertDevToolOps——与管理台"开发者工具箱"
-- 页面走同一套解析逻辑，不存在两处实现漂移。工具目录是代码定义的，本迁移只更新描述文案，
-- 让智能体配置页勾选时能看到新能力（沿用 V15/V27 定的"系统工具目录"范式：无新增/删除，只改描述）。
--
-- cert_match 需要私钥明文作为工具参数，意味着私钥会进入模型上下文并落进对话历史。这是产品上
-- 明确接受的代价，约束已写进工具描述（仅在用户明确要求时调用、不主动索取、不复述），remark
-- 同步标注，供配置智能体的人决策是否勾选。
--
-- PFX/JKS 密钥库解析刻意不暴露给智能体：那是 multipart 文件上传能力，智能体没有文件通道。

SET NAMES utf8mb4;

UPDATE `ai_system_tool`
SET `description` = '开发者常用本地工具集：JSON格式化/压缩/校验、时间戳转换、Base64/URL编解码、哈希(HMAC)、UUID生成、AES加解密、正则测试、X.509证书/CSR解析、私钥与证书配对校验',
    `remark`      = '纯本地计算，无外部依赖；注意 cert_match 需传入私钥明文，会进入模型上下文与对话历史'
WHERE `tool_code` = 'devtoolbox';
