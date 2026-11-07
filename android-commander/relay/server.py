"""Single-device, single-process MVP relay. Deploy behind HTTPS; no multi-worker replicas."""
import os, secrets, time, uuid, asyncio
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

DEVICE_TOKEN = os.environ.get("DEVICE_TOKEN", "")
CLIENT_TOKEN = os.environ.get("CLIENT_TOKEN", "")
if not DEVICE_TOKEN or not CLIENT_TOKEN or DEVICE_TOKEN == CLIENT_TOKEN:
    raise RuntimeError("Set distinct DEVICE_TOKEN and CLIENT_TOKEN environment variables")
app = FastAPI(title="Android Commander Relay", docs_url=None, redoc_url=None, openapi_url=None)
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
