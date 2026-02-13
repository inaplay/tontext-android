import hashlib
import os
import re
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timedelta
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from fastapi.templating import Jinja2Templates

app = FastAPI(title="Tontext Backend")
security = HTTPBasic()
templates = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))

DATABASE_PATH = os.environ.get("DATABASE_PATH", "data/tontext.db")
RELEASES_DIR = os.environ.get("RELEASES_DIR", "releases")
ADMIN_USERNAME = os.environ.get("ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "changeme")


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


def get_latest_version() -> tuple[str, Path] | None:
    releases = Path(RELEASES_DIR)
    if not releases.exists():
        return None
    apks = sorted(releases.glob("tontext-v*.apk"), reverse=True)
    if not apks:
        return None
    match = re.search(r"tontext-v(.+)\.apk", apks[0].name)
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

    version, apk_path = result

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

    return FileResponse(
        path=str(apk_path),
        filename=f"tontext-v{version}.apk",
        media_type="application/vnd.android.package-archive",
    )


@app.get("/api/model/{filename}")
def serve_model(filename: str):
    model_path = Path(RELEASES_DIR) / filename
    if not model_path.exists() or not model_path.name.endswith(".bin"):
        raise HTTPException(status_code=404, detail="Model not found")
    return FileResponse(path=str(model_path), filename=filename)


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
