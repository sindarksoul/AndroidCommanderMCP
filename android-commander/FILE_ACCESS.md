# Read-only Android file tools (experimental)

This branch adds three MCP tools to both the HTTPS relay and the optional local stdio MCP server:

- `android_list_files(path="")`: list up to 100 immediate children of the approved folder.
- `android_file_info(path)`: inspect metadata for a relative file or directory.
- `android_read_file(path)`: read at most 16 KiB of UTF-8 text.

## Permissions and security

Open the Android Commander app and tap **Choose folder for read-only MCP access**. Android's system folder picker grants access only to the selected tree. The app requests read-only, persistable access, and the user approves each MCP request on the phone. No storage-wide permission or write permission is requested. Do not select folders containing secrets or private documents you do not want exposed to your authenticated MCP client. Access can be revoked in Android app settings.

Paths are relative to the selected folder, e.g. `notes/todo.txt`, not absolute Android filesystem paths. The relay rejects traversal and backslashes. Text reading is capped at 16 KiB; binary formats are not supported. Listing is capped at 100 entries.

## Deployment / verification

1. Review and merge this branch after testing. Render must redeploy the updated `android-commander/relay/server.py`.
2. Rebuild and install the Android APK with the updated `MainActivity.kt` and `documentfile` dependency.
3. Reconnect the Android app to the HTTPS relay and select an approved folder.
4. In Colab, reconnect to the MCP endpoint and refresh `session.list_tools()` (the previous `android_tools` list is stale).
5. Call `android_list_files` with `{"path": ""}`, approve on the phone, then try `android_file_info` and `android_read_file` on a small test text file.

The relay uses an in-memory queue and a single process, as before. Both the APK and relay must be upgraded together. This feature has not yet been validated on an Android device or deployed to Render.
