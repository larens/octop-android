"""Install the Xiaomi Health cloud collector and its systemd timer."""

from __future__ import annotations

import shutil
from pathlib import Path

TARGET = Path("/opt/octop/xiaomi_health")
SYSTEMD = Path("/etc/systemd/system")


def main() -> None:
    TARGET.mkdir(parents=True, exist_ok=True)
    for name in (
        "health_trend_memory.py",
        "xiaomi_health_cloud.py",
        "xiaomi_health_sync.py",
        "configure_xiaomi_health.py",
    ):
        shutil.copyfile(Path(__file__).with_name(name), TARGET / name)
    (TARGET / "__init__.py").write_text("")

    service = """[Unit]
Description=Octop Xiaomi Health Cloud Sync
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=root
Group=root
UMask=0077
Environment=OCTOP_DB=/opt/octop/octop.db
ExecStart=/opt/octop/venv/bin/python /opt/octop/xiaomi_health/xiaomi_health_sync.py
Nice=10
TimeoutStartSec=5min
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
ReadWritePaths=/opt/octop/octop.db /opt/octop /data/agent-workspace/.octop/workspaces
"""
    timer = """[Unit]
Description=Run Octop Xiaomi Health Cloud Sync every 30 minutes

[Timer]
OnCalendar=*:0/30
Persistent=true
AccuracySec=1min
Unit=octop-xiaomi-health.service

[Install]
WantedBy=timers.target
"""
    (SYSTEMD / "octop-xiaomi-health.service").write_text(service)
    (SYSTEMD / "octop-xiaomi-health.timer").write_text(timer)
    print(TARGET)


if __name__ == "__main__":
    main()
