# syntax=docker/dockerfile:1
# TradeFlow web frontend: build the static bundle with Node, serve it with an unprivileged nginx
# that also reverse-proxies /api to the API gateway (same origin, so no CORS is needed).

# ---- build
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
# Cap Node's heap: the build runs inside the Docker VM next to the running stack.
ENV NODE_OPTIONS=--max-old-space-size=512
RUN npm run build

# ---- runtime: nginx as non-root (uid 101), listening on 8080
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
