start:
	brew services start postgresql@16
	docker compose up -d
	./gradlew run

stop:
	-lsof -ti :8080 | xargs kill 2>/dev/null
	-brew services stop postgresql@16
	-docker compose down 2>/dev/null

db:
	brew services start postgresql@16

.PHONY: start stop db
