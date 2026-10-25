# Octop Mobile

An Android client for self-hosted [Octop](https://github.com/TencentCloud/Octop). **One APK works with any instance** — install it, enter your own domain, and you get full remote control on your phone: assign tasks, watch tools actually execute, upload attachments. No more read-only Q&A through the Lark bot.

[中文](README.md)

## Why

Two different problems get mixed up when people use Octop from a phone:

| Symptom | Actual cause |
|---------|--------------|
| "It only answers questions; asking it to run something gets refused" | The agent's `conversation_mode` is `"ask"`. Octop's default is `craft`. **This is a server config issue** — see [docs/octop-setup.md](docs/octop-setup.md) |
| "It's just a web page, not an app" | There's no client shell |

This repo solves the second one; the first is documented in [docs/octop-setup.md](docs/octop-setup.md).

## Two ways to use it

| Option | Notes | Best for |
|--------|-------|----------|
| **A. Universal shell** (main) | One APK for every instance; enter your address on first launch | Almost everyone |
| **B. TWA** (secondary) | No address bar, shares Chrome login state — but the host is baked in at build time, so **each domain needs its own APK** | Those wanting a more native feel |

### Option A: universal shell

1. Grab the latest `octop-native.apk` from [Releases](../../releases).
2. Install it (you may need to allow installing from unknown sources).
3. Enter your instance address, e.g. `https://octop.example.com`, and tap **Connect**.
   - `https://` is added automatically and a trailing `/` is stripped
   - It must be deployed at the **domain root**; addresses with a sub-path like `https://example.com/octop` are rejected with a clear message
4. Once verified you land on the main screen — log in as usual.

The address and your session are remembered. Settings lets you change the address, sign out, or enable **Allow plain HTTP** (intranet self-hosting only, off by default).

### Option B: TWA

Requires Node.js 18+ and JDK 17:

```bash
git clone https://github.com/larens/octop-android.git
cd octop-android
# edit twa/twa.config.json and set host to your domain
./twa/scripts/build-twa.sh
```

The script verifies the instance, generates the project, creates a signing key, builds a signed APK, and outputs:

- `twa/build/app-release-signed.apk`
- `build-assetlinks/assetlinks.json` (real SHA-256 fingerprint)
- `build-assetlinks/nginx-location.conf`

Deploy `assetlinks.json` to `https://your-domain/.well-known/assetlinks.json`. See [server setup, section 4](docs/octop-setup.md#4仅-twa-用户配置-assetlinksjson).

> **Careful**: on fingerprint mismatch TWA **silently falls back** to a Custom Tab with an address bar. If you see an address bar after installing, verification failed.

## Server requirements

1. Publicly reachable over **HTTPS** (cleartext HTTP is rejected by default)
2. Nginx must handle **WebSocket and SSE** correctly (`proxy_buffering off` is mandatory, otherwise streaming output stalls then dumps at once)
3. Set the agent's `conversation_mode` to `craft` (otherwise it stays read-only)
4. TWA users only: serve `/.well-known/assetlinks.json`

Full configuration, including copy-pasteable Nginx snippets and SQL, is in **[docs/octop-setup.md](docs/octop-setup.md)**.

## Building it yourself

Requires JDK 17 and Android SDK (`platforms;android-36` + `build-tools;36.0.0`).

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:test                 # unit tests
```

Release signing:

```bash
./scripts/gen-keystore.sh           # creates keystore + keystore.properties (neither is committed)
./gradlew :app:assembleRelease
```

**No keys are stored in this repo.** Without a signing config, release builds fall back to the debug signature so a fresh clone builds out of the box. In CI, inject the signing config via the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` secrets.

> **Installing and upgrading**: without those secrets, CI signs with a throwaway debug key that **changes on every run**, so a newer APK cannot be installed over an older one (you'd have to uninstall first, losing data). To support in-place upgrades, generate your own keystore and add the secrets above. Local builds are unaffected — your `~/.android/debug.keystore` is stable.

## Privacy

No analytics, no telemetry. No network requests beyond the instance address you enter.

## License

[MIT](LICENSE)
