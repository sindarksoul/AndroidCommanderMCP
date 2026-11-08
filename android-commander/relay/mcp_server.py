"""Run this MCP server locally in an MCP-capable client; relay must be reachable over HTTPS."""
import os
import httpx
from mcp.server.fastmcp import FastMCP

mcp=FastMCP("Android Commander")
BASE=os.environ["RELAY_URL"].rstrip("/")
TOKEN=os.environ["CLIENT_TOKEN"]
if not BASE.startswith("https://"):
    raise RuntimeError("RELAY_URL must use HTTPS")

def request(op, args=None):
    with httpx.Client(timeout=85) as client:
        r=client.post(f"{BASE}/client/request/{op}",headers={"Authorization":f"Bearer {TOKEN}"},json=args or {})
        r.raise_for_status()
        return r.json()["result"]

@mcp.tool()
def android_device_info() -> dict:
    """Ask the Android user to approve reading device model and Android version."""
    return request("device_info")

@mcp.tool()
def android_battery_info() -> dict:
    """Ask the Android user to approve reading battery percentage and charging status."""
    return request("battery_info")

@mcp.tool()
def android_storage_info() -> dict:
    """Ask the Android user to approve reading device internal storage capacity and free space."""
    return request("storage_info")

@mcp.tool()
def android_list_files(path: str = "") -> dict:
    """List files in the Android user's selected folder, with phone approval."""
    return request("list_files", {"path": path})

@mcp.tool()
def android_file_info(path: str) -> dict:
    """Read file metadata in the selected Android folder, with phone approval."""
    return request("file_info", {"path": path})

@mcp.tool()
def android_read_file(path: str) -> dict:
    """Read at most 16 KiB of UTF-8 text from the selected Android folder, with phone approval."""
    return request("read_file", {"path": path})

if __name__=="__main__": mcp.run(transport="stdio")
