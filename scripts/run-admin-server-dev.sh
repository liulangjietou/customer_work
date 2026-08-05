#!/bin/bash
set -e
export JAVA_HOME=/Users/zhangfuqiang/Library/Java/JavaVirtualMachines/corretto-17.0.16/Contents/Home
export ADMIN_MYSQL_PASSWORD=root
export ADMIN_AES_SECRET_KEY=01234567890123456789012345678901
# 本机 XXL-JOB 调度中心（http://localhost:8088/xxl-job-admin）用的是官方内置默认 token，
# 未改过 xxl.job.accessToken 配置，故这里保持同值；调度中心侧改了 token 后这里要同步改。
export ADMIN_XXL_JOB_ENABLED=true
export ADMIN_XXL_JOB_ACCESS_TOKEN=default_token
# 登录态存 Redis（服务重启不掉线，见 AdminSaTokenDaoConfig）：本机 redis 容器是 localhost:6379
# 无密码，与默认值一致故无需显式 export；换地址/加密码用 ADMIN_REDIS_HOST/PORT/PASSWORD 覆盖。
# 注意 Redis 起不来时后台登录会直接失败（fail-closed），应急可 export ADMIN_SATOKEN_REDIS_PERSISTENT=false。
cd "$(dirname "$0")/../customer-admin-server"
exec mvn spring-boot:run -Dspring-boot.run.profiles=dev -q
