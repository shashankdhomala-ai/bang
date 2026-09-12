# BANG production deployment

This deployment uses the Node backend behind Caddy for automatic HTTPS and WebSocket proxying.

## 1. Server requirements

- Linux server with Docker Engine and Docker Compose plugin
- Public DNS record such as `api.example.com` pointing to the server
- TCP ports 80 and 443 open
- Persistent disk for `/data`

Do not expose port 8080 publicly.

## 2. Configure the deployment

From `BANG-v1.2-GitHub-Actions-Test`:

```bash
export BANG_DOMAIN=api.example.com
export ALLOWED_ORIGIN=https://app.example.com
export BANG_SERVER_CODE='replace-with-a-long-random-secret'

docker compose -f docker-compose.production.yml up -d --build
```

Caddy obtains and renews the TLS certificate automatically. The backend is available at:

`https://api.example.com`

Health check:

`https://api.example.com/healthz`

Expected response contains `"ok":true`.

## 3. Important data warning

The current BANG backend uses a JSON database. The Compose volume persists it, but this is suitable for an early production deployment only. Before a large public launch, migrate users/sessions/friends/invites to PostgreSQL or another transactional database and add backups.

## 4. Android release configuration

Create a GitHub repository variable:

- `BANG_API_URL=https://api.example.com`

Never put a real production password, private key, or signing keystore into source control.

## 5. Release signing secrets

Create these GitHub Actions secrets:

- `BANG_KEYSTORE_BASE64`
- `BANG_KEYSTORE_PASSWORD`
- `BANG_KEY_ALIAS`
- `BANG_KEY_PASSWORD`

`BANG_KEYSTORE_BASE64` is the base64 representation of the release `.jks` file.

The release workflow refuses to build unless both the HTTPS API URL and signing secrets are present.

## 6. Build a release AAB

Use GitHub Actions workflow **BANG Release AAB** and provide a semantic version such as `1.1.0` plus an integer version code greater than the previous Play Store version.

The workflow produces a signed:

`app-release.aab`

Do not upload a debug APK to Google Play as the production release.

## 7. Operational security checklist

- Use HTTPS only for public traffic.
- Keep `ALLOWED_ORIGIN` restricted to the real app/web origin.
- Keep Docker port 8080 private.
- Back up `/data/bang-data.json` until the database migration is complete.
- Rotate the server code if it is exposed.
- Monitor CPU, memory, disk, and application logs.
- Do not commit `.env`, keystores, passwords, or tokens.
- Test signup, login, session expiry, friend requests, invites, WebSocket chat, and reconnect behavior on real devices.
