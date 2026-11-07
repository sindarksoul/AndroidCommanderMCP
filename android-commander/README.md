# Android Commander MCP — v0.1 MVP

Native Kotlin Android client + Python HTTPS relay + local MCP stdio adapter.

## Features
- Device information, battery status and internal storage stats.
- **Every request requires a visible, one-time approval on the phone.**
- Separate device and client bearer tokens.
- No root, ADB, shell, file browsing, or background device surveillance.
- App must remain open and connected; MVP is **not** a background service.

## Build APK without a PC
1. Upload the project contents to a **private** GitHub repository (keep the directory structure).
2. Open **Actions → Build Android APK → Run workflow**.
3. Download the `android-commander-debug-apk` workflow artifact and install its APK on your Android phone.
4. This is a development-signed APK, not a production release. Verify the GitHub repository and workflow before installation.

## Run the relay (on your own HTTPS server)
Use Python 3.11+ and a publicly reachable HTTPS domain with a valid TLS certificate. The Android app refuses plain HTTP.

```bash
cd relay
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export DEVICE_TOKEN="$(python -c 'import secrets;print(secrets.token_urlsafe(48))')"
export CLIENT_TOKEN="$(python -c 'import secrets;print(secrets.token_urlsafe(48))')"
uvicorn server:app --host 127.0.0.1 --port 8765
```

Put a reverse proxy with HTTPS (e.g. Caddy) in front of port 8765; never expose the raw HTTP port publicly. Save both generated tokens securely; these must remain identical across relay restarts. Do not commit tokens to GitHub. For a quick deployment, use a trusted HTTPS reverse proxy/tunnel to localhost:8765. The relay currently keeps requests in RAM, supports one process, and loses outstanding requests on restart.

## Connect Android
Open the APK, enter the public `https://...` relay URL and the **DEVICE_TOKEN**, then tap Connect. Keep the app foregrounded. Approve or deny each request in the dialog.

## Connect an MCP client
Run `relay/mcp_server.py` as a **local stdio MCP server** with environment variables `RELAY_URL=https://your-domain.example` and `CLIENT_TOKEN=<the relay client token>`. It exposes `android_device_info`, `android_battery_info`, `android_storage_info`.

Example direct relay test from a machine with HTTPS access (Android must be connected):
```bash
curl -X POST "https://your-domain.example/client/request/device_info" -H "Authorization: Bearer YOUR_CLIENT_TOKEN"
```

**Important:** A local stdio MCP server is not automatically a ChatGPT connector. ChatGPT integration may require a remotely hosted MCP endpoint and appropriate product support; this MVP's stdio adapter is designed for MCP clients that support local servers. Do not claim ChatGPT connectivity until integration is configured and verified.

## Security limits / next release
- Tokens are stored in Android app-private preferences, **not encrypted at rest**; improve using Android Keystore before production.
- Relay stores outstanding jobs only in memory. No persistence, multi-worker coordination, rate limiting, or audit trail yet.
- Approval is per request, but the v0.1 polling loop can retrieve multiple requests before prior dialogs are answered. Use only with trusted clients.
- Use private infrastructure and long random tokens; protect relay from abusive traffic with a firewall/rate limiting.
- Debug APK is suitable for testing only; no production signing, penetration testing, or security audit has been completed.
- Do not deploy to shared/public production infrastructure without hardening.
