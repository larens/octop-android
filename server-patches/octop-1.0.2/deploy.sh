#!/usr/bin/env bash
set -euo pipefail

if [[ "${OCTOP_ALLOW_INPLACE_PATCH:-0}" != "1" ]]; then
  cat >&2 <<'EOF'
Direct in-place patching is disabled on blue-green managed hosts.
Use server-patches/bluegreen/octop-bluegreen stage/promote instead.
Set OCTOP_ALLOW_INPLACE_PATCH=1 only for an emergency repair.
EOF
  exit 2
fi

site=/opt/octop/venv/lib/python3.12/site-packages/octop
stamp=$(date +%Y%m%d-%H%M%S)
backup=/opt/octop/backups/turn-delete-$stamp
mkdir -p "$backup"
cp "$site/api/routers/chat/history.py" "$backup/history.py"
cp "$site/infra/cron/tools.py" "$backup/cron-tools.py"
cp "$site/infra/cron/delivery.py" "$backup/cron-delivery.py"
cp "$site/infra/today/automatic.py" "$backup/automatic.py"
cp "$site/infra/today/cards.py" "$backup/today-cards.py"
cp "$site/infra/today/health_cards.py" "$backup/health-cards.py"
cp "$site/api/routers/mobile_ingest.py" "$backup/mobile_ingest.py"
cp "$site/api/routers/artifact_shares.py" "$backup/artifact_shares.py" 2>/dev/null || true
cp "$site/api/app.py" "$backup/app.py"
cp "$site/api/deps.py" "$backup/deps.py"
cp "$site/../octop_memory/application/dashboard_data.py" "$backup/dashboard-data.py"
cp "$site/../octop_memory/adapters/bridge/handlers.py" "$backup/memory-handlers.py"
cp "$site/api/routers/memory.py" "$backup/memory-router.py"
cp turn_delete.py "$site/infra/agents/threads/turn_delete.py"
cp memory_diaries.py "$site/infra/memory/mobile_diaries.py"
cp mobile_profiles.py "$site/infra/memory/mobile_profiles.py"
cp artifact_shares.py "$site/api/routers/artifact_shares.py"
/opt/octop/venv/bin/python install_patch.py
/opt/octop/venv/bin/python install_memory_diaries.py
/opt/octop/venv/bin/python install_mobile_ingest_router.py
/opt/octop/venv/bin/python install_artifact_shares.py
/opt/octop/venv/bin/python install_artifact_lifecycle.py
/opt/octop/venv/bin/python install_cron_isolation.py
/opt/octop/venv/bin/python install_cron_delivery_isolation.py
/opt/octop/venv/bin/python install_cron_today_cards.py
/opt/octop/venv/bin/python install_calendar_card_format.py
/opt/octop/venv/bin/python install_today_feedback_semantics.py
/opt/octop/venv/bin/python install_xiaomi_health_cloud.py
/opt/octop/venv/bin/python migrate_existing_tasks.py
/opt/octop/venv/bin/python -m py_compile \
  "$site/api/routers/chat/history.py" \
  "$site/infra/cron/tools.py" \
  "$site/infra/cron/delivery.py" \
  "$site/infra/today/automatic.py" \
  "$site/infra/today/cards.py" \
  "$site/infra/today/cron_cards.py" \
  "$site/infra/today/health_cards.py" \
  "$site/api/routers/mobile_ingest.py" \
  "$site/api/routers/artifact_shares.py" \
  "$site/api/app.py" \
  "$site/api/deps.py" \
  "$site/infra/agents/middleware/thread_artifacts.py" \
  "$site/infra/db/repos/threads.py" \
  "$site/infra/agents/threads/turn_delete.py" \
  "$site/infra/memory/mobile_diaries.py" \
  "$site/infra/memory/mobile_profiles.py" \
  "$site/../octop_memory/application/dashboard_data.py" \
  "$site/../octop_memory/adapters/bridge/handlers.py" \
  "$site/api/routers/memory.py" \
  health_trend_memory.py \
  xiaomi_health_cloud.py \
  xiaomi_health_sync.py \
  configure_xiaomi_health.py
systemctl restart octop
systemctl is-active --quiet octop
systemctl daemon-reload
systemctl enable --now octop-xiaomi-health.timer
echo "$backup"
