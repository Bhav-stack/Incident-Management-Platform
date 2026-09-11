# Dev shortcuts — see docs/IMPLEMENTATION_PLAN.md for the full build order.
.PHONY: up down reset ps logs build test

up:
	docker compose up -d

down:
	docker compose down

reset:
	docker compose down -v

ps:
	docker compose ps

logs:
	docker compose logs -f --tail=100

build:
	./gradlew build

test:
	./gradlew test