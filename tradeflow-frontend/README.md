# TradeFlow web frontend

React 19 + TypeScript + Vite + Tailwind CSS. It talks only to the TradeFlow API gateway, through a
same-origin `/api` proxy (Vite in development, nginx in Docker). See the
[main README](../README.md#web-frontend) for screens, endpoints and the session model.

```bash
nvm use                 # Node 22 (.nvmrc)
npm install
npm run dev             # http://localhost:5173, proxies /api to http://localhost:8000
                        # (TRADEFLOW_GATEWAY_URL=http://host:port to point elsewhere)
npm run typecheck && npm test       # TypeScript + Vitest unit tests
npx playwright install chromium     # once
npm run test:e2e                    # browser journey against the running stack (E2E_BASE_URL to override)
```

Layout: `src/api` (typed client and endpoint functions mirroring the backend DTOs), `src/auth`
(session + JWT expiry), `src/lib` (data hooks, formatting), `src/components`, `src/pages`, `e2e/`.
In Docker the app is built by `Dockerfile` and served by `nginx.conf` on port 8080 (published as 3000).
