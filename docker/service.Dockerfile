# syntax=docker/dockerfile:1
# One Dockerfile for every service:  docker build -f docker/service.Dockerfile --build-arg SERVICE=order-service .

# ---- build: compile all four services once. The stage does not depend on SERVICE, so
# BuildKit builds it a single time and every service image reuses the cached result.
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
ENV MAVEN_OPTS="-Xmx768m"
# The cache mount keeps ~/.m2 between builds, so dependencies download once.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package \
      -pl tradeflow-user-service,tradeflow-market-data-service,tradeflow-order-service,tradeflow-portfolio-service -am

# ---- runtime: JRE only, non-root user, curl for the compose health check
FROM eclipse-temurin:17.0.18_8-jre-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home tradeflow
ARG SERVICE
WORKDIR /app
COPY --from=build /workspace/tradeflow-${SERVICE}/target/tradeflow-${SERVICE}-0.0.1-SNAPSHOT-exec.jar /app/app.jar
USER tradeflow
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
