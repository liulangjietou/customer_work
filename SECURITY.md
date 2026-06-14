# 安全策略 Security Policy

## 报告漏洞 / Reporting a Vulnerability

如发现安全漏洞，请**不要**公开提 Issue，而是通过邮件私下报告：

- 邮箱 / Email: owlzhangfq@gmail.com

请在报告中包含：复现步骤、影响范围、可能的修复建议。我们会在 **3 个工作日**内确认，并在修复后致谢。

If you discover a security vulnerability, please **do not** open a public issue.
Email the maintainer privately at the address above. We aim to acknowledge within 3 business days.

## 密钥与凭据 / Secrets

- 仓库**不包含任何真实密钥**。所有密钥（百炼 API Key、数据库 / Redis / Nacos 凭据）
  一律通过环境变量注入，见 `.env.example`。
- 请勿把 `.env`、生产密钥提交到版本库（已在 `.gitignore` 忽略）。
- 生产环境建议使用 Vault / KMS / K8s Secret 等密钥管理方案。

## 支持的版本 / Supported Versions

| 版本 | 支持 |
|---|---|
| 1.x | ✅ |
