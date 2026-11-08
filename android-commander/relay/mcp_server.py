"""Run this MCP server locally in an MCP-capable client; relay must be reachable over HTTPS."""
import os
import httpx
from mcp.server.fastmcp import FastMCP
import adb_wifi

mcp=FastMCP("Android Commander")
BASE=os.environ["RELAY_URL"].rstrip("/")
TOKEN=os.environ["CLIENT_TOKEN"]
if not BASE.startswith("https://"):
    raise RuntimeError("RELAY_URL must use HTTPS")

def request(op):
    with httpx.Client(timeout=85) as client:
        r=client.post(f"{BASE}/client/request/{op}",headers={"Authorization":f"Bearer {TOKEN}"})
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
def android_adb_pair(endpoint: str, pairing_code: str) -> dict:
    """Pair with a local Android Wireless Debugging endpoint; requires phone's six-digit code."""
    return adb_wifi.pair(endpoint, pairing_code)

@mcp.tool()
def android_adb_connect(endpoint: str) -> dict:
    """Connect to the phone's separate Wireless Debugging connection port on the local network."""
    return adb_wifi.connect(endpoint)

@mcp.tool()
def android_adb_devices() -> dict:
    """List ADB devices connected to the local MCP host."""
    return adb_wifi.devices()

@mcp.tool()
def android_adb_status() -> dict:
    """Return connected ADB device identifiers and states."""
    return adb_wifi.status()

@mcp.tool()
def android_adb_disconnect(endpoint: str) -> dict:
    """Disconnect one local Wi-Fi ADB endpoint."""
    return adb_wifi.disconnect(endpoint)

if __name__=="__main__": mcp.run(transport="stdio")
