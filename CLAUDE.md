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
Routing.kt            — ALL route registrations go here (single source of truth)
routing/
AuthRouting.kt        — POST /auth/register, POST /auth/login, POST /auth/refresh
TransactionRouting.kt — GET/POST /transactions
CategoryRouting.kt    — GET/POST/PUT/DELETE /categories
HealthRouting.kt      — GET /health
service/
AuthService.kt        — BCrypt, JWT issue/refresh logic
TransactionService.kt — business logic, idempotency enforcement
CategoryService.kt    — category CRUD, fallback reassignment on delete
InsightsService.kt    — LLM call + SSE stream
repository/
UserRepository.kt
TransactionRepository.kt
CategoryRepository.kt — CategoryType enum, seeding defaults
redis/
RedisClient.kt        — Jedis pool wrapper

## Plugin registration order (Application.kt)
configureDatabase() → configureSerialization() → configureRateLimit()
→ configureAuth() → configureRouting()
Order matters: auth middleware must load after serialization.

## Routing convention
- plugins/Routing.kt is the single place where all routes are wired.
- configureRouting() receives service instances as parameters (injected from Koin in Application.kt).
- Every new routing file (e.g. FooRouting.kt) must be added to configureRouting() — never call
  configure*Routing() directly from Application.kt.
- Health route is always registered; service routes are only registered when their service is provided
  (allows health-only test setups without Koin).

## Database
- The app is NOT yet published. Schema lives in V1__init.sql as a single source of truth.
- To change schema: update V1__init.sql, then apply the same SQL directly to the running DB,
  and restart the app (docker compose restart backend). Do NOT create new Vn__ migration files.
- When the DB volume is wiped, docker compose up recreates the full schema from V1.
- ORM: Jetbrains Exposed (DSL style, not DAO). No raw JDBC.
- Transactions table is RANGE-partitioned by occurred_at.

## Categories
- Seeded on user registration: Food, Transport, Shopping, Health, Entertainment, Housing,
  Other (expense fallback), Income, Income-Other (income fallback) — 9 total, inside the
  same DB transaction as user creation.
- Categories have a type (expense|income) and an is_fallback flag.
- Deleting a non-fallback category atomically reassigns its transactions to the same-type
  "Other" fallback before deleting.
- Fallback categories cannot be deleted.
- Transactions reference category_id (FK); type is derived from the category.

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
docker compose up   — starts postgres + redis + backend
Health check:       curl http://localhost:8080/health

## Testing
- Framework: JUnit5 + MockK.
- Every new route must have at minimum: happy path + auth-missing 401 test.
- Run: ./gradlew test
- Note: kotlin.test.assertIs<T>() returns T not Unit — if it is the final expression in a
  runBlocking test, JUnit 5 silently skips the test. End test blocks with assertTrue or
  a void-returning call (assertEquals, verify, etc.) instead.

## Android client
- Client repo: ../FinanceTracker-Android (separate project, separate Git repo).
- API contract: docs/openapi.yaml — update this before changing any endpoint signature.
- SSE endpoint (/insights) streams text/event-stream. Do not add Content-Length header.

## Do not
- Commit .env or any file with real credentials.
- Create new Flyway migration files (app not yet published — edit V1__init.sql directly).
- Return stack traces in HTTP responses — log them server-side, return a generic error body.
- Use blocking IO on Ktor's coroutine dispatcher — use Dispatchers.IO for DB calls.
- Add endpoints without updating docs/openapi.yaml.
- Call configure*Routing() from Application.kt — use Routing.kt for all route wiring.
