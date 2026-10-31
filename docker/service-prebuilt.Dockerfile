# syntax=docker/dockerfile:1
# Runtime image from a jar built on the host (make rebuild S=<service>). Same runtime as
# service.Dockerfile, but Maven runs on the Mac instead of inside the Docker VM, so an image
# rebuild doesn't compete with the running stack for the VM's memory.
FROM eclipse-temurin:17.0.18_8-jre-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home tradeflow
ARG SERVICE
WORKDIR /app
COPY tradeflow-${SERVICE}/target/tradeflow-${SERVICE}-0.0.1-SNAPSHOT-exec.jar /app/app.jar
USER tradeflow
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
