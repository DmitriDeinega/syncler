# M1.4 + M1.5 — Auth endpoints + device endpoints

You authored M1.1, M1.2, M1.3. Workspace-write granted to `d:\Projects\syncler\`. Touch only `server/`.

## Context

M1.1 scaffolded the FastAPI app + /health. M1.2 added async DB + Alembic. M1.3 added all 9 tables via `0001_initial_schema`. Now add user authentication (zero-knowledge) and device enrollment.

This is **server-side only** — the actual crypto (Argon2id derivation, master key wrapping) happens client-side. The server only stores blobs and verifies an `auth_key_hash`.

## M1.4 — Auth endpoints

### `POST /v1/auth/signup`
Request body (JSON):
```json
{
  "email": "user@example.com",
  "auth_key_hash": "<base64 SHA-256 of client-derived auth_key, 32 bytes>",
  "encrypted_master_key": "<base64 AES-256-GCM ciphertext blob>",
  "auth_salt": "<base64 16-byte random salt>",
  "argon2_params_version": 1
}
```
Response 201:
```json
{ "user_id": "<UUID>", "created_at": "<ISO8601>" }
```
- Validate email format (use `pydantic.EmailStr`)
- Validate base64 fields decode to expected lengths (`auth_key_hash` = 32 bytes; `auth_salt` = 16 bytes; `encrypted_master_key` >= 32 bytes)
- Hash `auth_key_hash` is stored as-is (it's already a hash); server does NOT re-hash
- 409 if email already exists
- 400 if validation fails
- Insert into `users` table

### `POST /v1/auth/login`
Request:
```json
{
  "email": "user@example.com",
  "auth_key_hash": "<base64 SHA-256 of client-derived auth_key>"
}
```
Response 200:
```json
{
  "user_id": "<UUID>",
  "session_token": "<JWT>",
  "encrypted_master_key": "<base64>",
  "auth_salt": "<base64>",
  "argon2_params_version": 1
}
```
- Look up user by email
- Compare submitted `auth_key_hash` to stored value using `secrets.compare_digest` (constant-time)
- On match: issue JWT signed with `JWT_SECRET` from settings, payload = `{ sub: user_id, iat, exp (24h) }`
- On mismatch or unknown user: 401 with generic "invalid credentials" (no email enumeration)
- Return the encrypted master key blob so client can decrypt locally
- Increment rate-limit counter (the middleware is M1.6 but the endpoint should accept a `Depends(rate_limit("login"))` placeholder so M1.6 only needs to wire the middleware)

### Auth helper
Create `server/app/auth.py`:
- `create_access_token(user_id: UUID) -> str` — issues JWT
- `decode_access_token(token: str) -> UUID` — verifies + returns user_id, raises `HTTPException(401)` on invalid
- `current_user(token: str = Depends(oauth2_scheme)) -> User` — FastAPI dependency that loads the User from DB (use SQLAlchemy session via `get_db`)
- Use `python-jose` or `pyjwt` (add to pyproject.toml dependencies)

### `DELETE /v1/account`
- Auth required (uses `current_user`)
- Cascades delete user → all devices, pairings, messages, delivery_status, encrypted_user_state via FK ON DELETE CASCADE
- Senders/plugins audit metadata is NOT deleted (those rows survive)
- Return 204 No Content

## M1.5 — Device endpoints

### `POST /v1/auth/devices/enroll`
Auth required.
Request:
```json
{
  "public_key": "<base64 Ed25519 public key, 32 bytes>",
  "fcm_token": "<string, optional, may be added later via separate endpoint>"
}
```
Response 201:
```json
{ "device_id": "<UUID>", "created_at": "<ISO8601>" }
```
- Insert into `devices` table for `current_user`
- 400 if public_key is not 32 bytes when decoded

### `POST /v1/auth/devices/{device_id}/revoke`
Auth required. Sets `revoked_at = now()` for the device. 404 if device doesn't exist OR belongs to another user. 204 on success.

### `GET /v1/auth/devices`
Auth required. Returns list of user's devices (id, created_at, last_seen, revoked_at, has_fcm_token boolean — never returns the public_key or fcm_token directly).

## Files to create / modify

- `server/app/auth.py` — NEW: JWT helpers + `current_user` dependency
- `server/app/routers/__init__.py` — NEW
- `server/app/routers/auth.py` — NEW: signup, login, account-delete routes
- `server/app/routers/devices.py` — NEW: enroll, revoke, list routes
- `server/app/schemas.py` — NEW: Pydantic schemas for requests/responses (`SignupRequest`, `SignupResponse`, `LoginRequest`, `LoginResponse`, `DeviceEnrollRequest`, `DeviceEnrollResponse`, `DeviceListItem`)
- `server/app/main.py` — UPDATE: `include_router(auth.router, prefix="/v1/auth")` and `include_router(devices.router, prefix="/v1/auth/devices")`. Adjust prefixes so URLs match the spec above.
- `server/app/services/__init__.py` — NEW
- `server/app/services/users.py` — NEW: service-layer functions for user create / lookup / delete (separate from route handlers so logic is testable without HTTP)
- `server/app/services/devices.py` — NEW: service-layer for device enroll / revoke / list
- `server/pyproject.toml` — UPDATE: add `pyjwt[crypto]` (or `python-jose[cryptography]`, choose one), `email-validator` for `EmailStr`
- `server/tests/test_auth.py` — NEW: tests for signup, login (success + wrong password + unknown user), account delete, full auth flow
- `server/tests/test_devices.py` — NEW: tests for device enroll + revoke + list, including auth-required failure modes

## Constraints

- Service layer is the source of truth; route handlers translate HTTP ↔ service calls
- Generic error messages (no email enumeration on login)
- Constant-time hash comparison (`secrets.compare_digest`)
- JWT expires in 24h
- All async (route handlers, service functions, DB calls)
- Pydantic 2 syntax (`Annotated[..., Field(...)]` is fine, avoid V1-style)
- Tests use the same `httpx.AsyncClient + ASGITransport` pattern from M1.1
- For tests that need a DB, use an in-memory SQLite (mark `@pytest.mark.skip` if SQLAlchemy 2 + SQLite + JSONB/UUID issues — note in test docstring)

## After files created

Print summary:
- Files created/modified
- JWT library chosen
- Any test skipped due to SQLite/postgres incompatibility (with justification)
