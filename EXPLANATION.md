# Component Explanations

---

## Netty

**What it is:** An asynchronous, non-blocking network I/O framework for the JVM.

**Why it's here:** Ktor needs a server engine to accept HTTP connections. Netty is the choice here because it handles thousands of concurrent connections on a small thread pool by using an event loop instead of blocking one OS thread per request. This matters for endpoints like `/insights` that stream SSE responses — a blocking engine would tie up a thread for the entire duration of each stream.

**How it fits in:** Ktor is not a server itself — it's a framework that sits on top of an engine. The engine is swapped by changing one dependency (`ktor-server-netty`). The entry point `io.ktor.server.netty.EngineMain` reads `application.conf`, starts the event loop, and hands control to the application module.

**Associated files:**
- `build.gradle.kts` — `ktor-server-netty` dependency
- `src/main/resources/application.conf` — `port = 8080`, module reference
- `src/main/kotlin/com/financetracker/Application.kt` — `fun Application.module()` entry point

**Associated commands:**
```bash
./gradlew run          # starts Netty via EngineMain
make start             # same, after starting infrastructure
lsof -ti :8080 | xargs kill   # stop the running server
```

---

## HikariCP

**What it is:** A JDBC connection pool library.

**Why it's here:** Opening a new database connection for every request is expensive — it involves a TCP handshake, PostgreSQL authentication, and process allocation on the server side, which adds 20–100ms of overhead. HikariCP maintains a pool of already-open connections and lends them out to threads on demand. With `maximumPoolSize = 10`, up to 10 queries can run in parallel; others wait in a queue rather than creating new connections.

**How it fits in:** `configureDatabase()` builds a `HikariDataSource` from the env-var-backed config, then passes that same data source to both Flyway (for migrations) and Exposed (for queries). Everything goes through the same pool.

**Associated files:**
- `src/main/kotlin/com/financetracker/plugins/Database.kt` — pool construction and configuration
- `src/main/resources/application.conf` — `database.poolSize = 10`
- `gradle/libs.versions.toml` — `hikaricp` version

**Log to watch for on startup:**
```
HikariPool-1 - Start completed.
```

---

## Flyway

**What it is:** A database migration tool. It runs versioned SQL scripts against the database in order and tracks which ones have already been applied.

**Why it's here:** The database schema needs to evolve as the app evolves. Without a migration tool, schema changes would be applied manually and inconsistently across environments. Flyway guarantees that every environment (local, staging, production) runs exactly the same SQL in exactly the same order, and never runs the same script twice.

**How it fits in:** On every startup, `configureDatabase()` calls `Flyway.configure().dataSource(pool).load().migrate()`. Flyway checks `flyway_schema_history` (a table it manages in the database), compares it against the files in `db/migration/`, and runs any scripts it hasn't seen before. If the app is already up to date, migration is a no-op.

**Naming rule:** Migration files must follow the pattern `V{number}__{description}.sql`. The double underscore is required. Never edit an existing file — Flyway checksums each script and will refuse to start if a previously applied script has changed. Always add a new `V2__...sql`, `V3__...sql` etc.

**Associated files:**
- `src/main/kotlin/com/financetracker/plugins/Database.kt` — migration trigger on startup
- `src/main/resources/db/migration/V1__init.sql` — initial schema: `users`, `transactions` (partitioned), partition tables for 2025/2026, indexes

**Log to watch for on startup:**
```
Successfully applied 1 migration to schema "public"
```

**Manually inspect migration state:**
```bash
/opt/homebrew/opt/postgresql@16/bin/psql -h localhost -p 5433 -U financetracker financetracker \
  -c "SELECT version, description, success FROM flyway_schema_history;"
```

---

## Exposed

**What it is:** Jetbrains' SQL library for Kotlin. Used here in DSL style (not DAO style), which means queries are written as Kotlin expressions that map directly to SQL rather than through entity objects.

