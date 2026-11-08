# Android Commander MCP — Read-only Android control (experimental)

Native Kotlin Android client, Python HTTPS relay, and MCP interfaces for approved Android requests.

## Available tools

| MCP tool | Function |
|---|---|
| `android_device_info` | Android device details |
| `android_battery_info` | Battery status |
| `android_storage_info` | Internal storage statistics |
| `android_list_files(path="")` | List up to 100 immediate entries in the approved folder |
| `android_file_info(path)` | File/folder metadata |
| `android_read_file(path)` | Read up to 16 KiB of UTF-8 text |

The three file tools have been tested end-to-end with a Vivo Android phone, a Render HTTPS relay, and a Google Colab MCP client. The original device tools were previously tested.

## Security model

- Every command requires a visible, one-time approval on the phone.
- File access is restricted to the directory selected through Android's Storage Access Framework folder picker.
- File paths are relative to that approved directory. No absolute paths or traversal outside it.
- Read-only: no file modification, deletion, shell execution, or background surveillance.
- Distinct `DEVICE_TOKEN` and `CLIENT_TOKEN` bearer credentials. Never commit or paste secrets into public logs.
- App must remain open and connected; this experimental MVP is not a background service.

## Build locally on Windows (without GitHub Actions)

Requires Android SDK, JDK 17, and Gradle 8.11.1. From `android-commander/`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
& 'C:\Users\Admin\AppData\Local\Android\DevTools\gradle-8.11.1\bin\gradle.bat' :app:assembleDebug --no-daemon --console=plain
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`. The debug APK is for testing only.

## Run the relay

Python 3.11+ and an HTTPS deployment are required. For a local deployment:

```bash
cd relay
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export DEVICE_TOKEN="$(python -c 'import secrets;print(secrets.token_urlsafe(48))')"
export CLIENT_TOKEN="$(python -c 'import secrets;print(secrets.token_urlsafe(48))')"
uvicorn server:app --host 127.0.0.1 --port 8765
```

For a hosted deployment, configure the same environment variables securely in the hosting dashboard and expose the service over HTTPS. The relay uses an in-memory queue and is designed for a single process; pending requests can be lost on restart.

## Connect Android

1. Open the APK and enter the HTTPS relay **base URL** (do not append `/mcp`).
2. Enter the `DEVICE_TOKEN`.
3. Tap **Choose folder for read-only MCP access** and select only a folder you are comfortable sharing with authenticated clients.
4. Tap **Connect**, keep the app open, and approve each incoming request individually.

## Connect an MCP client

Use the HTTPS MCP endpoint `https://YOUR-RELAY/mcp` with header `Authorization: Bearer YOUR_CLIENT_TOKEN`.

Alternatively run `relay/mcp_server.py` as a local stdio MCP adapter with `RELAY_URL` and `CLIENT_TOKEN` environment variables.

For Python clients using `mcp` and `httpx`:

```python
import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

async with httpx.AsyncClient(
    headers={"Authorization": f"Bearer {CLIENT_TOKEN}"}, timeout=90
) as http_client:
    async with streamable_http_client(MCP_URL, http_client=http_client) as (read, write, _):
        async with ClientSession(read, write) as session:
            await session.initialize()
            print([tool.name for tool in (await session.list_tools()).tools])
            result = await session.call_tool("android_list_files", {"path": ""})
            print(result)
```

This does not automatically connect ChatGPT or Qwen; each client must be configured separately.

## Known limitations and hardening backlog

- Debug APK only; no production signing or independent security audit.
- Android token storage currently uses app-private preferences rather than Android Keystore.
- Relay lacks persistent queues, rate limiting, and an audit log.
- Phone approval dialogs may overlap when multiple requests arrive.
- Keep the service private to trusted users and rotate any exposed tokens.

See [FILE_ACCESS.md](FILE_ACCESS.md) for file-access details.
