# COSMIC Update API

## GET /api/v1/app/update/android

Returns latest enabled Android release manifest.

```json
{
  "app_id": "com.cosmic.ai",
  "latest_version": "0.4.0",
  "version_code": 40,
  "minimum_supported_version": 30,
  "update_required": true,
  "release_name": "COSMIC AI v0.4",
  "release_notes": ["..."],
  "download_url": "https://...",
  "sha256": "...",
  "published_at": "2026-11-01T12:00:00Z",
  "enabled": true,
  "rollout_percent": 100
}
```

## Admin

Authorization: `Bearer $COSMIC_UPDATE_ADMIN_TOKEN`

- POST `/api/v1/admin/releases` — publish
- POST `/api/v1/admin/releases/disable` — `{ "version_code": 40 }`
- GET `/api/v1/admin/releases` — history

## Client policy

1. Fetch manifest (cached 15m)
2. Validate app_id, sha256 present, enabled
3. If installed < minimum_supported → MANDATORY
4. Else if remote version_code > installed → OPTIONAL or MANDATORY per flag
5. Download → SHA-256 → package name → user installer
