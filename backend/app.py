import hashlib
import io
import os
import re
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timedelta
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from fastapi.templating import Jinja2Templates
from minio import Minio

app = FastAPI(title="Tontext Backend")
security = HTTPBasic()
templates = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))

DATABASE_PATH = os.environ.get("DATABASE_PATH", "data/tontext.db")
ADMIN_USERNAME = os.environ.get("ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "changeme")

MINIO_ENDPOINT = os.environ.get("MINIO_ENDPOINT", "minio:9000")
MINIO_ROOT_USER = os.environ.get("MINIO_ROOT_USER", "minioadmin")
MINIO_ROOT_PASSWORD = os.environ.get("MINIO_ROOT_PASSWORD", "minioadmin")
MINIO_BUCKET = os.environ.get("MINIO_BUCKET", "tontext")
STORAGE_BASE_URL = os.environ.get("STORAGE_BASE_URL", "/storage")

minio_client = Minio(
    MINIO_ENDPOINT,
    access_key=MINIO_ROOT_USER,
    secret_key=MINIO_ROOT_PASSWORD,
    secure=False,
)


def ensure_bucket():
    if not minio_client.bucket_exists(MINIO_BUCKET):
        minio_client.make_bucket(MINIO_BUCKET)
        policy = {
            "Version": "2012-10-17",
            "Statement": [{
                "Effect": "Allow",
                "Principal": {"AWS": ["*"]},
                "Action": ["s3:GetObject"],
                "Resource": [f"arn:aws:s3:::{MINIO_BUCKET}/*"],
            }],
        }
        import json
        minio_client.set_bucket_policy(MINIO_BUCKET, json.dumps(policy))


def init_db():
    os.makedirs(os.path.dirname(DATABASE_PATH) or ".", exist_ok=True)
    with get_db() as db:
        db.execute("""
            CREATE TABLE IF NOT EXISTS downloads (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                version TEXT NOT NULL,
                downloaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                ip_hash TEXT,
                user_agent TEXT
            )
        """)


@contextmanager
def get_db():
    conn = sqlite3.connect(DATABASE_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def get_latest_version() -> tuple[str, str] | None:
    """Returns (version_string, object_name) or None."""
    objects = list(minio_client.list_objects(MINIO_BUCKET, prefix="releases/tontext-v"))
    apks = sorted(
        [obj.object_name for obj in objects if obj.object_name.endswith(".apk")],
        reverse=True,
    )
    if not apks:
        return None
    match = re.search(r"tontext-v(.+)\.apk", apks[0])
    if not match:
        return None
    return match.group(1), apks[0]


def verify_admin(credentials: HTTPBasicCredentials = Depends(security)):
    if credentials.username != ADMIN_USERNAME or credentials.password != ADMIN_PASSWORD:
        raise HTTPException(status_code=401, headers={"WWW-Authenticate": "Basic"})
    return credentials


@app.on_event("startup")
def startup():
    init_db()
    ensure_bucket()


@app.get("/api/version")
def api_version():
    result = get_latest_version()
    if not result:
        return JSONResponse({"version": "0.0.0", "available": False})
    version, _ = result
    return JSONResponse({"version": version, "available": True})


@app.get("/api/download/latest")
def download_latest(request: Request):
    result = get_latest_version()
    if not result:
        raise HTTPException(status_code=404, detail="No release available")

    version, object_name = result

    # Record download
    ip_hash = hashlib.sha256(
        (request.client.host or "unknown").encode()
    ).hexdigest()[:16]
    user_agent = request.headers.get("user-agent", "")

    with get_db() as db:
        db.execute(
            "INSERT INTO downloads (version, ip_hash, user_agent) VALUES (?, ?, ?)",
            (version, ip_hash, user_agent),
        )

    # Redirect to nginx-proxied MinIO URL for direct download
    return RedirectResponse(
        url=f"{STORAGE_BASE_URL}/{object_name}",
        status_code=302,
    )


@app.get("/api/model/{filename}")
def serve_model(filename: str):
    if not filename.endswith(".bin"):
        raise HTTPException(status_code=404, detail="Model not found")
    object_name = f"models/{filename}"
    try:
        minio_client.stat_object(MINIO_BUCKET, object_name)
    except Exception:
        raise HTTPException(status_code=404, detail="Model not found")
    return RedirectResponse(
        url=f"{STORAGE_BASE_URL}/{object_name}",
        status_code=302,
    )


@app.post("/api/releases/upload")
def upload_release(
    file: UploadFile,
    credentials: HTTPBasicCredentials = Depends(verify_admin),
):
    if not file.filename or not file.filename.endswith(".apk"):
        raise HTTPException(status_code=400, detail="File must be an .apk")
    if not re.match(r"tontext-v.+\.apk", file.filename):
        raise HTTPException(status_code=400, detail="Filename must match tontext-v*.apk")

    object_name = f"releases/{file.filename}"
    data = file.file.read()
    minio_client.put_object(
        MINIO_BUCKET,
        object_name,
        io.BytesIO(data),
        length=len(data),
        content_type="application/vnd.android.package-archive",
    )

    match = re.search(r"tontext-v(.+)\.apk", file.filename)
    version = match.group(1) if match else file.filename

    return JSONResponse({"status": "ok", "version": version, "object": object_name})


@app.get("/api/stats")
def api_stats(credentials: HTTPBasicCredentials = Depends(verify_admin)):
    with get_db() as db:
        total = db.execute("SELECT COUNT(*) as c FROM downloads").fetchone()["c"]
        today = db.execute(
            "SELECT COUNT(*) as c FROM downloads WHERE date(downloaded_at) = date('now')"
        ).fetchone()["c"]
        unique_ips = db.execute(
            "SELECT COUNT(DISTINCT ip_hash) as c FROM downloads"
        ).fetchone()["c"]

        per_version = db.execute("""
            SELECT version,
                   COUNT(*) as count,
                   MIN(downloaded_at) as first_seen,
                   MAX(downloaded_at) as last_seen
            FROM downloads
            GROUP BY version
            ORDER BY first_seen DESC
        """).fetchall()

        # Last 30 days
        thirty_days_ago = (datetime.now() - timedelta(days=30)).strftime("%Y-%m-%d")
        daily = db.execute("""
            SELECT date(downloaded_at) as day, COUNT(*) as count
            FROM downloads
            WHERE date(downloaded_at) >= ?
            GROUP BY date(downloaded_at)
            ORDER BY day DESC
        """, (thirty_days_ago,)).fetchall()

    return JSONResponse({
        "total": total,
        "today": today,
        "unique_ips": unique_ips,
        "per_version": [dict(row) for row in per_version],
        "daily": [dict(row) for row in daily],
    })


@app.get("/admin", response_class=HTMLResponse)
def admin_page(request: Request, credentials: HTTPBasicCredentials = Depends(verify_admin)):
    with get_db() as db:
        total = db.execute("SELECT COUNT(*) as c FROM downloads").fetchone()["c"]
        today = db.execute(
            "SELECT COUNT(*) as c FROM downloads WHERE date(downloaded_at) = date('now')"
        ).fetchone()["c"]
        unique_ips = db.execute(
            "SELECT COUNT(DISTINCT ip_hash) as c FROM downloads"
        ).fetchone()["c"]

        per_version = db.execute("""
            SELECT version,
                   COUNT(*) as count,
                   MIN(downloaded_at) as first_seen,
                   MAX(downloaded_at) as last_seen
            FROM downloads
            GROUP BY version
            ORDER BY first_seen DESC
        """).fetchall()

        thirty_days_ago = (datetime.now() - timedelta(days=30)).strftime("%Y-%m-%d")
        daily = db.execute("""
            SELECT date(downloaded_at) as day, COUNT(*) as count
            FROM downloads
            WHERE date(downloaded_at) >= ?
            GROUP BY date(downloaded_at)
            ORDER BY day DESC
        """, (thirty_days_ago,)).fetchall()

        max_daily = max((row["count"] for row in daily), default=1)

    return templates.TemplateResponse("admin.html", {
        "request": request,
        "total": total,
        "today": today,
        "unique_ips": unique_ips,
        "per_version": per_version,
        "daily": daily,
        "max_daily": max_daily,
    })
