-- COSMIC Update API schema
CREATE TABLE IF NOT EXISTS releases (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  app_id TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT 'android',
  latest_version TEXT NOT NULL,
  version_code INTEGER NOT NULL UNIQUE,
  minimum_supported_version INTEGER NOT NULL,
  update_required INTEGER NOT NULL DEFAULT 0,
  release_name TEXT NOT NULL,
  release_notes TEXT NOT NULL, -- JSON array
  download_url TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  published_at TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  rollout_percent INTEGER NOT NULL DEFAULT 100,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS admin_users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_releases_platform ON releases(platform, enabled, version_code);
