# ── Local dev (./gradlew run + Homebrew postgres) ──────────────────────────────

# Start Homebrew postgres on port 5433, then run the server via Gradle
# (reads .env automatically; use this mode when developing from IntelliJ too)
dev:
	brew services start postgresql@16
	./gradlew run

# Start only Homebrew postgres (for running the server from IntelliJ manually)
db:
	brew services start postgresql@16

# Stop the Gradle server + Homebrew postgres
stop-dev:
	-lsof -ti :8080 | xargs kill 2>/dev/null
	-brew services stop postgresql@16

# ── Docker stack (all three services in containers) ────────────────────────────

# Build images and start postgres + redis + backend
# First run takes 3-5 min (downloads base images, compiles JAR inside Docker)
docker-up:
	docker compose up --build

# Same but detached (logs via: docker compose logs -f)
docker-up-detached:
	docker compose up --build -d

# Stop and remove containers (named volume pg_data is preserved)
docker-down:
	docker compose down

# Stop containers AND delete the postgres volume (full reset)
docker-reset:
	docker compose down -v

# Drop and re-apply the full DB schema without touching the Docker volume.
# Use after editing V1__init.sql: wipes all tables, restarts backend so Flyway
# re-runs V1 from scratch and recreates everything clean.
db-schema:
	docker compose exec -T postgres psql -U financetracker -d financetracker \
		-c "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO financetracker;"
	docker compose restart backend

# ── Verify the running Docker stack ───────────────────────────────────────────
# All three targets exec into running Docker containers — requires docker-up first

health:
	curl -s http://localhost:8080/health | python3 -m json.tool

# Exec into the postgres container to list tables
tables:
	docker compose exec postgres psql -U financetracker -d financetracker -c "\dt"

# Exec into the redis container to check connectivity
redis-check:
	docker compose exec redis redis-cli ping

# ── Stop everything ────────────────────────────────────────────────────────────

stop:
	-lsof -ti :8080 | xargs kill 2>/dev/null
	-brew services stop postgresql@16
	-docker compose down 2>/dev/null

.PHONY: dev db stop-dev docker-up docker-up-detached docker-down docker-reset db-schema health tables redis-check stop
