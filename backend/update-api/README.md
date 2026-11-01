# COSMIC Update API

## Run

```bash
export COSMIC_UPDATE_ADMIN_TOKEN='long-random-secret'
python3 server.py
```

## Public

`GET /api/v1/app/update/android` → update manifest JSON

## Admin (Bearer token)

- `GET /api/v1/admin/releases`
- `POST /api/v1/admin/releases` — publish
- `POST /api/v1/admin/releases/disable` — `{ "version_code": 40 }`

Never put the admin token in the Android app.
