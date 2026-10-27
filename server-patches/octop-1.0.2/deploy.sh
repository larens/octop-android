#!/usr/bin/env bash
set -euo pipefail

site=/opt/octop/venv/lib/python3.12/site-packages/octop
stamp=$(date +%Y%m%d-%H%M%S)
backup=/opt/octop/backups/turn-delete-$stamp
mkdir -p "$backup"
cp "$site/api/routers/chat/history.py" "$backup/history.py"
cp "$site/infra/cron/tools.py" "$backup/cron-tools.py"
cp "$site/infra/cron/delivery.py" "$backup/cron-delivery.py"
cp "$site/infra/today/automatic.py" "$backup/automatic.py"
cp "$site/infra/today/cards.py" "$backup/today-cards.py"
cp "$site/api/routers/mobile_ingest.py" "$backup/mobile_ingest.py"
cp turn_delete.py "$site/infra/agents/threads/turn_delete.py"
/opt/octop/venv/bin/python install_patch.py
/opt/octop/venv/bin/python install_cron_isolation.py
/opt/octop/venv/bin/python install_cron_delivery_isolation.py
/opt/octop/venv/bin/python install_cron_today_cards.py
/opt/octop/venv/bin/python migrate_existing_tasks.py
/opt/octop/venv/bin/python -m py_compile \
  "$site/api/routers/chat/history.py" \
  "$site/infra/cron/tools.py" \
  "$site/infra/cron/delivery.py" \
  "$site/infra/today/automatic.py" \
  "$site/infra/today/cards.py" \
  "$site/infra/today/cron_cards.py" \
  "$site/api/routers/mobile_ingest.py" \
  "$site/infra/agents/threads/turn_delete.py"
systemctl restart octop
systemctl is-active --quiet octop
echo "$backup"
