#!/bin/bash
set -e
cd "$(dirname "$0")/../customer-admin-web"
exec npx vite --port 5175
