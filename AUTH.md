# Authentication

## Overview

The API uses a short-lived JWT access token paired with a long-lived refresh token.
Passwords are never stored in plaintext.

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

Authenticates an existing user and issues tokens.

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

| Status | `error` field        | Condition                           |
|--------|----------------------|-------------------------------------|
| 401    | `invalid_credentials`| Wrong password or unknown email     |

Wrong password and unknown email return the same error intentionally — the
response must not reveal which field is wrong.

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
- Rotation and revocation are implemented in Task 9 (`POST /auth/refresh`).

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

| Variable     | Required | Description                                      |
|--------------|----------|--------------------------------------------------|
| `JWT_SECRET` | Yes      | HS256 signing key — minimum 32 characters (256 bits). Never commit a real value. |

Local dev value is in `.env` (gitignored). CI uses a throwaway value set in
`.github/workflows/backend-ci.yml`.
