"""Install memory health and guarded switch tooling plus its systemd timer."""

from __future__ import annotations

import os
import shutil
from pathlib import Path

TARGET = Path(os.environ.get("OCTOP_OPS_DIR", "/opt/octop/ops"))
SYSTEMD = Path(os.environ.get("OCTOP_SYSTEMD_DIR", "/etc/systemd/system"))
OPS_SCRIPTS = (
    "memory_health_check.py",
    "memory_safe_repair.py",
    "memory_safe_switch.py",
    "storage_maintenance.py",
)


def _ops_source() -> Path:
    candidates = (
        Path(__file__).with_name("ops"),
        Path(__file__).resolve().parent.parent / "ops",
    )
    for candidate in candidates:
        if all((candidate / name).is_file() for name in OPS_SCRIPTS):
            return candidate
    raise FileNotFoundError(f"memory ops scripts not found in: {candidates}")


def main() -> None:
    source = _ops_source()
    TARGET.mkdir(parents=True, exist_ok=True)
    for name in OPS_SCRIPTS:
        destination = TARGET / name
        shutil.copy2(source / name, destination)
        os.chown(destination, 0, 0)
        destination.chmod(0o755)

    service = """[Unit]
Description=Octop memory SQLite health check
After=octop.service
Wants=octop.service

[Service]
Type=oneshot
User=root
Group=root
UMask=0077
EnvironmentFile=-/etc/octop/memory-alert.env
ExecStart=/opt/octop/venv/bin/python /opt/octop/ops/memory_health_check.py
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
ReadOnlyPaths=/data/agent-workspace/.octop/workspaces /opt/octop/agents
ReadWritePaths=/opt/octop/state/memory-health
"""
    timer = """[Unit]
Description=Run the Octop memory health check every 30 minutes

[Timer]
OnCalendar=*:0/30
Persistent=true
AccuracySec=1min
RandomizedDelaySec=30
Unit=octop-memory-health.service

[Install]
WantedBy=timers.target
"""
    storage_service = """[Unit]
Description=Prune old Octop releases and bound journal disk usage
After=octop.service
Wants=octop.service

[Service]
Type=oneshot
User=root
Group=root
UMask=0077
ExecStart=/opt/octop/venv/bin/python /opt/octop/ops/storage_maintenance.py --apply --release-keep=4 --journal-max-use=512M
Nice=10
IOSchedulingClass=idle
TimeoutStartSec=10min
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
ReadWritePaths=/opt/octop/releases /opt/octop/state/storage-maintenance /etc/systemd/journald.conf.d
"""
    storage_timer = """[Unit]
Description=Run Octop release and journal storage maintenance daily

[Timer]
OnCalendar=*-*-* 04:20:00
Persistent=true
AccuracySec=5min
RandomizedDelaySec=10min
Unit=octop-storage-maintenance.service

[Install]
WantedBy=timers.target
"""
    state_dir = Path("/opt/octop/state/memory-health")
    state_dir.mkdir(parents=True, exist_ok=True)
    state_dir.chmod(0o700)
    storage_state_dir = Path("/opt/octop/state/storage-maintenance")
    storage_state_dir.mkdir(parents=True, exist_ok=True)
    storage_state_dir.chmod(0o700)
    Path("/etc/systemd/journald.conf.d").mkdir(parents=True, exist_ok=True)
    if os.environ.get("OCTOP_INSTALL_SYSTEMD", "1") == "1":
        SYSTEMD.mkdir(parents=True, exist_ok=True)
        (SYSTEMD / "octop-memory-health.service").write_text(service)
        (SYSTEMD / "octop-memory-health.timer").write_text(timer)
        (SYSTEMD / "octop-storage-maintenance.service").write_text(storage_service)
        (SYSTEMD / "octop-storage-maintenance.timer").write_text(storage_timer)
    print(TARGET)


if __name__ == "__main__":
    main()
