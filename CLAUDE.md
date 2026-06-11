# FinanceTracker — Backend

## What this project is
Ktor REST API for the FinanceTracker Android app. Handles auth, transactions,
AI insights (SSE streaming), push notifications. PostgreSQL + Redis.

## Package structure
src/main/kotlin/
Application.kt          — entry point, plugin registration order matters
plugins/
Database.kt           — HikariCP pool + Flyway migration + Exposed connect
Auth.kt               — JWT verification middleware
Serialization.kt      — kotlinx.serialization JSON config
RateLimit.kt          — Redis sliding window middleware
routing/
AuthRouting.kt        — POST /auth/register, POST /auth/login, POST /auth/refresh
TransactionRouting.kt — GET/POST /transactions
InsightsRouting.kt    — POST /insights (SSE)
HealthRouting.kt      — GET /health
service/
AuthService.kt        — BCrypt, JWT issue/refresh logic
TransactionService.kt — business logic, idempotency enforcement
InsightsService.kt    — LLM call + SSE stream
repository/
UserRepository.kt
TransactionRepository.kt
redis/
RedisClient.kt        — Jedis pool wrapper

## Plugin registration order (Application.kt)
configureDatabase() → configureSerialization() → configureRateLimit()
→ configureAuth() → configureRouting()
Order matters: auth middleware must load after serialization.

## Database
- Migrations: Flyway. Files in src/main/resources/db/migration/.
- Naming: V1__init.sql, V2__add_index.sql etc.
- Never edit an existing migration — always add a new Vn__ file.
- Transactions table is RANGE-partitioned by occurred_at. Add new partitions in migrations.
- ORM: Jetbrains Exposed (DSL style, not DAO). No raw JDBC.

## Redis key schema
rate:{userId}:{minuteBucket}    INCR+EXPIRE   sliding window rate limiter, TTL 65s
session:{token}                 HSET          user session cache, TTL 900s (sliding)
idem:{uuid}                     SET NX        idempotency lock, TTL 86400s

## Auth
- Passwords: BCrypt (cost 12).
- Access token: JWT, 15-min expiry, signed with HS256.
- Refresh token: stored in DB (hashed), rotated on every use.
- /health is excluded from JWT middleware.

## Environment variables (never commit values)
DB_URL, DB_USER, DB_PASSWORD   — PostgreSQL connection
REDIS_HOST, REDIS_PORT         — Redis connection
JWT_SECRET                     — HS256 signing key
LLM_API_KEY                    — AI insights service key
APP_VERSION                    — injected by CI, shown in /health response

## Running locally
docker-compose up   — starts postgres + redis + backend
Health check:       curl http://localhost:8080/health

## Testing
- Framework: JUnit5 + MockK.
- Integration tests: Testcontainers (postgres + redis spun up per test class).
- Every new route must have at minimum: happy path + auth-missing 401 test.
- Run: ./gradlew test

## Android client
- Client repo: ../FinanceTracker-Android (separate project, separate Git repo).
- API contract: docs/openapi.yaml — update this before changing any endpoint signature.
- SSE endpoint (/insights) streams text/event-stream. Do not add Content-Length header.

## Do not
- Commit .env or any file with real credentials.
- Edit existing Flyway migrations.
- Return stack traces in HTTP responses — log them server-side, return a generic error body.
- Use blocking IO on Ktor's coroutine dispatcher — use Dispatchers.IO for DB calls.
- Add endpoints without updating docs/openapi.yaml.