# TunnelView – Webpages with app experience

TunnelView is a SSH tunneling Webview Android app to open websites safely, even when the server only lives inside a VPN or LAN. It does this by establishing an SSH tunnel (via [SSHJ](https://github.com/hierynomus/sshj)), exposing the internal service on `127.0.0.1`, and presenting it inside a hardened WebView with offline snapshots, diagnostics, and an optional white‑label app builder.

## Highlights

- **Always-on SSH forwarding** – `TunnelService` + `TunnelManager` keep a local port open (default `8090`) and reconnect with smart backoff, IPv4 forcing, host-key pinning, and stateful notifications.
- **Dynamic endpoints** – The app listens to `ntfy.sh` topics over SSE/WebSockets (`NtfySseService`) and periodically syncs a fallback host:port from HTTP or private Git (`EndpointSyncWorker` + `GitEndpointFetcher`), so technicians can rotate tunnels remotely.
- **Resilient browsing experience** – `MainCoordinator` toggles between direct LAN access and the tunnel, caches full HTML snapshots for offline mode, keeps uploads/downloads working, and exposes quick troubleshooting actions.
- **Material 3 settings & diagnostics** – Compose screens (`SettingsScreen`, `ConnectionDiagnosticsActivity`) expose every knob: ntfy topics, SSH keys/passwords, host fingerprints, force IPv4, localized UI (English/PT-BR), and real-time connection logs.
- **Embedded white‑label builder** – `TemplateAppBuilder` repackages a base APK, swaps icons/manifest ids/default secrets, and signs the result (either with an auto-generated key or a custom PEM pair) so each branch can get its own branded tunnel app directly from Settings.

## System Overview

| Layer                  | Responsibilities                                                                                                                     | Key files                                                                                                                  |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------- |
| UI (WebView + Compose) | Hosts the remote cashier page, exposes menus (Settings, ntfy, Diagnostics), handles file pickers, offline banners, and localization. | `MainActivity`, `ui/main/MainCoordinator.kt`, `ui/settings/SettingsScreen.kt`, `ui/debug/ConnectionDiagnosticsActivity.kt` |
| Connectivity & tunnel  | Observes network, manages SSH lifecycle, renders notifications, and logs connection events.                                          | `TunnelService.kt`, `ssh/SshClient.kt`, `ssh/TunnelManager.kt`, `logging/ConnLogger.kt`, `network/ConnectivityObserver.kt` |
| Endpoint orchestration | Decides which `tcp://host:port` to use (manual, ntfy, HTTP fallback, Git repo) and keeps it synced in `ProxyRepository`.             | `data/ProxyRepository.kt`, `network/NtfySubscriber.kt`, `work/EndpointSyncWorker.kt`, `network/GitEndpointFetcher.kt`      |
| Background services    | Foreground services for the tunnel and ntfy SSE plus WorkManager jobs for fallbacks.                                                 | `TunnelService.kt`, `NtfySseService.kt`, `EndpointSyncWorker.kt`                                                           |
| Security & storage     | Default secrets via `.env`/`app_defaults.json`, encrypted preferences, PEM handling, host fingerprinting.                            | `AppDefaults.kt`, `storage/CredentialsStore.kt`, `Prefs.kt`, `app/src/main/res/raw/id_ed25519*`                            |
| App builder            | Repackages the base template APK (generated from the `template` build type) with new defaults, icons, and signing credentials.       | `appbuilder/TemplateAppBuilder.kt`, `appbuilder/ManifestPatcher.kt`, Gradle task `packageBaseTemplateApk`                  |

## Repository Tour

- `app/src/main/java/com/zahah/tunnelview/` – Application wiring (`TunnelApplication`, `MainActivity`, `SettingsActivity`, `Prefs`, `AppLocaleManager`, etc.).
- `app/src/main/java/com/zahah/tunnelview/ssh/` – SSH tunnel orchestration and the `SshClient` wrapper around SSHJ.
- `app/src/main/java/com/zahah/tunnelview/network/` – `ntfy` subscriber, raw/Git fallback fetchers, connectivity observer.
- `app/src/main/java/com/zahah/tunnelview/data/` – Endpoint models, repository, and status flow.
- `app/src/main/java/com/zahah/tunnelview/ui/` – WebView coordinator, Compose settings, diagnostics screen.
- `app/src/main/java/com/zahah/tunnelview/appbuilder/` – Runtime APK builder invoked from Settings.
- `app/src/main/res/` – Layouts, Material theming, localized strings (English + Brazilian/Portuguese), and raw PEM placeholders (`id_ed25519`, `id_ed25519_git`).
- `.env.example` – Template for build-time defaults injected into `BuildConfig`.

## Requirements

- Android Studio Ladybug / Koala (AGP 8.9.0) with JDK 17.
- Android SDK 35 (compile/target) and minSdk 26 device/emulator with Play Store or ADB sideload enabled.
- Access to the SSH bastion/ngrok/etc. that exposes the target intranet host.
- Optional: Git repo with a file that publishes the latest `tcp://host:port` fallback, and an SSH deploy key able to read it.

## Getting Started

1. **Clone & install dependencies**
   ```bash
   git clone <repo-url>
   cd tuneelview
   ./gradlew --version   # verifies Java/Gradle toolchain
   ```
2. **Create `.env` (build-time defaults)**  
   Copy `.env.example` and fill in as needed. These values are baked into `BuildConfig` and surfaced by `AppDefaultsProvider` when the user has not customized settings yet.

   | Key                                               | Description                                                                                                                |
   | ------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
   | `DEFAULT_SSH_PRIVATE_KEY_PATH`                    | Path to a PEM file (relative to repo) containing the default tunnel key. Shipping keys live under `app/src/main/res/raw/`. |
   | `DEFAULT_GIT_PRIVATE_KEY_PATH`                    | PEM to reach the fallback Git repo.                                                                                        |
   | `DEFAULT_INTERNAL_HOST` / `DEFAULT_INTERNAL_PORT` | Internal web host/port the tunnel forwards to (e.g. `192.168.0.7:3000`).                                                   |
   | `DEFAULT_SSH_USER`                                | Default Linux user for the bastion.                                                                                        |
   | `DEFAULT_GIT_REPO_URL` / `DEFAULT_GIT_FILE_PATH`  | Optional Git repo + file that holds the latest endpoint payload.                                                           |

3. **Provide PEM files (never commit production keys)**

   - Drop placeholder keys in `app/src/main/res/raw/id_ed25519` and `id_ed25519_git` **or** point the `.env` paths at files outside the repo.
   - Keys are loaded at runtime via `AppDefaultsProvider` and can later be overridden inside Settings (stored securely in `EncryptedSharedPreferences` by `CredentialsStore`).

4. **Optional: ship a pre-filled `app_defaults.json`**  
   Place `app/src/main/assets/app_defaults.json` with the same fields as `AppDefaults` to override `.env` without rebuilding (useful when the App Builder generates a new APK).

5. **Build & install**
   ```bash
   ./gradlew clean assembleDebug      # compiles the app
   ./gradlew installDebug       # pushes to a connected device
   ```

## Runtime Behavior & Features

### WebView + Offline UX

- `MainCoordinator` owns the single WebView, toolbar, offline banner, and floating "offline info" action. It applies the hardened defaults from `WebViewConfigurator`.
- Each navigation cycle tries a **direct LAN URL first**, then falls back to the SSH tunnel URL (`http://127.0.0.1:<localPort>`). When both fail, the last good HTML snapshot stored under `files/offline-cache/` is displayed and users can force-refresh or open Settings.
- File uploads/downloads, geolocation prompts, and notification permission requests are delegated through `ActivityResultContracts`, so most modern cashier pages behave like Chrome Custom Tabs.

### SSH Tunnel Lifecycle

- `TunnelService` is a foreground service that keeps the tunnel alive, exposes status notifications, and dispatches intents (`START`, `RECONNECT`, `STOP`).
- `TunnelManager` orchestrates retries, exponential backoff, host fingerprint validation, keep-alives, and toggling between `Connected`, `WaitingForEndpoint`, `WaitingForNetwork`, and `LocalBypass` modes.
- `SshClient` configures SSHJ with Ed25519/RSA keys, password fallback, IPv4-only mode, and optional strict host-key verification via SHA256 fingerprints.
- Connection events stream into `ConnLogger`, which keeps a ring buffer + JSONL file for the diagnostics UI and for export when troubleshooting.

### Dynamic Endpoints (ntfy + fallbacks + manual)

- **ntfy SSE/WebSocket** – `NtfySseService` listens to a topic (or a direct `wss://` endpoint) and feeds raw payloads into `NtfySubscriber`, which in turn updates `ProxyRepository`. Payloads can be simple `host:port`, `tcp://` strings, or JSON envelopes (parsed by `EndpointPayloadParser`).
- **HTTP fallback** – Configure a URL plus bearer/access token inside Settings → Remote Updates. `EndpointSyncWorker` fetches the first non-empty line, validates it, and publishes it when ntfy is unavailable.
- **Git fallback** – When a Git repo is set, the worker first tries `raw.githubusercontent.com` and, if needed, clones the repo over SSH (using the bundled git key) to read the target file.
- **Manual overrides** – Users can pin a host/port + SSH bastion in Settings; `ProxyRepository` honors the manual choice until a grace period passes.

### Background workers & notifications

- WorkManager schedules `EndpointSyncWorker` every 15 minutes while the app is foregrounded, ensuring fallbacks stay fresh without draining the battery.
- Foreground notifications exist for both the tunnel and ntfy SSE (Android 13+ users must accept notification permission first). The persistent notification can be disabled in Settings if you prefer one-shot connections.

### App Builder

- Accessible under **Settings → App Builder**. You can define a new app name, package id, default hosts/keys, and upload a foreground icon.
- Behind the scenes `TemplateAppBuilder` unpacks `build/generated/baseTemplate/base_template_apk.tar`, patches the manifest/resources (`ManifestPatcher`), replaces `app_defaults.json`, signs the APK (either with an auto-generated RSA pair or your own PEMs), and saves it to the system Downloads folder via `FileProvider`.
- After code changes, regenerate the base template by running `./gradlew assembleTemplate packageBaseTemplateApk` so the builder works with the latest binaries.

### Diagnostics, logging, and localization

- Use the toolbar menu → **Diagnóstico** to open `ConnectionDiagnosticsActivity` where you can inspect the latest endpoint, tunnel state, recent `ConnLogger` events, and even run an on-device handshake test.
- Settings are localized (English and Brazilian Portuguese) and can force the UI language without changing system locales (`AppLocaleManager`).
- Advanced switches include caching the last page, forcing IPv4, toggling persistent notifications, enabling verbose connection logs, automatic settings saving, and adjusting SSH connect/read/keepalive timeouts.

### Security considerations

- Sensitive defaults never live in source code: `.env` is git-ignored, PEM files under `app/src/main/res/raw` are placeholders, and runtime overrides are stored with `EncryptedSharedPreferences`.
- Keys loaded from `.env`/assets normalize newlines before use, so you can paste PEM blocks directly into environment files when building template APKs.
- When strict host-key mode is enabled, `SshClient` requires a SHA256 fingerprint (configurable in Settings) and aborts if it changes.

## Some useful tips

### Acessing app config using the top bar
- If you fast touch screen 2 times in any part of the screen while loading page, it will show the top bar for configuring the app. If the page loads faster than you to tap screen two times, you can touch 2 times in the tiny part of the top bar that appears below device notification bar (if you aren't successful, rotate screen to landscape and try again).

## Build & Test Workflows

- **Assemble / install**
  ```bash
  ./gradlew assembleDebug installDebug
  ```
- **White-label template refresh**
  ```bash
  ./gradlew assembleTemplate packageBaseTemplateApk
  ```
- **Unit tests** (includes `EndpointPayloadParserTest` and OkHttp/MockWebServer-powered tests)
  ```bash
  ./gradlew test
  ```
- **Static analysis / lint**
  ```bash
  ./gradlew lintVitalRelease
  ./gradlew ktlintCheck # if you add ktlint locally
  ```

## Troubleshooting

- **Tunnel never leaves “Waiting for endpoint”** – Confirm the ntfy topic or fallback URL in Settings and check the diagnostics screen for the last payload. `ProxyRepository` logs every applied endpoint; look under `storage/connection_events.json`.
- **Foreground service stopped immediately** – On Android 13+, make sure the `POST_NOTIFICATIONS` permission dialog was accepted; otherwise the OS will kill `TunnelService`.
- **Git fallback fails with auth errors** – Validate the PEM at `DEFAULT_GIT_PRIVATE_KEY_PATH` (or the value stored in Settings) and confirm the repo URL uses the `git@` syntax so SSH keys take effect.
- **Direct LAN keeps winning over tunnel** – Disable “Use manual endpoint” in Settings or clear `local LAN host/port` if you want to force tunnel traffic.
- **Offline snapshot outdated** – Toggle “Cache last page” to refresh automatically, or tap the floating offline info button and hit “Force reload” once connectivity returns.
- **App Builder missing base template** – Re-run `./gradlew assembleTemplate packageBaseTemplateApk` so `build/generated/baseTemplate/base_template_apk.tar` exists before opening the builder UI.

## Contributing & Next Steps

- Keep `.env`, `local.properties`, and real PEM keys out of Git history.
- When adding new background behavior, wire it through `TunnelManager`/`ProxyRepository` so diagnostics stay accurate.
- Extend `EndpointPayloadParser` + its tests if your ntfy payload format evolves.
- Consider adding instrumentation tests for the WebView coordinator whenever you tweak offline caching or navigation heuristics.

Happy tunneling! 🛰️
