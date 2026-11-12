#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if [ -e .env ]; then echo '.env exists; refusing to replace credentials' >&2; exit 1; fi
umask 077
{ printf 'DB_PASSWORD=%s\n' "$(openssl rand -hex 24)"; printf 'RABBIT_PASSWORD=%s\n' "$(openssl rand -hex 24)"; printf 'OPERATOR_PASSWORD=%s\n' "$(openssl rand -hex 24)"; printf 'ADMIN_PASSWORD=%s\n' "$(openssl rand -hex 24)"; printf 'DEMO_ENABLED=true\n'; } > .env
echo 'Generated distinct credentials in local .env (mode 600). Read locally; do not commit or share.'