**Why it's here:** Raw JDBC is verbose and error-prone. Exposed provides a type-safe query DSL that compiles SQL at build time, eliminates string concatenation bugs, and integrates cleanly with Kotlin coroutines via the `transaction { }` block. DSL style was chosen over DAO style because it keeps the data layer explicit — you see exactly what SQL is being produced.

**How it fits in:** `Database.connect(dataSource)` in `configureDatabase()` registers the HikariCP pool with Exposed's connection manager. After that, any code in the codebase can open a `transaction { }` block and run queries. All DB calls must be wrapped in `withContext(Dispatchers.IO) { transaction { } }` to avoid blocking Ktor's coroutine dispatcher.

**Associated files:**
- `src/main/kotlin/com/financetracker/plugins/Database.kt` — `Database.connect(dataSource)`
- `gradle/libs.versions.toml` — `exposed-core`, `exposed-jdbc`, `exposed-kotlin-datetime`
- Future: `src/main/kotlin/com/financetracker/repository/` — all actual queries live here

**Example pattern (for future repository files):**
```kotlin
suspend fun findUserByEmail(email: String) = withContext(Dispatchers.IO) {
    transaction {
        Users.select { Users.email eq email }.singleOrNull()
    }
}
```

---

## Jedis

**What it is:** The official Java/Kotlin Redis client.

**Why it's here (declared now, wired in Sprint 2):** Redis serves three roles in this project:
1. **Rate limiting** — a sliding window counter (`rate:{userId}:{minuteBucket}`) prevents API abuse without hitting the database.
2. **Session cache** — recently verified JWTs are cached (`session:{token}`) to avoid re-parsing and re-verifying on every request.
3. **Idempotency locks** — a `SET NX` operation on `idem:{uuid}` ensures that duplicate transaction submissions (e.g. from a retried mobile request) are detected and rejected within a 24-hour window.

The dependency is declared in `libs.versions.toml` and `build.gradle.kts` now so that the Gradle dependency graph is resolved. The actual `RedisClient.kt` wrapper and its wiring into the plugin chain will be added in Sprint 2.

**Associated files:**
- `gradle/libs.versions.toml` — `jedis` version
- `build.gradle.kts` — `implementation(libs.jedis)`
- `src/main/resources/application.conf` — `REDIS_HOST`, `REDIS_PORT` env vars (Sprint 2)
- Future: `src/main/kotlin/com/financetracker/redis/RedisClient.kt`

**Key schema (for reference):**
| Key pattern | Command | TTL | Purpose |
|---|---|---|---|
| `rate:{userId}:{minuteBucket}` | INCR + EXPIRE | 65s | Sliding window rate limiter |
| `session:{token}` | HSET | 900s (sliding) | JWT session cache |
| `idem:{uuid}` | SET NX | 86400s | Idempotency lock |

---

## Docker

**What it is:** A container runtime. `docker compose` (the `docker-compose.yml` file) defines and starts the infrastructure services the app depends on.

**Why it's here:** The app itself runs on the JVM, but it depends on PostgreSQL and Redis. Docker lets every developer (and CI) spin up identical instances of those services with a single command, without installing them globally or worrying about version mismatches.

**Current local setup note:** Docker Desktop is not yet fully installed on this machine. PostgreSQL 16 is running via Homebrew on port 5433 instead. The `docker-compose.yml` is ready for when Docker is available, and maps the host port `5433` to the container's `5432` so the connection string stays the same in both setups.

**Associated files:**
- `docker-compose.yml` — defines `postgres:16-alpine` (port 5433) and `redis:7-alpine` (port 6379)
- `.env` / `.env.example` — connection strings consumed by both Docker and the app
- `Makefile` — `make start` / `make stop` / `make db` convenience targets

**Associated commands:**
```bash
docker compose up -d     # start postgres + redis in background
docker compose down      # stop and remove containers
docker compose ps        # check running services
docker compose logs -f   # tail logs from all services
make stop                # stop server + docker + brew postgres in one command
```
