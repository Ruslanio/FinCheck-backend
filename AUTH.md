# Authentication

## Overview

The API uses a short-lived JWT access token paired with a long-lived refresh token.
Passwords are never stored in plaintext. Active sessions are cached in Redis for fast
lookup; the database is the source of truth and requests fall through to it if Redis
is unavailable.

---

## Endpoints

### POST /auth/register

Creates a new user account.

**Request**
```json
{ "email": "user@example.com", "password": "password123" }
```

**Success — 201**
```json
{ "userId": "<uuid>", "email": "user@example.com" }
```

**Errors**

| Status | `error` field              | Condition                     |
|--------|----------------------------|-------------------------------|
| 400    | `invalid_email`            | Email fails regex validation  |
| 400    | `password_too_short`       | Password shorter than 8 chars |
| 409    | `email_already_registered` | Email already in use          |

---

### POST /auth/login

Authenticates an existing user and issues tokens. Writes a session entry to Redis
immediately after issuing the access token.

**Request**
```json
{ "email": "user@example.com", "password": "password123" }
```

**Success — 200**
```json
{
  "accessToken": "<jwt>",
  "refreshToken": "<uuid>",
  "expiresIn": 900
}
```

**Errors**

| Status | `error` field         | Condition                        |
|--------|-----------------------|----------------------------------|
| 401    | `invalid_credentials` | Wrong password or unknown email  |

Wrong password and unknown email return the same error — the response must not
reveal which field is wrong.

---

### POST /auth/refresh

Rotates the refresh token atomically: the old token is revoked and a new one is
inserted in a single database transaction. Returns a new access token and a new
refresh token. The old refresh token cannot be used again.

**Request**
```json
{ "refreshToken": "<uuid>" }
```

**Success — 200** — same shape as login response
```json
{
  "accessToken": "<new-jwt>",
  "refreshToken": "<new-uuid>",
  "expiresIn": 900
}
```

**Errors**

| Status | `error` field           | Condition                              |
|--------|-------------------------|----------------------------------------|
| 401    | `invalid_refresh_token` | Token not found in DB                  |
| 401    | `token_revoked`         | Token was already used or revoked      |
| 401    | `token_expired`         | Token's 30-day lifetime has passed     |

---

### POST /auth/logout

Revokes the refresh token and deletes the Redis session entry for the access token.

**Request**
```json
{
  "refreshToken": "<uuid>",
  "accessToken": "<jwt>"
}
```

Both fields are required. `accessToken` is needed to remove the session cache entry.

**Success — 204** — no body

**Errors**

| Status | `error` field   | Condition                                  |
|--------|-----------------|--------------------------------------------|
| 401    | `token_revoked` | Token already revoked or not found in DB   |

Both "already revoked" and "never existed" return the same error to avoid revealing
token existence.

---

## Token details

### Access token

- Format: signed JWT (HS256)
- Lifetime: 15 minutes (900 seconds)
- Claims: `sub` = userId (UUID string), `iat`, `exp`
- Secret: read from `JWT_SECRET` environment variable at runtime

### Refresh token

- The raw UUID is returned to the client once and never stored.
- A SHA-256 hex digest of the UUID is stored in the `refresh_tokens` table.
- Lifetime: 30 days from issuance.
- Rotation is atomic: revoke + insert happen in one DB transaction with no window
  where both old and new tokens are valid simultaneously.

---

## Redis session cache

After a successful login, a session entry is written to Redis:

- **Key**: `session:{rawAccessToken}`
- **Type**: HSET with fields `userId`, `email`, `createdAt` (ISO-8601 string)
- **TTL**: 900 seconds, sliding — reset on every read (Task 11 middleware) and on
  every new write (new login)

The session cache is a performance layer only. If Redis is unreachable:
- Login and logout still succeed — Redis failures are logged at WARN and swallowed.
- Session lookups fall through to PostgreSQL (Task 11 middleware handles the fallback).
- The application never crashes or returns 5xx due to a Redis outage.

On logout, `DEL session:{accessToken}` is called. If Redis is down the key will
expire naturally after 900 seconds.

---

## Password storage

BCrypt with cost factor 12. The hash is computed on `Dispatchers.Default`
(CPU-bound) outside the database transaction so no connection is held during
hashing (~300 ms).

---

## Local dev test accounts

These accounts exist in the local Docker Postgres volume (`pg_data`).
They were created during development and are **not seeded automatically** —
if you wipe the volume (`docker compose down -v`) you will need to re-register.

| Email               | Password    | User ID                                |
|---------------------|-------------|----------------------------------------|
| test@example.com    | password123 | 76b52f2c-14b0-4f8f-87dd-4878c25b20ca  |
| verify@example.com  | password123 | 5d37b405-cfaf-4dc1-9e6e-ac1989d5cd39  |

To register a new account locally:

```bash
curl -s -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"yourpassword"}' | python3 -m json.tool
```

---

## Environment variables

| Variable      | Required | Description                                                                 |
|---------------|----------|-----------------------------------------------------------------------------|
| `JWT_SECRET`  | Yes      | HS256 signing key — minimum 32 characters (256 bits). Never commit a real value. |
| `REDIS_HOST`  | No       | Redis hostname. Defaults to `localhost`.                                    |
| `REDIS_PORT`  | No       | Redis port. Defaults to `6379`.                                             |

Local dev values are in `.env` (gitignored). CI uses throwaway values set in
`.github/workflows/backend-ci.yml`.
