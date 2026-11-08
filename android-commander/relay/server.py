"""Single-device, single-process MVP relay. Deploy behind HTTPS; no multi-worker replicas."""
import os, secrets, time, uuid, asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI, Header, HTTPException
from starlette.responses import PlainTextResponse
from mcp.server.fastmcp import FastMCP
from pydantic import BaseModel

DEVICE_TOKEN = os.environ.get("DEVICE_TOKEN", "")
CLIENT_TOKEN = os.environ.get("CLIENT_TOKEN", "")
if not DEVICE_TOKEN or not CLIENT_TOKEN or DEVICE_TOKEN == CLIENT_TOKEN:
    raise RuntimeError("Set distinct DEVICE_TOKEN and CLIENT_TOKEN environment variables")
mcp = FastMCP("Android Commander", stateless_http=True, json_response=True, streamable_http_path="/")

@asynccontextmanager
async def lifespan(app):
    async with mcp.session_manager.run():
        yield

app = FastAPI(title="Android Commander Relay", docs_url=None, redoc_url=None,
              openapi_url=None, lifespan=lifespan)
ALLOWED = {"device_info", "battery_info", "storage_info"}
jobs = {}
queue = asyncio.Queue(maxsize=32)

class Result(BaseModel):
    manufacturer: str | None = None
    model: str | None = None
    android_version: str | None = None
    sdk: int | None = None
    percentage: int | None = None
    charging: bool | None = None
    total_bytes: int | None = None
    free_bytes: int | None = None
    error: str | None = None

def auth(header, token):
    if not header or not secrets.compare_digest(header, f"Bearer {token}"):
        raise HTTPException(401, "Unauthorized")

def cleanup():
    now=time.monotonic()
    for key, job in list(jobs.items()):
        if now-job["created"] > 120:
            jobs.pop(key,None)

@app.get("/device/next")
async def device_next(authorization: str | None = Header(default=None)):
    auth(authorization, DEVICE_TOKEN)
    cleanup()
    try:
        while True:
            id = queue.get_nowait()
            if id in jobs:
                return {"id": id, "operation": jobs[id]["operation"]}
    except asyncio.QueueEmpty:
        return {"id": None}

@app.post("/device/result/{id}")
async def device_result(id: str, result: Result, authorization: str | None = Header(default=None)):
    auth(authorization, DEVICE_TOKEN)
    if id not in jobs: raise HTTPException(404, "Unknown or expired request")
    jobs[id]["result"] = result.model_dump(exclude_none=True)
    jobs[id]["event"].set()
    return {"ok":True}

@app.post("/client/request/{operation}")
async def client_request(operation: str, authorization: str | None = Header(default=None)):
    auth(authorization, CLIENT_TOKEN)
    cleanup()
    if operation not in ALLOWED: raise HTTPException(400, "Unsupported operation")
    id=str(uuid.uuid4())
    job={"operation":operation,"created":time.monotonic(),"result":None,"event":asyncio.Event()}
    jobs[id]=job
    try: queue.put_nowait(id)
    except asyncio.QueueFull:
        jobs.pop(id,None)
        raise HTTPException(503,"Request queue full")
    try:
        await asyncio.wait_for(job["event"].wait(), timeout=75)
        return {"id":id,"result":job["result"]}
    except asyncio.TimeoutError:
        raise HTTPException(504,"Timed out waiting for Android approval")
    finally: jobs.pop(id,None)


async def dispatch(operation: str):
    """Queue a request for the already-connected Android app; approval happens on device."""
    cleanup()
    if operation not in ALLOWED:
        raise ValueError("Unsupported operation")
    job_id = str(uuid.uuid4())
    job = {"operation": operation, "created": time.monotonic(),
           "result": None, "event": asyncio.Event()}
    jobs[job_id] = job
    try:
        queue.put_nowait(job_id)
        await asyncio.wait_for(job["event"].wait(), timeout=75)
        return job["result"]
    finally:
        jobs.pop(job_id, None)

@mcp.tool()
async def android_device_info() -> dict:
    """Request Android model, manufacturer and OS version, with phone-side approval."""
    return await dispatch("device_info")

@mcp.tool()
async def android_battery_info() -> dict:
    """Request battery percentage and charging status, with phone-side approval."""
    return await dispatch("battery_info")

@mcp.tool()
async def android_storage_info() -> dict:
    """Request internal storage capacity and free space, with phone-side approval."""
    return await dispatch("storage_info")

class MCPTokenGuard:
    """Restrict all remote MCP HTTP methods to the separate client bearer token."""
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            headers = dict(scope.get("headers", []))
            provided = headers.get(b"authorization", b"")
            expected = ("Bearer " + CLIENT_TOKEN).encode()
            if not secrets.compare_digest(provided, expected):
                response = PlainTextResponse("Unauthorized", status_code=401)
                await response(scope, receive, send)
                return
        await self.app(scope, receive, send)

app.mount("/mcp", MCPTokenGuard(mcp.streamable_http_app()))
