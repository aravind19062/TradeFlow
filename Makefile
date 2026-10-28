# TradeFlow developer commands. Run `make help` for the list.
SHELL := /bin/bash
COMPOSE := docker compose
SERVICES := user-service market-data-service order-service portfolio-service

.DEFAULT_GOAL := help

help: ## Show this help
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------- Docker
infra-up: ## Start PostgreSQL and Kafka only (services run from IDE/mvn)
	$(COMPOSE) up -d postgres kafka
	@echo "PostgreSQL localhost:5432 | Kafka localhost:9094"

up: ## Build and start everything: infra, Eureka, API gateway, services (2 market-data replicas)
	$(COMPOSE) --profile apps up -d --build
	@echo "API gateway http://localhost:8000 (Swagger: /swagger-ui.html) | Eureka http://localhost:8761 | Logs: make logs"

tools: ## Start Kafka UI on http://localhost:8085 (optional)
	$(COMPOSE) --profile tools up -d kafka-ui

down: ## Stop all containers (data volumes are KEPT)
	$(COMPOSE) --profile apps --profile tools down

ps: ## Show container status and health
	$(COMPOSE) --profile apps --profile tools ps

logs: ## Follow logs of all containers (make logs S=order-service for one)
	$(COMPOSE) --profile apps logs -f --tail=100 $(S)

reset: ## Stop everything and DELETE the PostgreSQL and Kafka volumes (asks for confirmation)
	@read -p "This deletes all TradeFlow PostgreSQL and Kafka data. Type 'reset' to continue: " ans; \
	if [ "$$ans" = "reset" ]; then $(COMPOSE) --profile apps down -v; echo "Volumes removed."; else echo "Aborted."; fi

psql: ## Open psql in the PostgreSQL container (make psql DB=tradeflow_order)
	$(COMPOSE) exec postgres psql -U $${POSTGRES_USER:-tradeflow} -d $${DB:-tradeflow_order}

topics: ## List Kafka topics inside the broker container
	$(COMPOSE) exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --list

dlt: ## Show dead-lettered messages for a topic (make dlt T=trades.executed)
	$(COMPOSE) exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 \
		--topic $${T:-trades.executed}.DLT --from-beginning --timeout-ms 5000 --property print.headers=true

# ---------------------------------------------------------------- Build & test
build: ## Compile and package every module (skip tests)
	mvn -B -q install -DskipTests

test: ## Run all fast tests (H2 + embedded Kafka + in-JVM end-to-end)
	mvn -B verify

test-postgres: ## Also run the PostgreSQL compatibility tests (Testcontainers or embedded PostgreSQL)
	mvn -B verify -Ppostgres-tests

# ---------------------------------------------------------------- Run without Docker
run-h2: build ## Run all four services on file-based H2 (needs Kafka; KAFKA=localhost:9092 for Homebrew)
	@mkdir -p data/logs
	@for s in $(SERVICES); do \
		SPRING_PROFILES_ACTIVE=h2 KAFKA_BOOTSTRAP_SERVERS=$${KAFKA:-localhost:9094} \
		nohup java -jar tradeflow-$$s/target/tradeflow-$$s-0.0.1-SNAPSHOT-exec.jar > data/logs/$$s.log 2>&1 & \
		echo $$! > data/logs/$$s.pid; echo "started $$s (log: data/logs/$$s.log)"; \
	done

stop-h2: ## Stop services started with run-h2
	@for f in data/logs/*.pid; do [ -f "$$f" ] && kill $$(cat $$f) 2>/dev/null && rm -f $$f; done; echo stopped

demo: ## Run the scripted API walkthrough against running services
	./scripts/demo.sh

.PHONY: help infra-up up tools down ps logs reset psql topics dlt build test test-postgres run-h2 stop-h2 demo
