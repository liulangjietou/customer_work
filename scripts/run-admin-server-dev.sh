#!/bin/bash
set -e
export JAVA_HOME=/Users/zhangfuqiang/Library/Java/JavaVirtualMachines/corretto-17.0.16/Contents/Home
export ADMIN_MYSQL_PASSWORD=root
export ADMIN_AES_SECRET_KEY=01234567890123456789012345678901
cd "$(dirname "$0")/../customer-admin-server"
exec mvn spring-boot:run -Dspring-boot.run.profiles=dev -q
