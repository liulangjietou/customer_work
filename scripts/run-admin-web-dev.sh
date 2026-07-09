#!/bin/bash
set -e
cd "$(dirname "$0")/../customer-admin-web"
exec npm run dev
