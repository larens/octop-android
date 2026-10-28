# Octop Android

A native Android workbench for self-hosted [Octop](https://github.com/TencentCloud/Octop). It is more than a WebView wrapper: one APK can connect to any instance and brings **conversations, calendar, Today, scheduled tasks, memory, and daily diaries** into one executable workflow.

Install the app, enter your Octop domain, and sign in. You can assign work from your phone, watch the agent reason and call tools, inspect real execution results, and let the server organize your calendar, produce daily reviews, and retain useful context over time.

English | [中文](README.md)

## Core capabilities

| Capability | What you can do on the phone |
|------------|------------------------------|
| **Conversations** | Start work, stream replies, inspect tool execution, answer approvals, upload attachments, involve multiple agents, and resume interrupted turns |
| **Calendar and Today** | Bring the next three days, waiting conversations, and followed updates into one focused feed |
| **Scheduled tasks** | Describe recurring work in natural language; the server runs it even when the app is closed |
| **Memory and daily diaries** | Review the server-maintained profile, the last seven days of diaries, and lessons distilled from conversations |
| **Phone data** | Read the system calendar and share inbox without duplicating health collection on the device |
| **System integration** | Share target, App Shortcuts, deep links, long-running foreground notifications, and a WebView fallback |

## Conversations: finish the work, not just generate a reply

The Conversations tab opens directly into a new conversation. History lives in a drawer instead of blocking the main entry point.

- **Streaming execution**: WebSocket events stream the response, reasoning process, and tool calls. Tool arguments, results, and failures can be expanded.
- **Human confirmation**: HITL questions and tool approvals pause the turn until you respond. Confirmation resumes through SSE instead of pretending the task already completed.
- **Stop a turn**: An active turn can be cancelled when it is no longer useful.
- **Attachments**: Send files and images, take a photo, record a voice note, or share text, images, and files into Octop from another app.
- **Multiple agents**: Mention an expert as `@ExpertName` in the message; the app translates it into the target agent list for that turn.
- **Conversation management**: Rename, pin, delete, or bulk-delete conversations. The server remains authoritative while Room keeps a local mirror and streaming state.
- **Long-running work**: A foreground service keeps a progress notification alive while a turn is running.
- **System shortcuts**: Open conversations through `octopmobile://thread/...` and use long-press shortcuts for a new task, voice note, or photo.

Conversations are not just chat logs. Tool calls, approvals, and results stay attached to the same traceable thread, and Today cards can return you to the source conversation when follow-up work is needed.

## Calendar and Today: collapse the day into what matters now

The Android app reads the system calendar in read-only mode, covering seven days in the past and ninety days ahead. Feishu events that have already been synced to the system calendar enter the same pipeline; Octop does not request Feishu account access directly on the phone.

Today prioritizes information instead of presenting a flat list:

- Running, waiting-for-confirmation, and recently active conversations appear first.
- Upcoming events are ordered by time. Events that have already ended move into “Finished today” and no longer show advance actions.
- Of the next three days, only tomorrow is emphasized; the following two days remain background context.
- Schedule cards explain why they are relevant now and support feedback, snoozing until tomorrow, inspecting evidence, or opening the execution conversation.
- Task results, followed topics, and health results are projected by the server. A foreground notification WebSocket updates Today while the app is open, and the app refreshes missed cards when it returns to the foreground.
- Source coverage is visible. Expired, pending, or broken sources are reported honestly instead of being presented as “nothing changed.”

At the bottom, **Today's recap** summarizes what actually happened:

- Completed calendar events
- Conversations that advanced
- Information that was followed or handled

The recap is not another generic daily report. It closes the loop on the day and makes the next starting point clear for tomorrow.

## Scheduled tasks: describe recurring work in one sentence

The Tasks tab has two views: “Added” and “For you.” You do not need to learn cron first. Describe the outcome and timing directly:

> Every day at 7:30 AM, organize today's meetings and calendar, highlight preparation, conflicts, and anything that needs confirmation.

The app asks the agent to turn that request into a task title, trigger rule, and execution prompt. Later changes, pauses, resumes, and deletions can also be expressed in natural language.

- **Server-side execution**: Octop schedules the task. Turning off the phone or closing the app does not stop it.
- **Multiple schedules**: Cron, fixed intervals, one-off times, and connector events are supported.
- **Results return to Today**: Repeated runs update the same card instead of piling up duplicate results every day.
- **Recommended tasks**: Suggestions are generated from installed connectors and can include news briefings, inbox summaries, reading reviews, today's meetings, and tomorrow's calendar preview.
- **Execution history**: Success, failure, and waiting-for-confirmation states are preserved. A failed run is not presented as completed.

Recommendations are not permanent ads. If the required connector or capability is unavailable, the list shrinks or becomes empty rather than showing an entry that cannot run.

## Memory and daily diaries: keep what matters from each day

The Memory tab shows long-term information maintained by the server: user profile, current focus, things you have explicitly shared, atomic memories and entities, and emotional episodes.

**Daily diaries** are generated by the server from each day's conversations:

- The default view shows the last seven days, ordered by date with emotion tags.
- All diaries can be browsed by month.
- A diary contains the day's summary, “What I learned today,” emotion, and the signature of the agent that wrote it.

The diary is not a copy of every message. It compresses progress, conclusions, and feelings into a short retrievable record.

The local data layer also supports four structured memory types: people, projects, preferences, and commitments, with source conversations retained for traceability. Server memory and local memory serve different layers, and inferred content is not presented as something the user has explicitly confirmed.

## Health at a glance

Health data is server-first. Xiaomi Fitness data syncs into the Octop server, is written to `mobile_events`, and is projected into Today cards. Android only uses its own Octop login and never receives Xiaomi account cookies, `passToken`, `serviceToken`, or `ssecurity`.

- **Morning reminder**: Around 07:35, review last night's sleep duration, deep-sleep share, and progress toward an eight-hour target.
- **Evening summary**: Around 19:35, summarize the day's steps, heart rate, blood oxygen, stress, effective standing, and exercise, then provide lifestyle suggestions.
- **Only surface daytime exceptions**: Normal stress, heart-rate, or inactivity readings do not create extra cards. A separate reminder appears only when something needs attention.
- **Compact charts**: Steps use a line chart; sleep uses a goal proportion; heart rate, blood oxygen, and effective standing use columns; stress is treated as supporting information.
- **Long-term trends**: Only seven-day aggregates such as average steps and sleep enter long-term memory. Raw sensor details are not copied into every memory entry.

The Android manifest **does not request Health Connect permissions**, and the phone no longer duplicates health collection. The current source is an unofficial Xiaomi cloud interface that is not a stable public API and may break. Keep does not expose a health-data API. Health reminders use WorkManager and are inexact; health suggestions are for general lifestyle reference and are not medical advice.

## Getting started

1. Download the latest `octop-native.apk` from [Releases](https://github.com/larens/octop-android/releases).
2. Install it. You may need to allow installation from unknown sources.
3. Enter your instance address, for example `https://octop.example.com`, then connect and sign in.
   - `https://` is added automatically and a trailing `/` is removed.
   - The instance must be deployed at the domain root. Sub-paths such as `https://example.com/octop` are rejected.
4. Sign in and use the four bottom tabs: Conversations, Today, Tasks, and Memory.

The app remembers the address and session. Settings lets you change the instance, sign out, or enable **Allow plain HTTP** for a trusted intranet; it is off by default.

Long-press the launcher icon to start a new task, record a voice note, or take a photo. When another app shares content to Octop, choose either **Ask the agent** or **Save to inbox**.

### Optional TWA

The repository also keeps the TWA delivery for users who want no address bar and shared Chrome login state. The host is fixed at build time, so every domain needs its own build:

```bash
./twa/scripts/build-twa.sh
```

Edit `twa/twa.config.json` before building. The script generates `twa/build/app-release-signed.apk`, `build-assetlinks/assetlinks.json`, and `build-assetlinks/nginx-location.conf`. Publish `assetlinks.json` at `https://your-domain/.well-known/assetlinks.json`.

## Server requirements

At minimum:

1. Expose the instance publicly over HTTPS. Cleartext HTTP is denied by default.
2. Proxy WebSocket and SSE correctly. Buffering must be disabled, or streaming replies appear to stall and then arrive all at once.
3. Set the agent's `conversation_mode` to `craft`; otherwise it remains read-only.
4. TWA users must also deploy `/.well-known/assetlinks.json`.

A minimal reverse-proxy setup needs WebSocket Upgrade forwarding, SSE buffering disabled, and long read/write timeouts:

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

location / {
    proxy_pass http://127.0.0.1:8088;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
    client_max_body_size 512m;
}
```

Advanced Today projections and Xiaomi health sync require matching server patches. The scripts and tests are in [`server-patches/octop-1.0.2`](server-patches/octop-1.0.2). Xiaomi credentials must be entered through a controlled server-side flow and must never be written to scripts, application resources, documentation, or Git.

## Security and privacy

- No analytics and no telemetry. Usage data is not sent anywhere except the instance you configure.
- The login session is stored in Android encrypted storage, and system backup is disabled for the app.
- Calendar and shared content are encrypted locally before upload. Pending items are removed only after the server explicitly confirms receipt.
- The Xiaomi account session exists only in the server-side Octop `secrets` store. Android never holds health-source credentials.
- Long-term health memory stores seven-day aggregates, not account identifiers or raw sensor details.
- Health suggestions are not medical advice. You can revoke the source authorization and delete the related memory.
- The repository does not contain keystores, cookies, tokens, environment files, or real health data.

## Permissions

| Permission | Purpose | Required |
|------------|---------|----------|
| `INTERNET` | Connect to the configured Octop instance | Yes |
| `RECORD_AUDIO` | Voice input and voice notes | Optional |
| `CAMERA` | Capture a photo as a conversation attachment | Optional |
| `READ_CALENDAR` | Read the system calendar into Today | Optional |
| `POST_NOTIFICATIONS` | Long-running task progress and health reminders | Optional |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Keep the progress service alive during a turn | Granted automatically |
| `WRITE_EXTERNAL_STORAGE` | Save downloads on Android 9 and below | Optional; legacy systems only |

The current client does not request Health Connect permissions. Calendar and notification permissions are scoped to their own features and do not trigger health access.

## Building

Requirements: JDK 17 and Android SDK 36 (`platforms;android-36` and `build-tools;36.0.0`).

```bash
./gradlew :app:test
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

Release signing:

```bash
./scripts/gen-keystore.sh
./gradlew :app:assembleRelease
```

Signing values are read from `keystore.properties` or the `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` environment variables. None of them are committed. Without a signing configuration, release builds fall back to the debug key so a fresh clone remains buildable.

CI uses `KEYSTORE_BASE64` to inject the keystore. Without Secrets, GitHub Actions uses a temporary debug key that changes on every run, so a newer APK cannot upgrade an older installation. Configure your own keystore for stable upgrades.

GitHub Actions:

| Workflow | Trigger | Output |
|----------|---------|--------|
| `.github/workflows/native-apk.yml` | Push, PR, or `v*` tag | Native APK; creates a Release for tags |
| `.github/workflows/twa-apk.yml` | Manual dispatch with a domain | TWA APK and `assetlinks.json` |

## Repository layout

```text
app/                    # Native Android client (Compose + Room)
server-patches/         # Today, task projection, and Xiaomi health sync patches
scripts/                # Signing and TWA assetlinks tools
twa/                    # Optional TWA build
.github/workflows/      # APK CI
```

## Current limitations

- Android 8.0 (API 26) or newer is required.
- Conversations, Today, and execution are server-authoritative. Local mirrors remain readable offline, but turns cannot be guaranteed to continue without a network.
- Live Today updates depend on a foreground WebSocket. Vendor push after process death, quiet hours, and a daily notification cap are not fully implemented.
- Morning and evening health reminders use WorkManager, so battery optimization may delay them beyond 07:35 and 19:35.
- The Xiaomi health cloud path is not a stable public API and may stop working. Health Connect and Keep are not currently supported as direct phone-side sources.
- The WebView fallback covers missing native features only when the instance's web interface remains compatible.
- There is no iOS client.

## License

[MIT](LICENSE)
