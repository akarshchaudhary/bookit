.PHONY: up down burst test package

up:
	docker compose up --build -d

down:
	docker compose down -v

package:
	./mvnw -DskipTests package

test:
	./mvnw -Dgroups=concurrency verify

burst:
	./burst.sh http://localhost:8080
