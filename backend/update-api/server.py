#!/usr/bin/env python3
"""COSMIC AI Update API — minimal production-shaped server.
Run: python server.py
Env: COSMIC_UPDATE_ADMIN_TOKEN (required for publish)
Never embed this token in the Android APK.
"""
from __future__ import annotations

import hashlib
import json
import os
import sqlite3
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent
DB = ROOT / "releases.db"
APK_DIR = ROOT / "apks"
APK_DIR.mkdir(exist_ok=True)

ADMIN_TOKEN = os.environ.get("COSMIC_UPDATE_ADMIN_TOKEN", "")


def db():
    conn = sqlite3.connect(DB)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    schema = (ROOT / "schema.sql").read_text()
    with db() as c:
        c.executescript(schema)
        # seed example if empty
        n = c.execute("SELECT COUNT(*) AS n FROM releases").fetchone()["n"]
        if n == 0:
            c.execute(
                """INSERT INTO releases
                (app_id, platform, latest_version, version_code, minimum_supported_version,
                 update_required, release_name, release_notes, download_url, sha256, published_at, enabled)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,1)""",
                (
                    "com.cosmic.ai",
                    "android",
                    "0.3.0",
                    30,
                    1,
                    0,
                    "COSMIC AI v0.3",
                    json.dumps(["Foundation release", "Agent runtime", "MCP support"]),
                    "https://example.com/releases/cosmic-ai-v0.3.apk",
                    "0" * 64,
                    datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                ),
            )


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))

    def _json(self, code: int, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/v1/app/update/android":
            with db() as c:
                row = c.execute(
                    """SELECT * FROM releases
                       WHERE platform='android' AND enabled=1
                       ORDER BY version_code DESC LIMIT 1"""
                ).fetchone()
            if not row:
                return self._json(404, {"error": "no release"})
            notes = json.loads(row["release_notes"])
            return self._json(
                200,
                {
                    "app_id": row["app_id"],
                    "latest_version": row["latest_version"],
                    "version_code": row["version_code"],
                    "minimum_supported_version": row["minimum_supported_version"],
                    "update_required": bool(row["update_required"]),
                    "release_name": row["release_name"],
                    "release_notes": notes,
                    "download_url": row["download_url"],
                    "sha256": row["sha256"],
                    "published_at": row["published_at"],
                    "enabled": bool(row["enabled"]),
                    "rollout_percent": row["rollout_percent"],
                },
            )
        if path == "/api/v1/admin/releases":
            if not self._auth():
                return self._json(401, {"error": "unauthorized"})
            with db() as c:
                rows = c.execute("SELECT * FROM releases ORDER BY version_code DESC").fetchall()
            return self._json(200, [dict(r) for r in rows])
        if path == "/health":
            return self._json(200, {"ok": True})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            data = json.loads(raw.decode() or "{}")
        except json.JSONDecodeError:
            return self._json(400, {"error": "invalid json"})

        if path == "/api/v1/admin/releases":
            if not self._auth():
                return self._json(401, {"error": "unauthorized"})
            required = [
                "app_id",
                "latest_version",
                "version_code",
                "minimum_supported_version",
                "download_url",
                "sha256",
            ]
            for k in required:
                if k not in data:
                    return self._json(400, {"error": f"missing {k}"})
            notes = data.get("release_notes", [])
            with db() as c:
                c.execute(
                    """INSERT INTO releases
                    (app_id, platform, latest_version, version_code, minimum_supported_version,
                     update_required, release_name, release_notes, download_url, sha256, published_at,
                     enabled, rollout_percent)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (
                        data["app_id"],
                        data.get("platform", "android"),
                        data["latest_version"],
                        int(data["version_code"]),
                        int(data["minimum_supported_version"]),
                        1 if data.get("update_required") else 0,
                        data.get("release_name", data["latest_version"]),
                        json.dumps(notes),
                        data["download_url"],
                        data["sha256"].lower(),
                        data.get("published_at")
                        or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                        1 if data.get("enabled", True) else 0,
                        int(data.get("rollout_percent", 100)),
                    ),
                )
            return self._json(201, {"ok": True})

        if path == "/api/v1/admin/releases/disable":
            if not self._auth():
                return self._json(401, {"error": "unauthorized"})
            vc = int(data.get("version_code", -1))
            with db() as c:
                c.execute("UPDATE releases SET enabled=0 WHERE version_code=?", (vc,))
            return self._json(200, {"ok": True})

        self._json(404, {"error": "not found"})

    def _auth(self) -> bool:
        if not ADMIN_TOKEN:
            return False
        auth = self.headers.get("Authorization", "")
        return auth == f"Bearer {ADMIN_TOKEN}"


def main():
    init_db()
    host, port = "0.0.0.0", int(os.environ.get("PORT", "8080"))
    print(f"COSMIC Update API on http://{host}:{port}")
    if not ADMIN_TOKEN:
        print("WARNING: COSMIC_UPDATE_ADMIN_TOKEN not set — admin routes disabled")
    ThreadingHTTPServer((host, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
