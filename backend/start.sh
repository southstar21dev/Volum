#!/bin/sh
set -e

if [ -n "$PB_ADMIN_EMAIL" ] && [ -n "$PB_ADMIN_PASSWORD" ]; then
  /usr/local/bin/pocketbase superuser upsert "$PB_ADMIN_EMAIL" "$PB_ADMIN_PASSWORD" --dir=/pb_data
fi

exec /usr/local/bin/pocketbase serve \
  --http=0.0.0.0:8090 \
  --dir=/pb_data \
  --hooksDir=/pb_hooks \
  --migrationsDir=/pb_migrations \
  ${PB_ORIGINS:+--origins=$PB_ORIGINS}
