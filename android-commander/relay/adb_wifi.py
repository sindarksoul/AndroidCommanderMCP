"""Local-only, deliberately limited ADB Wi-Fi connection management."""
import ipaddress
import re
import shutil
import subprocess

def _endpoint(value: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9.]+:[0-9]{1,5}", value):
        raise ValueError("Use a numeric IPv4 address and port, e.g. 192.168.1.10:37123")
    address, port_text = value.rsplit(":", 1)
    ip = ipaddress.ip_address(address)
    if ip.version != 4 or not (ip.is_private or ip.is_loopback):
        raise ValueError("Only private/local IPv4 addresses are supported")
    port = int(port_text)
    if not 1 <= port <= 65535:
        raise ValueError("Invalid port")
    return f"{ip}:{port}"

def _adb(*args: str, input_text: str | None = None) -> dict:
    binary = shutil.which("adb")
    if binary is None:
        return {"ok": False, "error": "ADB not found. Install Android SDK Platform-Tools and add adb to PATH."}
    try:
        proc = subprocess.run([binary, *args], input=input_text, text=True,
                              capture_output=True, timeout=20, check=False)
        output = (proc.stdout + proc.stderr).strip()
        return {"ok": proc.returncode == 0, "output": output[:4000]}
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "ADB command timed out"}
    except OSError as exc:
        return {"ok": False, "error": str(exc)}

def pair(endpoint: str, code: str) -> dict:
    endpoint = _endpoint(endpoint)
    if not isinstance(code, str) or not re.fullmatch(r"[0-9]{6}", code):
        raise ValueError("Pairing code must contain exactly six digits")
    # Never include pairing code in command arguments or tool output.
    result = _adb("pair", endpoint, input_text=code + "\n")
    result["output"] = result.get("output", "").replace(code, "[REDACTED]")
    return result

def connect(endpoint: str) -> dict:
    return _adb("connect", _endpoint(endpoint))

def devices() -> dict:
    return _adb("devices", "-l")

def status() -> dict:
    result = devices()
    if result["ok"]:
        lines = result.get("output", "").splitlines()[1:]
        result["devices"] = [{"serial": line.split()[0], "state": line.split()[1]}
                             for line in lines if len(line.split()) >= 2]
    return result

def disconnect(endpoint: str) -> dict:
    return _adb("disconnect", _endpoint(endpoint))
