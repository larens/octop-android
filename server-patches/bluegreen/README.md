# Octop Blue-Green Upgrade

This kit keeps community Octop upgrades away from the production virtualenv
until migrations, custom patches, authentication, and mobile-facing APIs have
passed acceptance on a copied database.

## Incident this prevents

An in-place community upgrade previously introduced an upstream migration
version that collided with a local custom migration (`022`). The service entered
a crash loop and eventually hit systemd's `start-limit-hit`. Acceptance now
detects and reconciles migration collisions in a candidate release before the
production virtualenv or database is touched.

## Layout

```text
/opt/octop/
  venv -> releases/current/venv
  releases/
    current -> <release-id>
    <release-id>/
      venv/
      patch-pack/
      manifest.json
      acceptance/
  state/bluegreen/
    current -> <release-id>
    previous -> <release-id>
```

The production service still runs `/opt/octop/bin/octop`, which resolves
`/opt/octop/venv`. Switching `state/current` is therefore the activation point.

## First deployment

```bash
install -d -m 700 /opt/octop/bluegreen
cp -a server-patches/bluegreen/. /opt/octop/bluegreen/
cp -a server-patches/octop-1.0.2 /opt/octop/bluegreen/octop-1.0.2
chmod +x /opt/octop/bluegreen/octop-bluegreen
ln -sfn /opt/octop/bluegreen/octop-bluegreen /usr/local/sbin/octop-bluegreen
octop-bluegreen bootstrap
```

`bootstrap` stops the service briefly, moves the existing venv into the first
immutable release, creates the activation links, and verifies health. It does
not change the installed Octop version.

## Upgrade flow

```bash
octop-bluegreen check
octop-bluegreen stage <latest_stable>
octop-bluegreen promote <release-id>
```

Use `latest_stable` from `check`, not `latest_any`. The `1.0.2` patch pack keeps
the community distribution version unchanged and installs only the explicitly
listed mobile/Today backport modules plus the regular custom patch set.

`stage` performs all of the following without touching the production process
or production database:

1. Creates an isolated Python 3.12 virtualenv.
2. Installs the exact requested Octop version from PyPI.
3. Copies the custom migration pack and moves upstream migration collisions to
   unused version numbers.
4. Reapplies every custom patch and runs the patch unit tests.
5. Copies the current SQLite database through the SQLite backup API.
6. Disables agents, channels, cron jobs, proactive care, and monitors in the
   candidate database.
7. Starts the candidate on `127.0.0.1:18088`.
8. Tests health, login, agents, providers, threads, cron, Today, mobile diaries,
   and the managed-update guard.

`promote` repeats acceptance on a fresh database copy, creates a pre-promote
backup, stops the service, switches the release link, starts the service, and
checks both the local and public health endpoints. If any step after the service
stop fails, it restores the database backup, activation links, and previous
release automatically.

## Rollback

```bash
octop-bluegreen rollback
octop-bluegreen rollback <release-id>
```

Rollback always takes a database backup first. It does not reverse schema
changes automatically; use the pre-promote backup when a failed version has
already changed the schema. If the selected older release cannot become
healthy, the command restores the pre-rollback database copy and reactivates the
release that was running when rollback started.

## Managed updater

The candidate patch disables the dashboard's in-place upgrade action when
`OCTOP_MANAGED_UPDATE=1`. Systemd receives that setting through
`/etc/systemd/system/octop.service.d/20-managed-update.conf`, so the UI must use
this staged flow instead of overwriting the running virtualenv.

## Server and mobile release channels

Server community releases and Android releases are independent:

- Server updates use `stage -> acceptance -> promote`; the web dashboard never
  installs over the active release.
- Android releases keep their own signing, store rollout, and client version
  checks. A server upgrade does not require shipping a new APK unless API
  acceptance detects a breaking mobile contract.
- The candidate runs the mobile-critical smoke suite before promotion so a
  server-only upgrade cannot accidentally remove Today, Memory, cron, thread, or
  authentication behavior.

## Files retained for audit

Each release keeps its migration remap report, patch backups, test output, and
JSON acceptance evidence under `/opt/octop/releases/<release-id>/acceptance/`.
Pre-promote database backups are under `/opt/octop/backups/`.
