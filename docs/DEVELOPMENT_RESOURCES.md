# Running TradeFlow on an 8 GB Mac (4 GiB Docker VM)

Target machine: MacBook Air M1, 8 GB RAM, Docker Desktop 4 GiB memory / 2 GiB swap, with IntelliJ and
Claude Code running alongside. Raising Docker's memory is **not** part of the workflow: macOS itself
needs the rest.

## Measurements (1 Nov 2026, full stack, idle after startup)

| | before tuning | after tuning |
|---|---|---|
| VM memory used | 3245 MiB | 2807 MiB |
| VM swap used | 1029 MiB (of 1 GiB; full) | 30 MiB |
| VM memory available | 507 MiB | 931 MiB |
| containers | 12 (2 market-data replicas) | 11 (1 replica) |
| full Playwright suite (27 tests) | random 503s, 8-67 s responses | 27/27 in 1.3 min |
| peak during the suite | swap full, stalls | 2.9 GiB used, swap peak 44 MiB |

Per-JVM breakdown (actuator, order-service, before tuning): heap used 112 MB of 136 MB committed;
**metaspace 134 MB**, compressed class space 18 MB, code cache 38 MB, 54 threads. Non-heap is larger
than the heap for every service (175-200 MB), so a smaller `-Xmx` alone would not have helped much.

## What changed and why

| Setting | Before | After | Effect |
|---|---|---|---|
| JVM heap | `MaxRAMPercentage=65` of 576m (≈374 MB cap) | explicit `-Xmx`: 160m (user, market, learning, live), 192m (order, portfolio), 128m gateway, 96m Eureka | heap can't balloon to the limit before collecting |
| JIT | C1 + C2 | `-XX:TieredStopAtLevel=1` (C1 only) | no C2 compiler threads/arenas, ~half the code cache, faster startup. Lower peak throughput; irrelevant for a laptop stack |
| GC | Serial | Serial + `MinHeapFreeRatio=10`, `MaxHeapFreeRatio=30` | Serial GC returns freed heap after a burst |
| native malloc | glibc default (arena per core) | `MALLOC_ARENA_MAX=2` | less native fragmentation per JVM |
| thread stacks | 1 MB | `-Xss512k` | small saving per thread |
| OOM behaviour | hang in GC | `-XX:+ExitOnOutOfMemoryError` + `restart: unless-stopped` | a JVM that runs out restarts instead of stalling the VM |
| Hikari pool | 10 per service | 5 (min idle 1) | fewer PostgreSQL backends and Java connections |
| PostgreSQL | defaults (128 MB shared buffers, 100 connections) | `shared_buffers=48MB`, `max_connections=60`, `work_mem=2MB` | 119 MiB → 62 MiB |
| Kafka heap | `-Xms256m -Xmx512m`, G1 | `-Xms128m -Xmx256m`, Serial GC, C1 | 402 MiB → 218 MiB |
| Kafka threads/buffers | 3 network, 8 I/O, 128 MB compaction dedupe buffer | 2, 2, 16 MB | the dedupe buffer alone was 128 MB of heap |
| Kafka health check | new JVM every 15 s | every 60 s after start (5 s while starting) | the check's JVM costs ~80 MB for a few seconds |
| container limits | 576m services, 900m Kafka | 448m services, 512m Kafka, 384m gateway, 320m Eureka | ceilings now match real use |
| market-data replicas | 2 | `MARKET_DATA_REPLICAS` (default 1) | saves ~260 MB; `MARKET_DATA_REPLICAS=2 make up` still shows load balancing |
| shutdown | 10 s, then SIGKILL | `stop_grace_period: 30s` | Spring finishes in-flight work and leaves Kafka groups |

The settings are development defaults in `docker-compose.yml`; they don't change application code or
behaviour.

## Start only what you need

| Command | Starts | Typical use |
|---|---|---|
| `make infra-up` | PostgreSQL + Kafka | run one service from IntelliJ |
| `make up-demo` | + Eureka, gateway, frontend, user, market-data, order, portfolio | demo trading work (~1 GB less than everything) |
| `make up-academy` | + learning-service | Academy / readiness work |
| `make up` | everything incl. live-trading-service | live work, full browser suite |
| `make stop-app S=live-trading-service` | stops one service, keeps its container | free memory temporarily |
| `make mem` | per-container and VM memory | check headroom |

Live trading fails closed without learning-service (eligibility cannot be checked), so it only starts
with the full profile.

## Rebuilding without starving the stack

`make up --build` runs Maven **inside** the Docker VM (512 MB heap now, was 768 MB) next to the running
containers. That is what pushed the VM over the edge in phase 5. For day-to-day work:

```
make rebuild S=order-service
```

builds the jar on the Mac (`mvn -pl tradeflow-order-service -am package -DskipTests`), wraps it in a
runtime-only image (`docker/service-prebuilt.Dockerfile`, a few seconds) and restarts only that container
with `--no-deps`. A change in `tradeflow-common` affects every service: rebuild them one at a time.

## Tests

* `mvn verify` runs on the Mac, not in the VM. Maven builds sequentially (no `-T`): each module's tests
  start Spring contexts plus embedded Kafka, and parallel modules on 8 GB would compete with IntelliJ.
* `make test-postgres` now passes `-Dtradeflow.test.postgres=embedded`, so the PostgreSQL compatibility
  tests use embedded PostgreSQL on the Mac instead of starting a Testcontainers PostgreSQL (plus Ryuk)
  inside the VM next to the stack. `-Dtradeflow.test.postgres=docker` forces Testcontainers.
* Playwright already runs with one worker; with the tuning above the full suite runs against the full
  stack without failures.

## The unexpected shutdown on 31 Oct (not an OOM kill)

All application containers exited at 19:38:24-27 UTC within three seconds of each other, eight with exit
code 137 and the frontend with 0; PostgreSQL and Kafka exited with 0 and were back four seconds later.
`OOMKilled=false` for all of them.

Docker Desktop's own log (`~/Library/Containers/com.docker.docker/Data/log/host/com.docker.backend.log`)
shows the cause:

```
19:37:22  GUI settings changed: vm.resources.cpus 4                  restart needed: true
19:37:48  GUI settings changed: cpus 4, vm.resources.swapMiB 2058    restart needed: true
19:38:23  POST /app/settings/gui  -> applying settings changes -> engine stop requested
```

Applying the new CPU/swap settings in Docker Desktop restarted the Docker engine. The engine sent
SIGTERM to every container and SIGKILL after the 10 s default grace period. The Spring services, slowed
by the swap pressure at the time, did not finish shutting down in 10 s, hence 137 (128 + SIGKILL). nginx
and PostgreSQL stopped cleanly (0). After the restart only PostgreSQL and Kafka came back, because only
they had `restart: unless-stopped`.

Fixes: application containers now have `restart: unless-stopped` (they come back after a Docker Desktop
restart or a Mac reboot) and `stop_grace_period: 30s`.

Current Docker Desktop settings: 4 CPUs (was 8), swap 2 GiB (was 1 GiB), memory unchanged (3.8 GiB
usable). 4 CPUs is enough for this stack; the larger swap is a useful safety margin.
